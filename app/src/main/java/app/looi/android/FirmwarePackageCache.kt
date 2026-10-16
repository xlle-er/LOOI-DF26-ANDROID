package app.looi.android

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** A verified package survives network changes and application restarts. */
class FirmwarePackageCache(private val directory: File) {
    companion object {
        fun allowsUpdate(m: JSONObject,includePreview: Boolean=false): Boolean {
            val channel=m.optString("channel","stable")
            return channel=="stable" || (channel=="preview" && includePreview)
        }
        fun verify(bytes: ByteArray,m: JSONObject) {
            require(m.optString("channel","stable") in setOf("stable","preview")){"Unknown update channel"}
            require(m.getString("chip")=="ESP32-S3" && ((m.getString("partition")=="app3M_fat9M_16MB" && m.optString("board","n16r8")=="n16r8") || (m.getString("partition")=="looi_8m" && m.optString("board")=="n8r8")) && m.getInt("size") in 32..3145728){"Incompatible firmware release"}
            val sha=m.getString("sha256");require(sha.matches(Regex("[0-9a-fA-F]{64}"))){"Invalid firmware checksum"}
            require(bytes.size==m.getInt("size") && bytes.firstOrNull()==0xe9.toByte()){"Firmware size or image is invalid"}
            val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            require(actual.equals(sha,true)){"Firmware SHA-256 mismatch"}
        }
    }
    @Synchronized fun read(): Pair<JSONObject,ByteArray>? {
        val metadata=File(directory,"ready.json");if(!metadata.exists())return null
        val m=JSONObject(metadata.readText());val sha=m.getString("sha256")
        require(sha.matches(Regex("[0-9a-fA-F]{64}"))){"Invalid firmware checksum"}
        val image=File(directory,sha.lowercase()+".bin");require(image.length() in 32L..3145728L){"Firmware cache invalid; download again"}
        val bytes=image.readBytes();verify(bytes,m);return m to bytes
    }
    @Synchronized fun save(m: JSONObject,bytes: ByteArray) {
        verify(bytes,m);check(directory.isDirectory||directory.mkdirs())
        val sha=m.getString("sha256").lowercase();val image=File(directory,"$sha.bin");val temp=File(directory,"$sha.tmp")
        temp.writeBytes(bytes);check(temp.renameTo(image)){"Cannot save firmware"}
        val metadata=File(directory,"ready.tmp");metadata.writeText(m.toString());check(metadata.renameTo(File(directory,"ready.json"))){"Cannot save firmware metadata"}
    }
}
