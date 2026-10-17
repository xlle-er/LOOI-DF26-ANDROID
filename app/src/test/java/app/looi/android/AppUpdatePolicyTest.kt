package app.looi.android
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class AppUpdatePolicyTest {
    private fun manifest()=JSONObject().put("packageName","app.looi.companion").put("versionCode",6).put("versionName","0.3.0").put("url","https://github.com/xlle-er/LOOI-DF26-ANDROID/releases/download/v0.3.0/LOOI.apk").put("size",3).put("sha256","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    @Test fun releaseMustBeOfficialHttps(){AppUpdatePolicy.validate(manifest(),"app.looi.companion");listOf("http://github.com/xlle-er/LOOI-DF26-ANDROID/releases/x","https://github.com/attacker/repo/releases/x","https://github.com.evil.test/x","https://user@github.com/xlle-er/LOOI-DF26-ANDROID/releases/x").forEach{assertFalse(AppUpdatePolicy.trustedUrl(it))}}
    @Test fun corruptDownloadsCannotInstall(){val file=java.io.File.createTempFile("looi-update",".apk");try{file.writeText("abc");AppUpdatePolicy.verifyHash(file,manifest());file.writeText("abd");assertThrows(Exception::class.java){AppUpdatePolicy.verifyHash(file,manifest())};file.writeText("abcd");assertThrows(Exception::class.java){AppUpdatePolicy.verifyHash(file,manifest())}}finally{file.delete()}}
    @Test fun packageVersionAndSigningIdentityMustMatch(){AppUpdatePolicy.compatibleIdentity("app.looi.companion","app.looi.companion",6,6,5,setOf("official"),setOf("official"));assertThrows(Exception::class.java){AppUpdatePolicy.compatibleIdentity("app.looi.companion","other",6,6,5,setOf("official"),setOf("official"))};assertThrows(Exception::class.java){AppUpdatePolicy.compatibleIdentity("app.looi.companion","app.looi.companion",6,6,6,setOf("official"),setOf("official"))};assertThrows(Exception::class.java){AppUpdatePolicy.compatibleIdentity("app.looi.companion","app.looi.companion",6,6,5,setOf("official"),setOf("custom"))}}
}
