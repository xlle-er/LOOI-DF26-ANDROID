package app.looi.android

import android.Manifest
import android.os.Build
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun DeviceAccessPrompt(r:LooiRuntime){
    val session=r.robot.management;val state by session.state.collectAsState();var password by remember{mutableStateOf("")};var name by remember{mutableStateOf("")}
    if(state.passwordPrompt||state.namePrompt)AlertDialog(onDismissRequest={session.dismissPrompt()},title={Text(if(state.passwordPrompt)t("设备管理密码","Device admin password") else t("给机器人起个名字","Name your robot"))},text={Column{
        if(state.passwordPrompt)OutlinedTextField(password,{password=it},label={Text(t("设备管理密码","Device password"))},visualTransformation=PasswordVisualTransformation())
        else OutlinedTextField(name,{name=it},label={Text(t("名字（支持中文）","Name (Chinese supported)"))})
        if(state.busy)LinearProgressIndicator();Text(state.message)
    }},confirmButton={TextButton(onClick={session.job{if(state.passwordPrompt)session.authenticate(password)else session.rename(name)}},enabled=!state.busy){Text(t("保存","Save"))}},dismissButton={TextButton(onClick={session.dismissPrompt()},enabled=!state.busy){Text(if(state.namePrompt)t("保留识别码","Keep device ID")else t("稍后","Later"))}})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun DeviceManagementDialog(r:LooiRuntime,onDismiss:()->Unit){
    val context=LocalContext.current;val session=r.robot.management;val state by session.state.collectAsState();val ready by r.robot.ready.collectAsState();val prepared by r.robot.wifi.prepared.collectAsState()
    val prefs=remember{context.getSharedPreferences("looi-updates",android.content.Context.MODE_PRIVATE)}
    var tab by remember{mutableStateOf(0)};var settings by remember{mutableStateOf(false)}
    var ssid by remember{mutableStateOf("")};var routerPassword by remember{mutableStateOf("")};var address by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var left by remember{mutableStateOf(true)};var right by remember{mutableStateOf(false)};var swap by remember{mutableStateOf(false)};var networks by remember{mutableStateOf<List<String>>(emptyList())}
    var preview by remember{mutableStateOf(session.preview)};var close by remember{mutableStateOf(session.closeHotspot)}
    fun calibrate(extra:JSONObject){session.job{val result=session.command("calibration_set",extra);val c=result.getJSONObject("calibration");left=c.getBoolean("left_invert");right=c.getBoolean("right_invert");swap=c.getBoolean("swap")}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)session.job{
        val bytes=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=3145728){"Firmware exceeds 3 MB"};out.write(buffer,0,n)};out.toByteArray()}}
        if(state.phase=="hotspot")session.stageLocal(bytes)else{r.robot.wifi.upload(bytes,"firmware.bin",closeHotspot=close);session.verifyReboot(expected=null)}
    }}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){values->if(values.values.all{it})session.job{session.joinHotspot()}else r.report(t("连接热点需要 Wi-Fi 权限","Wi-Fi permission is needed to join the hotspot"))}
    LaunchedEffect(Unit){if(ready&&!state.busy)session.job{session.inspect();state.info.optJSONObject("calibration")?.let{left=it.optBoolean("left_invert");right=it.optBoolean("right_invert");swap=it.optBoolean("swap")}}}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(12.dp),shape=MaterialTheme.shapes.extraLarge){
            Column(Modifier.padding(16.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(if(settings)t("管理设置","Management settings")else t("调试机器人","Debug robot"),style=MaterialTheme.typography.titleLarge);TextButton(onClick={if(settings)settings=false else onDismiss()}){Text(if(settings)t("返回","Back")else t("完成","Done"))}}
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    if(settings){
                        Text(state.info.optString("name","LOOI"));Text(state.info.optString("ip","192.168.4.1"))
                        OutlinedTextField(address,{address=it;session.manualAddress=it},label={Text(t("手动指定 IP（可选）","Manual IP override (optional)"))})
                        Button(onClick={session.job{if(state.info.length()==0)session.requestNetwork(address.ifBlank{"192.168.4.1"})else session.connectHTTP(address.ifBlank{state.info.optString("ip","192.168.4.1")})}},enabled=!state.busy){Text(t("连接","Connect"))}
                        if(session.identity){
                            OutlinedTextField(name,{name=it},label={Text(t("机器人名字（支持中文）","Robot name (Chinese supported)"))})
                            TextButton(onClick={session.job{session.rename(name)}},enabled=!state.busy){Text(t("修改机器人名字","Change robot name"))}
                            OutlinedTextField(password,{password=it},label={Text(t("新管理密码","New admin password"))},visualTransformation=PasswordVisualTransformation())
                            TextButton(onClick={session.job{session.changePassword(password);password=""}},enabled=!state.busy){Text(t("修改管理密码","Change admin password"))}
                        }else Text(t("升级固件后可修改名称和密码","Update firmware to change the name and password"))
                        TextButton(onClick={session.promptPassword()}){Text(t("重新输入管理密码","Re-enter admin password"))}
                        Row{Switch(preview,{preview=it;prefs.edit().putBoolean("preview",it).apply();session.state.value=session.state.value.copy(release=null,phase="idle")},enabled=!state.busy);Text(t("预览版更新","Preview firmware updates"))}
                        Row{Switch(close,{close=it;prefs.edit().putBoolean("close",it).apply()},enabled=!state.busy);Text(t("升级后关闭 ESP 热点","Close hotspot after update"))}
                    }else{
                        TabRow(selectedTabIndex=tab){listOf(t("马达测试","Motor tests"),t("配网","Wi-Fi setup"),"OTA").forEachIndexed{i,title->Tab(selected=tab==i,onClick={tab=i},text={Text(title)})}}
                        if(!ready)Text(t("请先连接机器人","Connect a robot first"))
                        when(tab){
                            0->{
                                Text(t("先架空车轮，点动最多 300 毫秒。","Lift the wheels. Each test lasts at most 300 ms."))
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    listOf("forward" to t("前进","Forward"),"backward" to t("后退","Backward"),"left" to t("左转","Left"),"right" to t("右转","Right"),"left_forward" to "L+","left_backward" to "L−","right_forward" to "R+","right_backward" to "R−").forEach{(direction,title)->OutlinedButton(onClick={session.job{session.command("control_acquire",JSONObject().put("takeover",true));session.command("calibration_test",JSONObject().put("direction",direction))}},enabled=ready&&!state.busy){Text(title)}}
                                }
                                HorizontalDivider();Text(t("电机方向","Motor calibration"))
                                Row{Checkbox(left,{left=it});Text(t("左轮反向","Invert left"))};Row{Checkbox(right,{right=it});Text(t("右轮反向","Invert right"))};Row{Checkbox(swap,{swap=it});Text(t("交换左右轮","Swap motor sides"))}
                                Button(onClick={calibrate(JSONObject().put("left_invert",left).put("right_invert",right).put("swap",swap))},enabled=ready&&!state.busy){Text(t("保存方向","Save direction"))}
                                FlowRow{TextButton(onClick={calibrate(JSONObject().put("shortcut","reverse_forward"))},enabled=ready&&!state.busy){Text(t("前后反了","Reverse forward/back"))};TextButton(onClick={calibrate(JSONObject().put("shortcut","reverse_steering"))},enabled=ready&&!state.busy){Text(t("左右反了","Reverse steering"))};TextButton(onClick={calibrate(JSONObject().put("reset",true))},enabled=ready&&!state.busy){Text(t("恢复默认","Reset"))}}
                            }
                            1->{
                                OutlinedTextField(ssid,{ssid=it},label={Text(t("Wi-Fi 名称","Wi-Fi name"))});OutlinedTextField(routerPassword,{routerPassword=it},label={Text(t("Wi-Fi 密码","Wi-Fi password"))},visualTransformation=PasswordVisualTransformation())
                                OutlinedButton(onClick={session.job{var result=session.command("wifi_scan");withTimeout(30000){while(result.optBoolean("scanning")){delay(1000);result=session.command("wifi_scan")}};val a=result.optJSONArray("networks");networks=(0 until (a?.length()?:0)).map{a!!.getJSONObject(it).getString("ssid")}}},enabled=ready&&!state.busy){Text(t("扫描 Wi-Fi","Scan Wi-Fi"))}
                                networks.forEach{n->TextButton(onClick={ssid=n}){Text(n)}}
                                Button(onClick={session.job{session.command("wifi_configure",JSONObject().put("ssid",ssid).put("password",routerPassword));routerPassword=""}},enabled=ready&&!state.busy){Text(t("保存并连接","Save and connect"))}
                                TextButton(onClick={session.job{session.command("wifi_forget")}},enabled=ready&&!state.busy){Text(t("忘记网络","Forget network"))}
                            }
                            2->{
                                Text(state.info.optString("firmware_version","—")+(state.release?.let{" → "+it.optString("version")}?:""))
                                when(state.phase){
                                    "idle","complete"->Button(onClick={session.job{session.enter()}},enabled=ready&&!state.busy){Text(t("进入升级模式","Enter update mode"))}
                                    "fallback"->Button(onClick={session.job{session.hotspot()}},enabled=!state.busy){Text(t("改用热点升级","Update via hotspot"))}
                                    "hotspot"->{if(session.localFirmware!=null||session.compatiblePackage(prepared)){
                                        Text(t("固件已下载","Firmware downloaded")+" · "+(prepared?.optString("version")?:"bin"))
                                        Button(onClick={permissions.launch(if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},enabled=!state.busy){Text(t("连接热点","Connect hotspot"))}
                                    }else Button(onClick={session.job{session.download()}},enabled=!state.busy){Text(t("下载固件","Download firmware"))}
                                        TextButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!state.busy){Text(t("选择本地 .bin","Choose local .bin"))}
                                    }
                                    "online","connected"->{if(state.phase=="online"||session.localFirmware!=null||session.compatiblePackage(prepared))Button(onClick={session.job{session.install()}},enabled=!state.busy){Text(t("安装 OTA 更新","Install OTA update"))}else Button(onClick={session.job{session.download()}},enabled=!state.busy){Text(t("下载固件","Download firmware"))};if(r.robot.wifi.connected)OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!state.busy){Text(t("上传 bin 更新包","Upload bin package"))}}
                                    "rebooting"->TextButton(onClick={session.job{session.verifyReboot()}},enabled=!state.busy){Text(t("核对升级版本","Verify installed version"))}
                                }
                                if(state.phase!="idle"&&state.phase!="complete")TextButton(onClick={session.job{session.exit()}},enabled=!state.busy){Text(t("退出升级模式","Exit update mode"))}
                            }
                        }
                        TextButton(onClick={settings=true}){Text(t("管理设置","Management settings"))}
                    }
                    if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth());Text(state.message)
                }
                Button(onClick={r.scope.launch{runCatching{r.robot.send(JSONObject().put("type","stop"))}}},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(t("立即停止","Stop now"))}
            }
        }
    }
}
