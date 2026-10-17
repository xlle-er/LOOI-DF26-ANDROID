@file:Suppress("DEPRECATION", "MissingPermission")
package app.looi.android

import android.content.Context
import android.net.*
import android.net.wifi.*
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DeviceSessionState(val info:JSONObject=JSONObject(),val phase:String="idle",val message:String="",val busy:Boolean=false,val passwordPrompt:Boolean=false,val namePrompt:Boolean=false,val release:JSONObject?=null)
class DeviceSession(private val context:Context,private val robot:RobotClient,private val scope:CoroutineScope) {
    val state=MutableStateFlow(DeviceSessionState())
    private val credentials=CredentialStore(context)
    private val prefs=context.getSharedPreferences("looi-updates",Context.MODE_PRIVATE)
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback:ConnectivityManager.NetworkCallback?=null
    private var id=""
    private var pendingHost:String?=null
    var password="";private set
    var manualAddress=""
    var localFirmware:ByteArray?=null;private set
    private var originalRow:RobotRow?=null
    val identity get()=state.value.info.optJSONArray("capabilities")?.let{a->(0 until a.length()).any{a.optString(it)=="device_identity"}}==true
    val preview get()=prefs.getBoolean("preview",false)
    val closeHotspot get()=prefs.getBoolean("close",true)
    fun job(block:suspend ()->Unit) { if(state.value.busy)return;scope.launch {state.update{it.copy(busy=true)};try{block()}catch(e:CancellationException){throw e}catch(e:Exception){state.update{it.copy(message=e.message.orEmpty(),phase=if(it.phase=="checking")"fallback" else it.phase)}}finally{state.update{it.copy(busy=false)}}} }
    suspend fun command(type:String,extra:JSONObject=JSONObject()):JSONObject {
        try{return robot.manage(extra.put("type",type),password)}catch(e:Exception){if(e.message?.contains("password",true)==true)state.update{it.copy(passwordPrompt=true)};throw e}
    }
    suspend fun inspect() {
        if(!robot.ready.value)return
        val info=robot.deviceStatus()
        info.optJSONObject("hardware_profile")?.optString("board")?.let{context.getSharedPreferences("looi-hardware",Context.MODE_PRIVATE).edit().putString("board",it).apply()}
        val next=info.optString("device_id");if(next.isEmpty())return
        if(next!=id){localFirmware=null;id=next;password=runCatching{credentials.get("device.$id")}.getOrDefault("");state.value=DeviceSessionState(info)}else state.update{it.copy(info=info)}
        if(password.isEmpty())state.update{it.copy(passwordPrompt=true)}else authenticate(password)
    }
    fun requestNetwork(address:String){pendingHost=address;promptPassword()}
    suspend fun authenticate(value:String) {
        pendingHost?.let{host->robot.connectWifi(host,value);val info=robot.wifi.info.value;val next=info.getString("device_id");if(next!=id){localFirmware=null;state.value=DeviceSessionState(info,passwordPrompt=true)}else state.update{it.copy(info=info)};id=next;pendingHost=null}
        password=value
        if(identity){val verified=command("admin_auth");state.update{it.copy(info=verified)}}else command("wifi_scan")
        credentials.set("device.$id",password)
        if(prefs.getString("expected.$id",null)==state.value.info.optString("firmware_version")){state.update{it.copy(phase="complete",message=t("升级完成","Update complete"))};prefs.edit().remove("expected.$id").apply()}
        val info=state.value.info
        state.update{it.copy(passwordPrompt=false,namePrompt=identity&&!info.optBoolean("device_name_custom")&&!info.optBoolean("ap_name_custom")&&!prefs.getBoolean("named.$id",false))}
    }
    fun dismissPrompt(){if(state.value.namePrompt)skipName();state.update{it.copy(passwordPrompt=false)}}
    fun promptPassword(){state.update{it.copy(passwordPrompt=true)}}
    fun skipName(){prefs.edit().putBoolean("named.$id",true).apply();state.update{it.copy(namePrompt=false)}}
    suspend fun rename(value:String){require(value.isNotBlank()&&value.toByteArray().size<=24){t("名字最多 24 个 UTF-8 字节","Name must fit 24 UTF-8 bytes")};command("device_name_set",JSONObject().put("name",value));skipName();robot.disconnect();state.update{it.copy(message=t("名称已保存，请重新连接","Name saved. Reconnect the robot"))}}
    suspend fun changePassword(value:String){require(value.length in 8..63&&value.all{it.code in 32..126}){"Password must be 8-63 printable ASCII characters"};command("admin_password_set",JSONObject().put("new_password",value));credentials.set("device.$id",value);password=value;robot.disconnect();state.update{it.copy(message=t("密码已保存，请重新连接","Password saved. Reconnect the robot"))}}
    suspend fun enter(){
        if(password.isEmpty()){promptPassword();return}
        val info=command("device_status");originalRow=robot.robots.value.firstOrNull{it.address==robot.address}
        state.update{it.copy(info=info)};command("ota_start",JSONObject().put("close_ap_after_update",closeHotspot))
        if(info.optBoolean("connected")){
            runCatching{connectHTTP(manualAddress.ifBlank{info.optString("ip")})}
            if(info.optJSONArray("capabilities")?.toString()?.contains("async_ota")==true){state.update{it.copy(phase="checking")};command("firmware_check",JSONObject().put("channel",if(preview)"preview" else "stable"));poll(true);return}
        }
        if(robot.wifi.connected){state.update{it.copy(phase="connected")};return}
        hotspot()
    }
    suspend fun poll(checking:Boolean){
        repeat(600){delay(1000);val info=try{command("ota_status")}catch(e:Exception){if(!checking){verifyReboot();return};throw e};state.update{it.copy(info=info)}
            when(info.optString("ota_phase")){
                "failed"->{state.update{it.copy(phase="fallback",message=info.optString("error"))};return}
                "ready"->if(checking){state.update{it.copy(phase="online",release=info.optJSONObject("release"),message=t("设备已就绪","Device ready"))};return}
                "rebooting"->{verifyReboot();return}
            }
            state.update{it.copy(message=if(checking)t("正在检查版本…","Checking release…") else "${info.optInt("ota_progress")}%")}
        };error(t("升级超时，请重试","Update timed out. Retry"))
    }
    suspend fun hotspot(){val info=command("ota_start",JSONObject().put("hotspot",true).put("close_ap_after_update",closeHotspot));state.update{it.copy(info=info,phase="hotspot",message="")}}
    fun compatiblePackage(m:JSONObject?):Boolean=m!=null&&FirmwarePackageCache.allowsUpdate(m,preview)&&m.optString("board","n16r8")==state.value.info.optJSONObject("hardware_profile")?.optString("board","n16r8")
    fun stageLocal(bytes:ByteArray){
        require(bytes.size in 32..3145728&&bytes[0]==0xe9.toByte()) { "Invalid application image" }
        val info=state.value.info
        if(info.optInt("hardware_protocol")==1){val marker="LOOI_HW_V1|${info.getJSONObject("hardware_profile").getString("board")}|1|${info.getString("partition")}|END".toByteArray();require((0..(bytes.size-marker.size)).any{i->marker.indices.all{j->bytes[i+j]==marker[j]}}) { "Incompatible firmware board" }}
        localFirmware=bytes;state.update{it.copy(release=null,message=t("本地固件已就绪","Local firmware ready"))}
    }
    suspend fun download(){val release=robot.wifi.release(preview);robot.wifi.prepareRelease(release);localFirmware=null;state.update{it.copy(release=release,message=t("固件已下载","Firmware downloaded"),phase=if(robot.wifi.connected)"connected" else it.phase)}}
    suspend fun connectHTTP(address:String){robot.wifi.connect(address,password);if(robot.wifi.info.value.optString("device_id")!=id){robot.wifi.disconnect();error("Device identity mismatch")};state.update{it.copy(message=t("Wi-Fi 已连接","Wi-Fi connected"))}}
    suspend fun joinHotspot(){
        val ssid=state.value.info.getString("ap_ssid")
        networkCallback?.let{runCatching{connectivity.unregisterNetworkCallback(it)}};networkCallback=null
        if(Build.VERSION.SDK_INT>=29){
            val network=withTimeout(45000){suspendCancellableCoroutine<Network>{continuation->
                val callback=object:ConnectivityManager.NetworkCallback(){
                    override fun onAvailable(network:Network){if(continuation.isActive)continuation.resume(network)}
                    override fun onUnavailable(){if(continuation.isActive)continuation.resumeWithException(IllegalStateException("Hotspot connection cancelled"))}
                    override fun onLost(network:Network){robot.wifi.disconnect()}
                };networkCallback=callback
                continuation.invokeOnCancellation{runCatching{connectivity.unregisterNetworkCallback(callback)}}
                val spec=WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
                connectivity.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build(),callback)
            }}
            robot.wifi.useNetwork(network)
        }else{
            val manager=context.applicationContext.getSystemService(WifiManager::class.java)
            val config=WifiConfiguration().apply{SSID="\"$ssid\"";preSharedKey="\"$password\""}
            val network=manager.addNetwork(config);check(network>=0&&manager.enableNetwork(network,true)){"Unable to join hotspot"};manager.reconnect()
        }
        var failure:Exception=IllegalStateException("Hotspot unavailable")
        repeat(10){try{connectHTTP(manualAddress.ifBlank{"192.168.4.1"});state.update{it.copy(phase="connected")};return}catch(e:Exception){failure=e;delay(1000)}};throw failure
    }
    suspend fun install(){val value=state.value
        val expected=value.release?.optString("version")?:robot.wifi.prepared.value?.optString("version");if(localFirmware==null&&expected!=null)prefs.edit().putString("expected.$id",expected).apply()
        if(value.phase=="online"){command("firmware_install",JSONObject().put("version",value.release!!.getString("version")).put("channel",if(preview)"preview" else "stable").put("close_ap_after_update",closeHotspot));state.update{it.copy(phase="installing")};poll(false)}
        else if(localFirmware!=null){robot.wifi.upload(localFirmware!!,"firmware.bin",closeHotspot=closeHotspot);verifyReboot(expected=null)}
        else{robot.wifi.installPrepared(closeHotspot,preview);verifyReboot()}
    }
    suspend fun verifyReboot(expected:String?=state.value.release?.optString("version")?:robot.wifi.prepared.value?.optString("version")){
        state.update{it.copy(phase="rebooting",message=t("正在重启…","Restarting…"))};robot.wifi.disconnect();robot.wifi.useNetwork(null)
        networkCallback?.let{runCatching{connectivity.unregisterNetworkCallback(it)}};networkCallback=null
        robot.disconnect();delay(3000)
        originalRow?.let{runCatching{robot.connect(it)}}
        repeat(40){delay(1000);if(robot.ready.value){val info=runCatching{command("device_status")}.getOrNull();if(info?.optString("device_id")==id){state.update{it.copy(info=info)};if(expected!=null&&info.optString("firmware_version")==expected){state.update{it.copy(phase="complete",message=t("升级完成","Update complete"))};prefs.edit().remove("expected.$id").apply();return}}}}
        state.update{it.copy(message=t("请重新连接并核对版本","Reconnect and verify the version"))}
    }
    suspend fun exit(){command("ota_exit");state.update{it.copy(phase="idle")}}
}
