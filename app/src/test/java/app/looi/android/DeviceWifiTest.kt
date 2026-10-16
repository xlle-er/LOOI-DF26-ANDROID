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
}
