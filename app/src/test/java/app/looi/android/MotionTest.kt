package app.looi.android
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class MotionTest {
    class Transport: RobotTransport {
        override val ready=MutableStateFlow(true)
        val sent=mutableListOf<String>(); var fail=false
        override suspend fun send(payload: JSONObject): JSONObject { sent += payload.getString("type"); if(fail) throw LooiException("超时","Timeout"); return JSONObject().put("type","ack").put("accepted",true) }
    }
    @Test fun preemptionStopsBeforeGrantingHigherPriority() = runTest { val b=Transport();val a=MotionArbiter(b);a.arm(true);a.acquire(MotionSource.FOLLOW);a.acquire(MotionSource.TEST);assertEquals(listOf("stop"),b.sent);assertEquals(MotionSource.TEST,a.owner) }
    @Test fun lowerPriorityCannotPreemptTest() = runTest {val a=MotionArbiter(Transport());a.arm(true);a.acquire(MotionSource.TEST);var failed=false;try{a.acquire(MotionSource.FOLLOW)}catch(_:IllegalStateException){failed=true};assertTrue(failed)}
    @Test fun previousLeaseCannotMoveAfterStop() = runTest {val b=Transport();val a=MotionArbiter(b);a.arm(true);val old=a.acquire(MotionSource.FOLLOW);a.stop();var failed=false;try{a.send(JSONObject().put("type","motion").put("linear",.1).put("angular",0),MotionSource.FOLLOW,old)}catch(_:IllegalStateException){failed=true};assertTrue(failed);assertEquals(listOf("stop"),b.sent)}
    @Test fun stopFailurePropagatesAndRevokesPermission() = runTest {val b=Transport();val a=MotionArbiter(b);a.arm(true);b.fail=true;var failed=false;try{a.stop(true)}catch(_:LooiException){failed=true};assertTrue(failed);assertFalse(a.allowed);assertNull(a.owner)}
}
