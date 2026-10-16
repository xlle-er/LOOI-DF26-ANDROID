package app.looi.android
import org.junit.Assert.*
import org.junit.Test
class DeviceWifiTest {
 @Test fun acceptsPrivateLanAddressesOnly(){
  listOf("192.168.4.1","10.0.0.5","172.16.0.1","172.31.255.1").forEach{assertEquals(it,DeviceWifiClient.localHost(it))}
  listOf("127.0.0.1","169.254.169.254","8.8.8.8","192.168.4.1:80","192.168.4.1/path","user@192.168.4.1","192.168.4.1?x").forEach{assertThrows(Exception::class.java){DeviceWifiClient.localHost(it)}}
 }
 @Test fun computesFirmwareSha256(){assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",DeviceWifiClient.sha256("abc".toByteArray()))}
 @Test fun catalogHasNoRemovedActuator(){assertFalse("head_pitch" in BodyProtocol.supportedCommands);assertTrue(BodyProtocol.supportedCommands.containsAll(listOf("wifi_configure","calibration_set","control_acquire","firmware_install")))}

 @Test fun firmwarePersistsForOfflineInstallAndRejectsCorruption(){
  val dir=java.nio.file.Files.createTempDirectory("looi-firmware-test").toFile()
  try {
   val bytes=ByteArray(64);bytes[0]=0xe9.toByte()
   val m=org.json.JSONObject().put("version","2.0.0").put("chip","ESP32-S3").put("partition","app3M_fat9M_16MB").put("size",64).put("sha256","27585a5e85e1a2afcb9718bcfd03a0fd8822b4cfdfd06862e3a2c0cfd1c092eb")
   FirmwarePackageCache(dir).save(m,bytes)
   assertArrayEquals(bytes,FirmwarePackageCache(dir).read()!!.second)
   val corrupt=bytes.copyOf();corrupt[3]=1
   assertThrows(Exception::class.java){FirmwarePackageCache(dir).save(m,corrupt)}
   assertArrayEquals(bytes,FirmwarePackageCache(dir).read()!!.second)
   java.io.File(dir,m.getString("sha256")+".bin").writeBytes(corrupt)
   assertThrows(Exception::class.java){FirmwarePackageCache(dir).read()}
  }finally{dir.deleteRecursively()}
 }
}
