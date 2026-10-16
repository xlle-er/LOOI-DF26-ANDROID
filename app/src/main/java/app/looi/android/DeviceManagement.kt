package app.looi.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable fun DeviceManagementDialog(r: LooiRuntime,onDismiss: ()->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var address by remember{mutableStateOf("192.168.4.1")};var admin by remember{mutableStateOf("12345678")}
    var ssid by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var left by remember{mutableStateOf(true)};var right by remember{mutableStateOf(false)};var swap by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var info by remember{mutableStateOf(JSONObject())}
    var release by remember{mutableStateOf<JSONObject?>(null)};var networks by remember{mutableStateOf<List<String>>(emptyList())}
    val found by r.robot.wifi.devices.collectAsState()
    fun job(block:suspend ()->Unit){scope.launch{busy=true;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message.orEmpty()}finally{busy=false}}}
    suspend fun command(type:String,extra:JSONObject=JSONObject()):JSONObject=r.robot.manage(extra.put("type",type),admin)
    suspend fun refresh(){info=command("device_status");info.optJSONObject("calibration")?.let{left=it.optBoolean("left_invert");right=it.optBoolean("right_invert");swap=it.optBoolean("swap")}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)job{
        require(r.robot.wifi.connected){t("请先通过 Wi-Fi 连接设备","Connect to the device over Wi-Fi first")}
        val bytes=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=3145728){"Firmware exceeds 3 MB"};out.write(buf,0,n)};out.toByteArray()}}
        r.robot.wifi.upload(bytes,"looi-s3.bin");r.robot.disconnect();message=t("升级成功，请重连并核对版本","Update accepted. Reconnect and verify the version")
    }}
    DisposableEffect(Unit){onDispose{r.robot.wifi.stopDiscovery()}}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(t("设备管理","Device management"))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(t("Wi-Fi 连接 / BLE 配网","Wi-Fi connection / BLE provisioning"),style=MaterialTheme.typography.titleMedium)
            Text(t("先连接 ESP 热点或同一局域网。通过 BLE 配网时无需 Wi-Fi 连接。","Join the ESP hotspot or the same LAN. BLE provisioning does not require a Wi-Fi connection."))
            OutlinedTextField(address,{address=it},label={Text(t("设备 IP","Device IP"))},singleLine=true)
            OutlinedTextField(admin,{admin=it},label={Text(t("设备管理密码","Device password"))},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={job{r.robot.connectWifi(address,admin);refresh();message=t("Wi-Fi 已连接","Wi-Fi connected")}},enabled=!busy){Text(t("Wi-Fi 连接","Connect Wi-Fi"))}
                OutlinedButton(onClick={r.robot.wifi.discover()},enabled=!busy){Text(t("发现设备","Discover"))}
                OutlinedButton(onClick={job{refresh()}},enabled=!busy){Text(t("读取状态","Refresh"))}
            }
            found.forEach{(name,ip)->TextButton(onClick={address=ip}){Text("$name · $ip")}}
            Text(listOf(info.optString("device_id"),info.optString("firmware_version"),info.optString("network_state"),info.optString("ip")).filter{it.isNotEmpty()}.joinToString(" · "))
            Text(t("配网","Wi-Fi setup"),style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(ssid,{ssid=it},label={Text(t("Wi-Fi 名称","Wi-Fi name"))},singleLine=true)
            OutlinedTextField(password,{password=it},label={Text(t("Wi-Fi 密码","Wi-Fi password"))},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={job{var result=command("wifi_scan");while(result.optBoolean("scanning")){delay(1000);result=command("wifi_scan")};val a=result.optJSONArray("networks");networks=(0 until (a?.length()?:0)).map{a!!.getJSONObject(it).getString("ssid")}}},enabled=!busy){Text(t("扫描 Wi-Fi","Scan Wi-Fi"))}
                Button(onClick={job{command("wifi_configure",JSONObject().put("ssid",ssid).put("password",password));password="";message=t("正在连接，稍后读取状态查看 IP","Connecting. Refresh status shortly to see the IP")}},enabled=!busy){Text(t("保存并连接","Save and connect"))}
                TextButton(onClick={job{command("wifi_forget");message=t("已忘记网络","Network forgotten")}},enabled=!busy){Text(t("忘记网络","Forget network"))}
            }
            networks.forEach{name->TextButton(onClick={ssid=name}){Text(name)}}
            HorizontalDivider();Text(t("方向校准","Motor calibration"),style=MaterialTheme.typography.titleMedium)
            Text(t("先架空车轮。点动每次最多 300 毫秒。","Lift the wheels first. Each test lasts at most 300 ms."))
            Row{Checkbox(left,{left=it});Text(t("左轮反向","Invert left motor"))};Row{Checkbox(right,{right=it});Text(t("右轮反向","Invert right motor"))};Row{Checkbox(swap,{swap=it});Text(t("交换左右轮","Swap motor sides"))}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={job{command("calibration_set",JSONObject().put("left_invert",left).put("right_invert",right).put("swap",swap));refresh()}},enabled=!busy){Text(t("保存方向","Save direction"))}
                OutlinedButton(onClick={job{command("calibration_set",JSONObject().put("reset",true));refresh()}},enabled=!busy){Text(t("恢复默认","Reset"))}
                OutlinedButton(onClick={job{command("calibration_set",JSONObject().put("shortcut","reverse_forward"));refresh()}},enabled=!busy){Text(t("前后反了","Reverse forward/back"))}
                OutlinedButton(onClick={job{command("calibration_set",JSONObject().put("shortcut","reverse_steering"));refresh()}},enabled=!busy){Text(t("左右反了","Reverse steering"))}
            }
            Button(onClick={job{command("control_acquire",JSONObject().put("takeover",true));message=t("已接管控制","Control acquired")}},enabled=!busy){Text(t("接管控制","Take control"))}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                listOf("forward" to t("前进","Forward"),"backward" to t("后退","Backward"),"left" to t("左转","Left"),"right" to t("右转","Right"),"left_forward" to "L+","left_backward" to "L−","right_forward" to "R+","right_backward" to "R−").forEach{(direction,title)->OutlinedButton(onClick={job{command("control_acquire",JSONObject().put("takeover",true));command("calibration_test",JSONObject().put("direction",direction))}},enabled=!busy){Text(title)}}
                Button(onClick={scope.launch{runCatching{command("stop")}}}){Text(t("立即停止","Stop now"))}
            }
            HorizontalDivider();Text(t("固件升级","Firmware update"),style=MaterialTheme.typography.titleMedium)
            Text(t("升级上传使用 Wi-Fi；支持本地 .bin 和在线版本。","Updates use Wi-Fi. Choose a local .bin or an online release."))
            Button(onClick={picker.launch(arrayOf("*/*"))},enabled=!busy){Text(t("选择 .bin 升级","Choose .bin"))}
            OutlinedButton(onClick={job{release=r.robot.wifi.release();message=t("在线版本：","Online version: ")+release!!.getString("version")}},enabled=!busy){Text(t("检查在线版本","Check for updates"))}
            release?.let{manifest->Button(onClick={job{r.robot.wifi.installRelease(manifest);r.robot.disconnect();message=t("升级成功，请重连核对版本","Update accepted. Reconnect and verify the version")}},enabled=!busy){Text(t("安装 ","Install ")+manifest.getString("version"))}}
            TextButton(onClick={job{command("ota_exit");message=t("已退出升级模式","Update mode exited")}},enabled=!busy){Text(t("退出升级模式","Exit update mode"))}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());Text(message)
        }
    },confirmButton={TextButton(onClick=onDismiss,enabled=!busy){Text(t("完成","Done"))}})
}
