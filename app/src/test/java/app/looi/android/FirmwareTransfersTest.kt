package app.looi.android
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
class FirmwareTransfersTest {
 @Test fun wiringNeverOverwritesConfiguredDevice(){
  val info=JSONObject().put("hardware_configured",false).put("hardware_profile",JSONObject().put("board","n16r8"))
  assertTrue(needsMotorWiring(info));assertEquals(17,defaultMotorWiring(info)!!.getJSONObject("pins").getInt("ena"))
  info.put("hardware_configured",true);assertNull(defaultMotorWiring(info));assertFalse(needsMotorWiring(JSONObject()))
 }
 @Test fun downloadReportsActualBytesAndRejectsTruncation(){
  val bytes=ByteArray(20000){it.toByte()};val progress=mutableListOf<Double>()
  assertArrayEquals(bytes,readFirmwareStream(ByteArrayInputStream(bytes),bytes.size,bytes.size.toLong()){progress+=it})
  assertEquals(0.0,progress.first(),0.0);assertEquals(1.0,progress.last(),0.0);assertTrue(progress.any{it>0&&it<1})
  assertThrows(IllegalArgumentException::class.java){readFirmwareStream(ByteArrayInputStream(bytes),bytes.size,bytes.size+1L)}
  assertThrows(IllegalArgumentException::class.java){readFirmwareStream(ByteArrayInputStream(bytes),10)}
 }
 @Test fun uploadCountsTheActualRequestBody(){
  val bytes=ByteArray(20000){it.toByte()};val progress=mutableListOf<Double>();val sink=Buffer()
  FirmwareProgressBody(bytes.toRequestBody(),{progress+=it}).writeTo(sink)
  assertArrayEquals(bytes,sink.readByteArray());assertEquals(1.0,progress.last(),0.0);assertTrue(progress.all{it in 0.0..1.0})
 }
}
