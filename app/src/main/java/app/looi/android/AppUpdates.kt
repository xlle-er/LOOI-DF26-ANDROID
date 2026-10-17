@file:Suppress("DEPRECATION")
package app.looi.android

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

data class AppUpdateState(val stage:String="idle",val release:JSONObject?=null,val message:String="")
class AppUpdateManager(private val context:Context,private val scope:CoroutineScope){
    val state=MutableStateFlow(AppUpdateState())
    private val prefs=context.getSharedPreferences("looi-app-update",Context.MODE_PRIVATE)
    private val downloads=context.getSystemService(DownloadManager::class.java)
    private val http=OkHttpClient.Builder().followRedirects(false).build()
    private var working=false
    private fun url(value:String)=AppUpdatePolicy.trustedUrl(value)
    private fun fetch(url:String,api:Boolean=false):ByteArray{
        var target=url
        repeat(6){
            require((api&&target.startsWith("https://api.github.com/repos/xlle-er/LOOI-DF26-ANDROID/"))||url(target)){"Untrusted update URL"}
            http.newCall(Request.Builder().url(target).header("Accept","application/vnd.github+json").build()).execute().use{r->
                if(r.code in listOf(301,302,303,307,308)){target=r.header("Location")?:error("Missing update redirect");return@repeat}
                check(r.isSuccessful){"Update server HTTP ${r.code}"}
                val input=r.body!!.byteStream();val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=262144){"Update manifest too large"};out.write(buffer,0,n)};return out.toByteArray()
            }
        };error("Too many update redirects")
    }
    fun check(manual:Boolean=false){if(working)return;working=true;scope.launch{
        try{
            if(prefs.getLong("download",-1)>=0){resume();return@launch}
            if(!manual&&System.currentTimeMillis()-prefs.getLong("checked",0)<86400000)return@launch
            if(manual)state.value=AppUpdateState("checking",message=t("正在检查更新…","Checking for updates…"))
            val current=context.packageManager.getPackageInfo(context.packageName,0).let{if(Build.VERSION.SDK_INT>=28)it.longVersionCode else it.versionCode.toLong()}
            val manifest=withContext(Dispatchers.IO){
                val releases=JSONArray(String(fetch("https://api.github.com/repos/xlle-er/LOOI-DF26-ANDROID/releases?per_page=20",true)))
                var found:JSONObject?=null
                for(i in 0 until releases.length()){
                    val release=releases.getJSONObject(i);if(release.optBoolean("draft"))continue
                    val assets=release.getJSONArray("assets")
                    for(j in 0 until assets.length())if(assets.getJSONObject(j).optString("name")=="looi-android-update.json"){
                        val m=JSONObject(String(fetch(assets.getJSONObject(j).getString("browser_download_url"))))
                        AppUpdatePolicy.validate(m,context.packageName)
                        if(found==null||m.getLong("versionCode")>found!!.getLong("versionCode"))found=m
                    }
                };found
            }
            prefs.edit().putLong("checked",System.currentTimeMillis()).apply()
            state.value=if(manifest!=null&&manifest.getLong("versionCode")>current)AppUpdateState("available",manifest)else AppUpdateState(message=if(manual)t("已是最新版本","Already up to date")else "")
        }catch(e:CancellationException){throw e}catch(e:Exception){if(manual)state.value=AppUpdateState("error",message=e.message.orEmpty())}finally{working=false}
    }}
    fun dismiss(){state.value=state.value.copy(stage="idle",message="")}
    fun download(){val m=state.value.release?:return;scope.launch{
        try{
            val directory=File(context.getExternalFilesDir(null),"updates");directory.mkdirs();val file=File(directory,"looi-${m.getLong("versionCode")}.apk");file.delete()
            val request=DownloadManager.Request(Uri.parse(m.getString("url"))).setTitle("LOOI ${m.getString("versionName")}").setDestinationUri(Uri.fromFile(file)).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val download=downloads.enqueue(request)
            prefs.edit().putLong("download",download).putString("release",m.toString()).putString("file",file.absolutePath).commit()
            state.value=AppUpdateState("downloading",m)
            while(isActive&&prefs.getLong("download",-1)==download){delay(2000);resume();if(state.value.stage!="downloading")break}
        }catch(e:Exception){state.value=AppUpdateState("error",m,e.message.orEmpty())}
    }}
    suspend fun resume(){
        val id=prefs.getLong("download",-1);if(id<0)return
        val manifest=JSONObject(prefs.getString("release","{}")!!)
        val installed=context.packageManager.getPackageInfo(context.packageName,0).let{if(Build.VERSION.SDK_INT>=28)it.longVersionCode else it.versionCode.toLong()}
        if(installed>=manifest.optLong("versionCode")){prefs.edit().remove("download").remove("release").remove("offered").apply();state.value=AppUpdateState();return}
        val status=downloads.query(DownloadManager.Query().setFilterById(id)).use{c->if(!c.moveToFirst())-1 else c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))}
        when(status){
            DownloadManager.STATUS_SUCCESSFUL->{
                try{withContext(Dispatchers.IO){verify(File(prefs.getString("file","")!!),manifest)};state.value=AppUpdateState("ready",manifest);notifyReady()}
                catch(e:Exception){prefs.edit().remove("download").apply();state.value=AppUpdateState("error",manifest,e.message.orEmpty())}
            }
            DownloadManager.STATUS_FAILED,-1->{prefs.edit().remove("download").apply();state.value=AppUpdateState("error",manifest,t("下载失败，请重试","Download failed. Retry"))}
            else->state.value=AppUpdateState("downloading",manifest)
        }
    }
    private fun verify(file:File,m:JSONObject){
        AppUpdatePolicy.verifyHash(file,m)
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("Invalid APK")
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        val version=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
        val installedVersion=if(Build.VERSION.SDK_INT>=28)installed.longVersionCode else installed.versionCode.toLong()
        fun signatures(p:android.content.pm.PackageInfo)=if(Build.VERSION.SDK_INT>=28)p.signingInfo!!.apkContentsSigners.map{it.toCharsString()}.toSet()else p.signatures!!.map{it.toCharsString()}.toSet()
        AppUpdatePolicy.compatibleIdentity(context.packageName,archive.packageName,m.getLong("versionCode"),version,installedVersion,signatures(installed),signatures(archive))
    }
    fun requestInstall(){prefs.edit().remove("offered").apply();state.value=state.value.copy(stage="install-requested")}
    fun install(activity:Activity){scope.launch{
        try{
            val m=state.value.release?:return@launch
            val downloadId=prefs.getLong("download",-1)
            if(prefs.getLong("offered",-2)==downloadId)return@launch
            withContext(Dispatchers.IO){verify(File(prefs.getString("file","")!!),m)}
            if(Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls()){
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")));return@launch
            }
            val uri=downloads.getUriForDownloadedFile(prefs.getLong("download",-1))?:error("Downloaded APK missing")
            prefs.edit().putLong("offered",downloadId).apply()
            activity.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            state.value=state.value.copy(stage="installing")
        }catch(e:Exception){state.value=state.value.copy(stage="error",message=e.message.orEmpty())}
    }}
    private fun notifyReady(){
        if(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))return
        val manager=context.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("app-updates","LOOI updates",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).putExtra("app_update",true)
        val pending=PendingIntent.getActivity(context,45,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(context,"app-updates")else Notification.Builder(context)
        runCatching{manager.notify(45,builder.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(t("LOOI 更新已下载","LOOI update downloaded")).setContentText(t("点击安装","Tap to install")).setContentIntent(pending).setAutoCancel(true).build())}
    }
}
class AppUpdateReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action!=DownloadManager.ACTION_DOWNLOAD_COMPLETE)return;val pending=goAsync();val runtime=(context.applicationContext as LooiApplication).runtime;runtime.scope.launch{try{runtime.updates.resume()}finally{pending.finish()}}}}
@Composable fun AppUpdatePrompt(r:LooiRuntime,activity:Activity,available:Boolean){
    val state by r.updates.state.collectAsState();val session by r.robot.management.state.collectAsState()
    LaunchedEffect(state.stage,available,session.phase){if(state.stage in listOf("ready","install-requested")&&available&&session.phase in listOf("idle","complete"))r.updates.install(activity)}
    val notifications=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){r.updates.download()}
    if(available&&state.stage=="available")AlertDialog(onDismissRequest={r.updates.dismiss()},title={Text(t("发现新版本","Update available"))},text={Text("LOOI ${state.release?.optString("versionName")}")},confirmButton={TextButton(onClick={if(Build.VERSION.SDK_INT>=33)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)else r.updates.download()}){Text(t("下载","Download"))}},dismissButton={TextButton(onClick={r.updates.dismiss()}){Text(t("稍后","Later"))}})
    if(available&&state.stage=="error")AlertDialog(onDismissRequest={r.updates.dismiss()},title={Text(t("客户端更新","App update"))},text={Text(state.message)},confirmButton={TextButton(onClick={r.updates.dismiss()}){Text(t("关闭","Dismiss"))}})
}
