package app.looi.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class DeviceWifiClient(context: Context, private val scope: CoroutineScope) {
    private val hardwarePrefs=context.getSharedPreferences("looi-hardware",Context.MODE_PRIVATE)
    private val firmwareCache=FirmwarePackageCache(java.io.File(context.filesDir,"firmware"))
    val prepared=MutableStateFlow(runCatching{firmwareCache.read()?.first}.getOrNull())
    val devices=MutableStateFlow<List<Pair<String,String>>>(emptyList())
    val info=MutableStateFlow(JSONObject())
    var connected=false; private set
    var onLost: ()->Unit = {}
    private var http=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).followRedirects(false).build()
    private var host=""; private var password=""; private var token=""
    private val clientId=UUID.randomUUID().toString()
    private var heartbeat: Job?=null; private var epoch=0L
    private var motionEpoch=0L; private var controlToken=0L
    private val commandLock=kotlinx.coroutines.sync.Mutex()
    private val nsd=context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discovery: NsdManager.DiscoveryListener?=null
    companion object {
        const val MANIFEST="https://github.com/xlle-er/LOOI-DF26-Firmware/releases/latest/download/looi-s3-manifest.json"
        fun localHost(value: String): String {
            val h=value.trim().removePrefix("http://").removeSuffix("/")
            val parts=h.split('.').mapNotNull(String::toIntOrNull)
            require(parts.size==4 && parts.all{it in 0..255} && (parts[0]==10 || parts[0]==192&&parts[1]==168 || parts[0]==172&&parts[1] in 16..31)) { "Enter a private LAN IPv4 address" }
            return h
        }
        fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
    fun useNetwork(network:android.net.Network?) { http=http.newBuilder().socketFactory(network?.socketFactory?:javax.net.SocketFactory.getDefault()).dns(if(network==null) Dns.SYSTEM else object:Dns { override fun lookup(hostname:String)=network.getAllByName(hostname).toList() }).build() }
    suspend fun connect(address: String, adminPassword: String) {
        disconnect(); host=localHost(address); password=adminPassword
        val result=refresh(); require(result.optString("chip")=="ESP32-S3") { "Unsupported device" }
        connected=true
        val current=epoch
        heartbeat=scope.launch {
            while(isActive && connected && epoch==current) {
                delay(250)
                try {
                    if(info.value.optString("control_owner")=="http:$clientId" && controlToken!=0L) {
                        val renewing=controlToken
                        try { command(JSONObject().put("type","control_renew")) } catch(e:CancellationException) { throw e } catch(_:Exception) { if(controlToken==renewing)controlToken=0 }
                    }
                    refresh()
                } catch(e:CancellationException){throw e}
                catch(_:Exception){if(epoch==current){disconnect();onLost()};return@launch}
            }
        }
    }
    fun disconnect(){epoch++;motionEpoch++;controlToken=0;connected=false;heartbeat?.cancel();heartbeat=null}
    suspend fun refresh(): JSONObject {
        val result=request("/api/status")
        token=result.getString("csrf_token");info.value=result;result.optJSONObject("hardware_profile")?.optString("board")?.takeIf{it in setOf("n16r8","n8r8")}?.let{hardwarePrefs.edit().putString("board",it).apply()};return result
    }
    suspend fun command(payload: JSONObject): JSONObject {
        val type=payload.optString("type")
        if(type=="stop") { motionEpoch++;controlToken=0;return checkedRequest(payload) }
        val current=motionEpoch
        commandLock.lock()
        try {
            check(current==motionEpoch){"Command cancelled"}
            if(type=="motion" && controlToken==0L) {
                val claim=checkedRequest(JSONObject().put("type","control_acquire"))
                check(current==motionEpoch){"Command cancelled"};controlToken=claim.getLong("control_token")
            }
            val result=checkedRequest(JSONObject(payload.toString()).put("control_token",controlToken))
            check(current==motionEpoch){"Command cancelled"}
            if(type=="control_acquire")controlToken=result.getLong("control_token")
            if(type in setOf("calibration_set","wifi_configure","wifi_forget","ota_start","control_release","firmware_check","firmware_install","hardware_set"))controlToken=0
            return result
        } finally { commandLock.unlock() }
    }
    private suspend fun checkedRequest(payload: JSONObject): JSONObject {
        val result=request("/api/command",JSONObject(payload.toString()).put("id",UUID.randomUUID().toString()))
        check(result.optString("type")!="error" && result.opt("accepted")!=false){result.optString("message","Device rejected command")}
        return result
    }
    private suspend fun request(path: String,payload: JSONObject?=null): JSONObject {
        val builder=Request.Builder().url("http://$host$path").header("Authorization",Credentials.basic("admin",password)).header("X-Looi-Client",clientId)
        if(payload!=null)builder.header("X-OTA-Token",token).post(payload.toString().toRequestBody("application/json".toMediaType()))
        val req=builder.build()
        return withContext(Dispatchers.IO){http.newCall(req).execute().use{r->val body=r.body?.string().orEmpty();check(r.isSuccessful){"Device HTTP ${r.code}"};JSONObject(body)}}
    }
    suspend fun upload(bytes: ByteArray,name: String,expectedSha: String?=null,closeHotspot: Boolean=true) {
        check(connected) { "Connect to the device over Wi-Fi first" }
        require(bytes.size in 32..3145728 && name.endsWith(".bin") && bytes[0]==0xe9.toByte()){ "Select an ESP32-S3 application .bin" }
        if(info.value.optInt("hardware_protocol")==1) {
            val board=info.value.optJSONObject("hardware_profile")?.optString("board").orEmpty()
            val partition=info.value.optString("partition")
            val marker="LOOI_HW_V1|$board|1|$partition|END".toByteArray()
            require((0..bytes.size-marker.size).any { i -> marker.indices.all { j -> bytes[i+j]==marker[j] } }){"Incompatible hardware firmware / 固件与接线配置协议或板型不兼容"}
        }
        val sha=sha256(bytes);require(expectedSha==null||sha.equals(expectedSha,true)){"Firmware SHA-256 mismatch"}
        heartbeat?.cancel();command(JSONObject().put("type","ota_start").put("close_ap_after_update",closeHotspot))
        val form=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("firmware",name,bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        val req=Request.Builder().url("http://$host/update").header("Authorization",Credentials.basic("admin",password)).header("X-OTA-Token",token).header("X-Looi-Client",clientId).header("X-Firmware-Size",bytes.size.toString()).header("X-Firmware-SHA256",sha).post(form).build()
        withContext(Dispatchers.IO){http.newBuilder().callTimeout(180,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build().newCall(req).execute().use{r->val result=JSONObject(r.body?.string().orEmpty());check(r.isSuccessful&&result.optBoolean("accepted")){result.optString("message","Update failed")}}}
        disconnect()
    }
    suspend fun release(includePreview: Boolean=false): JSONObject=withContext(Dispatchers.IO) {
        val boardUrl=if(hardwarePrefs.getString("board","n16r8")=="n8r8") MANIFEST.replace("looi-s3-manifest","looi-s3-n8r8-manifest") else MANIFEST
        fun fetch(channel: String): JSONObject {
            val url=if(channel=="preview") boardUrl.replace("/latest/download/","/download/preview/") else boardUrl
            return JSONObject(fetchHttps(url,8192).toString(Charsets.UTF_8)).put("channel",channel).also{validateRelease(it)}
        }
        if(!includePreview)return@withContext fetch("stable")
        val stable=runCatching{fetch("stable")}.getOrNull()
        val preview=runCatching{fetch("preview")}.getOrNull()
        if(preview!=null&&(stable==null||compareReleaseVersions(preview.getString("version"),stable.getString("version"))>0))preview
        else stable?:error("Online releases unavailable / 在线版本暂不可用")
    }
    private fun validateRelease(m: JSONObject) {
        require(m.getString("version").matches(Regex("""v?[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?""")) && m.getString("chip")=="ESP32-S3" && ((m.getString("partition")=="app3M_fat9M_16MB" && m.optString("board","n16r8")=="n16r8") || (m.getString("partition")=="looi_8m" && m.optString("board")=="n8r8")) && m.getInt("size") in 32..3145728 && m.getString("sha256").matches(Regex("[0-9a-fA-F]{64}"))){"Incompatible firmware release"}
        require(m.getString("url").startsWith("https://github.com/xlle-er/LOOI-DF26-Firmware/releases/")){"Untrusted firmware URL"}
    }
    suspend fun prepareRelease(m: JSONObject) {
        validateRelease(m)
        withContext(Dispatchers.IO){val bytes=fetchHttps(m.getString("url"),3145728);firmwareCache.save(m,bytes)}
        prepared.value=JSONObject(m.toString())
    }
    suspend fun installPrepared(closeHotspot: Boolean=true,includePreview: Boolean=false) {
        val cached=withContext(Dispatchers.IO){firmwareCache.read()}?:error("Download firmware before joining the ESP hotspot")
        require(FirmwarePackageCache.allowsUpdate(cached.first,includePreview)){"Enable preview updates / 请先开启预览版更新"}
        upload(cached.second,"looi-s3.bin",cached.first.getString("sha256"),closeHotspot)
    }
    private fun fetchHttps(url: String,limit: Int): ByteArray {
        var next=url
        repeat(6){
            val parsed=next.toHttpUrl()
            require(parsed.scheme=="https" && parsed.host in setOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")){"Untrusted release redirect"}
            http.newCall(Request.Builder().url(parsed).build()).execute().use{r->
                if(r.code in listOf(301,302,307,308)){next=parsed.resolve(r.header("Location")?:error("Missing redirect"))!!.toString()}
                else {check(r.isSuccessful){"Release unavailable: HTTP ${r.code}"};val input=r.body?.byteStream()?:error("No release data");val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=limit){"Release too large"};output.write(buffer,0,n)};return output.toByteArray()}
            }
        };error("Too many release redirects")
    }
    fun discover(){
        stopDiscovery();devices.value=emptyList()
        val listener=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t:String){}
            override fun onServiceFound(service:NsdServiceInfo){
                @Suppress("DEPRECATION") nsd.resolveService(service,object:NsdManager.ResolveListener{
                    override fun onResolveFailed(s:NsdServiceInfo,error:Int){}
                    override fun onServiceResolved(s:NsdServiceInfo) {
                        val address=s.host?.hostAddress ?: return
                        runCatching { localHost(address) }.onSuccess {
                            scope.launch { devices.value=(devices.value+Pair(s.serviceName,address)).distinctBy { it.second } }
                        }
                    }
                })
            }
            override fun onServiceLost(s:NsdServiceInfo){}
            override fun onDiscoveryStopped(t:String){}
            override fun onStartDiscoveryFailed(t:String,e:Int){stopDiscovery()}
            override fun onStopDiscoveryFailed(t:String,e:Int){}
        }
        discovery=listener;nsd.discoverServices("_looi._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)
    }
    fun stopDiscovery(){discovery?.let{runCatching{nsd.stopServiceDiscovery(it)}};discovery=null}
}
