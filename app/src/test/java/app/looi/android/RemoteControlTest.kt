package app.looi.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlTest {
    private class Robot {
        var connected=true
        var stopResult=true
        var failure=false
        var pending: CompletableDeferred<Unit>?=null
        var stopPending: CompletableDeferred<Boolean>?=null
        var stops=0
        var takeovers=0
        var faults=0
        val commands=mutableListOf<JSONObject>()
        fun controller(scope: CoroutineScope)=RemoteControl(scope,{connected},send={
            commands+=it
            pending?.await()
            if(failure) error("rejected")
        },stop={stops++;stopPending?.await() ?: stopResult},takeover={takeovers++},fault={faults++})
    }
    @Test fun joystickDeadZoneNonFiniteAndCombinedLimit() {
        assertEquals(DriveVector(),DriveVector.joystick(.05,.05,.15))
        assertEquals(DriveVector(),DriveVector.joystick(Double.NaN,1.0,.15))
        assertEquals(DriveVector(.15,0.0),DriveVector.joystick(0.0,-1.0,.15))
        assertTrue(DriveVector.joystick(-1.0,0.0,.15).angular < 0)
        val v=DriveVector.joystick(3.0,-3.0,10.0)
        assertTrue(kotlin.math.abs(v.linear)+kotlin.math.abs(v.angular) <= .300001)
    }
    @Test fun admissionWaitsForStopAckAndOpeningIsIdempotent()=runTest {
        val robot=Robot();robot.stopPending=CompletableDeferred()
        val c=robot.controller(this);c.open();c.open();runCurrent()
        assertEquals(1,robot.takeovers);assertNull(c.begin());assertEquals(1,robot.stops)
        robot.stopPending!!.complete(true);runCurrent();assertTrue(c.ready)
        c.close();runCurrent()
    }
    @Test fun disconnectedOrUnconfirmedStopCannotAdmitMotion()=runTest {
        val robot=Robot();robot.connected=false;val c=robot.controller(this);c.open();runCurrent()
        assertFalse(c.ready);assertNull(c.begin());assertEquals(RemotePhase.FAILED,c.state.value.phase)
        robot.connected=true;robot.stopResult=false;c.retry();runCurrent();assertFalse(c.ready)
        assertTrue(robot.faults>0);robot.stopResult=true;c.retry();runCurrent();assertTrue(c.ready)
        c.close();runCurrent()
    }
    @Test fun releaseStopsAndHeldStaleGestureCannotRestart()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent()
        val id=c.begin()!!;assertNull(c.begin());c.update(id,0.0,-1.0);runCurrent()
        val command=robot.commands.single();assertEquals(400,command.getInt("duration_ms"));assertEquals(60,command.getInt("ramp_ms"))
        c.release(id);runCurrent();assertTrue(c.ready);assertFalse(c.state.value.moving)
        c.update(id,1.0,0.0);advanceTimeBy(500);runCurrent();assertEquals(1,robot.commands.size)
        assertEquals(2,robot.stops);c.close();runCurrent()
    }
    @Test fun slowAckRetainsOnlyLatestInput()=runTest {
        val robot=Robot();robot.pending=CompletableDeferred();val c=robot.controller(this);c.open();runCurrent()
        val id=c.begin()!!;c.update(id,0.0,-1.0);runCurrent()
        c.update(id,1.0,0.0);c.update(id,0.0,1.0);advanceTimeBy(500);runCurrent()
        assertEquals(1,robot.commands.size)
        robot.pending!!.complete(Unit);runCurrent();advanceTimeBy(100);runCurrent()
        assertEquals(2,robot.commands.size);assertTrue(robot.commands.last().getDouble("linear")<0)
        assertEquals(0.0,robot.commands.last().getDouble("angular"),0.0)
        c.release(id);runCurrent()
    }
    @Test fun lateAckAfterReleaseCannotResume()=runTest {
        val robot=Robot();robot.pending=CompletableDeferred();val c=robot.controller(this);c.open();runCurrent()
        val id=c.begin()!!;c.update(id,0.0,-1.0);runCurrent();c.release(id);runCurrent()
        robot.pending!!.complete(Unit);advanceTimeBy(1000);runCurrent()
        assertEquals(1,robot.commands.size);assertTrue(c.ready);assertFalse(c.state.value.moving)
        c.close();runCurrent()
    }
    @Test fun rejectionStopsRenewalsAndRequiresRetry()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent();robot.failure=true
        c.update(c.begin()!!,1.0,0.0);runCurrent()
        assertEquals(RemotePhase.FAILED,c.state.value.phase);assertNull(c.begin());assertTrue(robot.stops>=2)
        advanceTimeBy(1000);runCurrent();assertEquals(1,robot.commands.size)
        robot.failure=false;c.retry();runCurrent();assertTrue(c.ready);c.close();runCurrent()
    }
    @Test fun modeChangeEmergencyStopAndCloseInvalidateTouches()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent()
        val id=c.begin()!!;c.update(id,1.0,0.0);runCurrent();c.mode(RemoteMode.BUTTONS);runCurrent()
        assertTrue(c.ready);c.update(id,0.0,-1.0);runCurrent();assertEquals(1,robot.commands.size)
        val next=c.begin()!!;c.update(next,0.0,-1.0);runCurrent();c.emergencyStop();runCurrent()
        c.update(next,1.0,0.0);runCurrent();assertEquals(2,robot.commands.size)
        c.close();assertFalse(c.state.value.presented);assertNull(c.begin());runCurrent()
        assertEquals(RemotePhase.INACTIVE,c.state.value.phase)
    }
    @Test fun reopenCannotRaceOutstandingCloseStop()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent()
        robot.stopPending=CompletableDeferred();c.close();runCurrent();c.open()
        assertFalse(c.state.value.presented);assertEquals(RemotePhase.STOPPING,c.state.value.phase)
        robot.stopPending!!.complete(true);runCurrent();assertEquals(RemotePhase.INACTIVE,c.state.value.phase)
        c.open();runCurrent();assertTrue(c.ready);c.close();runCurrent()
    }
    @Test fun connectionFailureInvalidatesHeldGesture()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent()
        val id=c.begin()!!;c.update(id,0.0,-1.0);runCurrent();robot.connected=false;c.connectionLost();runCurrent()
        c.update(id,1.0,0.0);advanceTimeBy(1000);runCurrent();assertEquals(1,robot.commands.size)
        assertEquals(RemotePhase.FAILED,c.state.value.phase);assertFalse(c.state.value.moving)
        c.close();runCurrent()
    }
    @Test fun accessiblePulseStopsAfterTwoHundredMilliseconds()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent()
        c.pulse(0.0,-1.0);runCurrent();advanceTimeBy(200);runCurrent()
        assertTrue(c.ready);assertFalse(c.state.value.moving);assertTrue(robot.stops>=2)
        val count=robot.commands.size;advanceTimeBy(1000);runCurrent();assertEquals(count,robot.commands.size)
        c.close();runCurrent()
    }
    @Test fun returningToCenterStopsOnceWithoutQueuedMotion()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent()
        val id=c.begin()!!;c.update(id,0.0,-1.0);runCurrent();c.update(id,0.0,0.0);advanceTimeBy(100);runCurrent()
        assertFalse(c.state.value.moving);assertEquals(2,robot.stops)
        advanceTimeBy(500);runCurrent();assertEquals(2,robot.stops)
        c.release(id);runCurrent()
    }
    @Test fun silentStopTimesOutWithoutAdmittingMotion()=runTest {
        val robot=Robot();robot.stopPending=CompletableDeferred();val c=robot.controller(this)
        c.open();runCurrent();advanceTimeBy(6000);runCurrent()
        assertEquals(RemotePhase.FAILED,c.state.value.phase);assertNull(c.begin());assertEquals(1,robot.faults)
        robot.stopPending!!.complete(true);runCurrent();assertFalse(c.ready)
        c.close();runCurrent()
    }
    @Test fun silentMotionAckTimesOutAndRequestsStop()=runTest {
        val robot=Robot();val c=robot.controller(this);c.open();runCurrent();robot.pending=CompletableDeferred()
        c.update(c.begin()!!,0.0,-1.0);runCurrent();advanceTimeBy(6000);runCurrent()
        assertEquals(RemotePhase.FAILED,c.state.value.phase);assertEquals(1,robot.commands.size);assertTrue(robot.stops>=2)
        c.close();runCurrent()
    }
    @Test fun remotePreemptsTestButNoOtherSourceCanPreemptRemote()=runTest {
        val b=MotionTest.Transport();val a=MotionArbiter(b);a.arm(true)
        a.acquire(MotionSource.TEST);a.acquire(MotionSource.REMOTE)
        assertEquals(listOf("stop"),b.sent)
        for(source in MotionSource.values().filter {it != MotionSource.REMOTE}) {
            assertThrows(IllegalStateException::class.java) {runBlocking {a.acquire(source)}}
        }
    }
}
