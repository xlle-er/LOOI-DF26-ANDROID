package app.looi.android

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SessionTest {
    @Test fun repeatedStartIsRejected() { val s=SessionGate(); assertNotNull(s.begin()); assertNull(s.begin()); assertEquals(Phase.STARTING,s.phase) }
    @Test fun latePermissionCannotStartCancelledSession() { val s=SessionGate(); val first=s.begin()!!; s.stop(); s.idle(); val second=s.begin()!!; assertFalse(s.active(first)); assertTrue(s.active(second)); assertEquals(Phase.ACTIVE,s.phase) }
    @Test fun testNeverTurnsIntoCompanion() { val s=SessionGate(); val token=s.begin(true)!!; assertTrue(s.active(token)); assertEquals(Phase.TESTING,s.phase) }
    @Test fun stoppingInvalidatesCallbacks() { val s=SessionGate(); val token=s.begin()!!; s.stop(); assertFalse(s.current(token)); assertEquals(Phase.STOPPING,s.phase) }
}
class BodyProtocolTest {
    private fun motion()=JSONObject().put("type","motion").put("linear",1).put("angular",-1)
    @Test fun clampsSpeedDurationAndDefaultRamp() {
        val p=BodyProtocol.validate(motion().put("duration_ms",50),true)
        assertEquals(.4,p.getDouble("linear"),0.0); assertEquals(-.4,p.getDouble("angular"),0.0); assertEquals(25,p.getInt("ramp_ms"))
    }
    @Test fun clampsHead() { val p=BodyProtocol.validate(JSONObject().put("type","head_pitch").put("pulse",999).put("duration_ms",9999),true); assertEquals(620,p.getInt("pulse")); assertEquals(2000,p.getInt("duration_ms")) }
    @Test(expected=IllegalStateException::class) fun noPermissionNoMotion() { BodyProtocol.validate(motion(),false) }
    @Test(expected=IllegalArgumentException::class) fun numericStringIsNotNumber() { BodyProtocol.validate(motion().put("linear","0.1"),true) }
    @Test(expected=LooiException::class) fun unknownCommandRejected() { BodyProtocol.validate(JSONObject().put("type","raw_pwm"),true) }
    @Test fun stopBreaksPartialJson() { val packet=BodyProtocol.packet(JSONObject().put("type","stop"),"a").toString(Charsets.UTF_8); assertTrue(packet.startsWith("\n{")); assertTrue(packet.endsWith("\n")); assertEquals("a",JSONObject(packet.trim()).getString("id")) }
    @Test fun decoderHandlesSplitUtf8AndCombinedLines() {
        val bytes="{\"id\":\"一\"}\n{\"id\":\"b\"}\n".toByteArray(); val decoder=NotificationDecoder()
        assertTrue(decoder.append(bytes.copyOfRange(0,9)).isEmpty())
        val result=decoder.append(bytes.copyOfRange(9,bytes.size)); assertEquals(listOf("一","b"),result.map {it.getString("id")})
    }
    @Test fun invalidLinesDoNotPoisonNextAck() { assertEquals("ok",NotificationDecoder().append("bad\n{\"id\":\"ok\"}\n".toByteArray()).single().getString("id")) }
    @Test fun overflowClearsDecoder() { val d=NotificationDecoder(); assertThrows(LooiException::class.java) {d.append(ByteArray(65537))}; assertEquals(1,d.append("{}\n".toByteArray()).size) }
    @Test fun removesCallerSuppliedId() { assertFalse(BodyProtocol.validate(motion().put("id","forged"),true).has("id")) }
}
class AudioLedgerTest {
    @Test fun thirtySecondsDoesNotOverflowOldTwentySecondLimit() { val q=PcmLedger(); q.append(ByteArray(24000*30*2)); assertEquals(30000L,q.pendingMs) }
    @Test fun writtenAudioRemainsPendingUntilRendered() { val q=PcmLedger(); q.append(ByteArray(4800)); q.wrote(4800); assertEquals(100L,q.pendingMs); assertTrue(q.progress(q.generation,1200)); assertEquals(50L,q.pendingMs); q.progress(q.generation,2400); assertEquals(0L,q.pendingMs) }
    @Test fun partialWritesDoNotSkipBytes() { val q=PcmLedger(); q.append(byteArrayOf(1,2,3,4,5,6)); q.wrote(2); assertArrayEquals(byteArrayOf(3,4,5,6),q.slice(6)) }
    @Test fun recoveryRetainsOnlyUnconfirmedWindow() { val q=PcmLedger(); q.append(ByteArray(4800)); q.wrote(4800); q.progress(q.generation,1200); val old=q.generation; q.recover(); assertEquals(50L,q.pendingMs); assertFalse(q.progress(old,2400)); assertEquals(2400,q.slice(9999).size) }
    @Test fun clearedQueueIgnoresOldCallback() { val q=PcmLedger(); q.append(ByteArray(48)); q.wrote(48); val old=q.generation; q.clear(); q.append(ByteArray(96)); assertFalse(q.progress(old,24)); assertEquals(48,q.pendingFrames) }
    @Test fun capacityIsBounded() { val q=PcmLedger(10); q.append(ByteArray(20)); assertThrows(IllegalStateException::class.java) {q.append(ByteArray(2))}; assertEquals(10,q.pendingFrames) }
    @Test fun impossiblePlaybackPositionIgnored() { val q=PcmLedger(); q.append(ByteArray(20)); q.wrote(10); assertFalse(q.progress(q.generation,99)); assertEquals(10,q.pendingFrames) }
}
class FollowTest {
    private fun detection(x: Float=.4f,w: Float=.2f,h: Float=.4f,time: Long=1000,id: Long=1)=Detection(id,"person",.9f,Box(x,.1f,w,h),time)
    private fun prepared(): FollowPolicy = FollowPolicy().apply { select(detection()); assertTrue(calibrate(1000)); assertTrue(start(1000)) }
    @Test fun needsReference() { val p=FollowPolicy();p.select(detection());assertFalse(p.start(1000)) }
    @Test fun centeredAtReferenceStops() { assertEquals(FollowIntent(),prepared().intent(1000)) }
    @Test fun shrinkingTargetAdvances() { val p=prepared();assertTrue(p.update(listOf(detection(.42f,.16f,.32f,1100)),1100));assertEquals(.1,p.intent(1100).linear,0.0) }
    @Test fun turnTakesPriorityOverForward() { val p=prepared();assertTrue(p.update(listOf(detection(.51f,.2f,.4f,1100)),1100));assertTrue(p.update(listOf(detection(.58f,.2f,.4f,1200)),1200));val v=p.intent(1200);assertEquals(0.0,v.linear,0.0);assertEquals(.1,v.angular,0.0) }
    @Test fun nearTargetNeverReverses() { val p=prepared();p.update(listOf(detection(.38f,.24f,.48f,1100)),1100);assertEquals(FollowIntent(),p.intent(1100)) }
    @Test fun staleFrameStops() { val p=prepared();assertEquals(FollowIntent(),p.intent(1301));assertFalse(p.enabled) }
    @Test fun targetLossRequiresReselection() { val p=prepared();assertFalse(p.update(emptyList(),1100));assertNull(p.target);assertNull(p.reference);assertFalse(p.start(1101)) }
    @Test fun similarTargetsNeverAutoSwitch() { val p=prepared();assertFalse(p.update(listOf(detection(.4f,time=1100,id=2),detection(.41f,time=1100,id=3)),1100));assertNull(p.target) }
    @Test fun trackedIdStaysStable() { val p=prepared();assertTrue(p.update(listOf(detection(time=1100,id=27)),1100));assertEquals(1L,p.target?.id) }
    @Test fun oldFrameCannotCalibrate() { val p=FollowPolicy();p.select(detection());assertFalse(p.calibrate(1500)) }
}
class ResamplerTest {
    @Test fun partialInputGroupsCarryAcrossReads() { val c=Pcm48To16();assertEquals(0,c.convert(byteArrayOf(3,0,6,0)).size);assertArrayEquals(byteArrayOf(6,0),c.convert(byteArrayOf(9,0))) }
    @Test fun oneSecondHasExactTargetSampleCount() { assertEquals(32000,Pcm48To16().convert(ByteArray(96000)).size) }
}
class ImageGeometryTest {
    @Test fun landscapeLetterboxMapsBackWithoutDistortion() {val g=Letterbox(640f,480f);assertEquals(Box(0f,0f,1f,1f),g.normalized(0f,40f,320f,280f))}
    @Test fun portraitLetterboxMapsBackWithoutDistortion() {val g=Letterbox(480f,640f);assertEquals(Box(0f,0f,1f,1f),g.normalized(40f,0f,280f,320f))}
}
