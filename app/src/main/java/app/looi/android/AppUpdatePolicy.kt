package app.looi.android

import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

object AppUpdatePolicy {
    fun trustedUrl(value:String):Boolean=runCatching{val u=URI(value);u.scheme=="https"&&u.userInfo==null&&(u.port==-1||u.port==443)&&((u.host=="github.com"&&u.path.startsWith("/xlle-er/LOOI-DF26-ANDROID/releases/"))||u.host in listOf("release-assets.githubusercontent.com","objects.githubusercontent.com"))}.getOrDefault(false)
    fun validate(m:JSONObject,packageName:String){require(m.getString("packageName")==packageName&&m.getLong("versionCode")>0&&m.getLong("size") in 1..268435456&&Regex("[a-f0-9]{64}").matches(m.getString("sha256"))&&trustedUrl(m.getString("url"))){"Invalid update manifest"}}
    fun verifyHash(file:File,m:JSONObject){
        require(file.length()==m.getLong("size")){"APK size mismatch"}
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it)}==m.getString("sha256")){"APK SHA-256 mismatch"}
    }
    fun compatibleIdentity(expectedPackage:String,actualPackage:String,expectedVersion:Long,actualVersion:Long,currentVersion:Long,installedSigners:Set<String>,downloadedSigners:Set<String>){
        require(expectedPackage==actualPackage&&expectedVersion==actualVersion&&actualVersion>currentVersion){"APK package or version mismatch"}
        require(installedSigners.isNotEmpty()&&installedSigners==downloadedSigners){t("签名不同，无法覆盖安装此更新","Signing certificate differs; this update cannot replace the installed app")}
    }
}
