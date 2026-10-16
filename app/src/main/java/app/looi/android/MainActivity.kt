@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.looi.android

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream

class LooiViewModel(application: android.app.Application): androidx.lifecycle.AndroidViewModel(application) { val runtime=(application as LooiApplication).runtime }
class MainActivity: ComponentActivity() {
    private var authorizationPending=false
    private val authorization=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        authorizationPending=false
        if(result.resultCode == RESULT_OK && result.data != null) {
            runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data) }.onSuccess(::authorized).onFailure { authFailure() }
        } else authFailure()
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); runtime().camera.setRotation(windowManager.defaultDisplay.rotation) }
    private fun runtime()=(application as LooiApplication).runtime
    private fun authorized(result: AuthorizationResult) {
        val account=result.toGoogleSignInAccount()?.account?.name
        if(result.accessToken.isNullOrBlank() || GoogleAuth.CLOUD_SCOPE !in result.grantedScopes || account.isNullOrBlank()) { authFailure(); return }
        runtime().authorized(account)
    }
    private fun authFailure() { runtime().report(t("Google 授权未完成，请检查 Android OAuth 注册、签名与 Play 服务", "Google authorization incomplete; check Android OAuth registration, signing and Play services")) }
    fun authorizeGoogle() {
        if(authorizationPending) return
        authorizationPending=true
        Identity.getAuthorizationClient(this).authorize(GoogleAuth.request()).addOnSuccessListener { result ->
            if(result.hasResolution()) result.pendingIntent?.let { authorization.launch(IntentSenderRequest.Builder(it).build()) }
            else { authorizationPending=false; authorized(result) }
        }.addOnFailureListener { authorizationPending=false; authFailure() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { val model: LooiViewModel=androidx.lifecycle.viewmodel.compose.viewModel(); LooiTheme { LooiApp(model.runtime,this) } }
        runtime().camera.setRotation(windowManager.defaultDisplay.rotation)
    }
}
@Composable fun LooiTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF8DE8CB),onPrimary=Color(0xFF00382B),secondary=Color(0xFFB7C9F4),background=Color(0xFF101716),surface=Color(0xFF182220),surfaceVariant=Color(0xFF263632),onSurface=Color(0xFFE3EEE8),onSurfaceVariant=Color(0xFFBED0C5)),content=content)
}
@Composable private fun LooiApp(r: LooiRuntime, activity: MainActivity) {
    val s by r.state.collectAsState()
    val remote by r.remote.state.collectAsState()
    val robotReady by r.robot.ready.collectAsState()
    var permissionToken by rememberSaveable { mutableStateOf<Long?>(null) }
    var permissionPurpose by rememberSaveable { mutableStateOf("") }
    var showRobots by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when(permissionPurpose) {
            "start" -> permissionToken?.let(r::startGranted)
            "camera" -> r.cameraGranted()
            "scan" -> if(showRobots) r.robot.scan()
        }
        permissionToken=null; permissionPurpose=""
    }
    fun begin(test: Boolean) {
        val token=r.prepareStart(test) ?: return
        val needed=r.requiredPermissions(test)
        if(needed.isEmpty()) r.startGranted(token) else { permissionToken=token; permissionPurpose="start"; permissions.launch(needed) }
    }
    fun scan() {
        permissionPurpose="scan"
        permissions.launch(if(Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }
    val config=LocalConfiguration.current
    val companion=s.phase == Phase.ACTIVE
    val landscape=companion || s.phase == Phase.STARTING
    LaunchedEffect(companion,landscape,config.screenWidthDp,remote.presented) {
        activity.requestedOrientation=if(config.smallestScreenWidthDp >= 600 || activity.isInMultiWindowMode) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else if(landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        if(companion || remote.presented) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    BackHandler(s.phase != Phase.IDLE || remote.presented) { if(remote.presented) r.remote.close() else r.cancel() }
    key(s.language) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            // The local face can be recreated independently of media and Live; hidden in settings.
            key(companion) { AndroidView(factory={ r.face.create() }, modifier=Modifier.fillMaxSize(), onRelease={ r.face.release(it) },update={ it.post { it.evaluateJavascript("window.looiFace?.resize()",null) }; it.visibility=if(companion) android.view.View.VISIBLE else android.view.View.INVISIBLE; it.importantForAccessibility=if(companion) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }) }
            if(companion) CompanionScreen(r,s) {
                permissionPurpose="camera"; permissions.launch(arrayOf(Manifest.permission.CAMERA))
            } else SettingsScreen(r,s,{showRobots=true},{showConfig=true},{begin(false)},{begin(true)})
            if(s.message.isNotBlank()) {
                Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).widthIn(max=640.dp),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(start=16.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(s.message,Modifier.weight(1f).semantics { liveRegion=LiveRegionMode.Polite },style=MaterialTheme.typography.bodyMedium)
                        TextButton(onClick=r::clearMessage) { Text(t("关闭","Dismiss")) }
                    }
                }
            }
        }
    }
    if(remote.presented) RemoteDialog(r.remote,robotReady)
    if(showConfig) ConfigDialog(r,s,{activity.authorizeGoogle()}) { showConfig=false }
    if(showRobots) RobotDialog(r,s,::scan) { r.robot.stopScan(); showRobots=false }
}
}
@Composable private fun SettingsScreen(r: LooiRuntime,s: AppState,robots: ()->Unit,configure: ()->Unit,start: ()->Unit,test: ()->Unit) {
    val ready by r.robot.ready.collectAsState()
    val remote by r.remote.state.collectAsState()
    val idle=s.phase == Phase.IDLE && !remote.presented && remote.phase != RemotePhase.STOPPING
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max=720.dp).align(Alignment.TopCenter).padding(bottom=104.dp).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("LOOI",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            Text(t("陪伴设置","Companion settings"),style=MaterialTheme.typography.headlineMedium)
            Text(t("把手机放在机器人上，让陪伴开始。","Place your phone on your robot and make yourself at home."),color=MaterialTheme.colorScheme.onSurfaceVariant)
            SettingCard(t("连接与对话","Connection & conversation")) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=robots,enabled=idle,modifier=Modifier.weight(1f)) { Text(t("我的机器人","My robots")) }
                    OutlinedButton(onClick=configure,enabled=idle,modifier=Modifier.weight(1f)) { Text(t("AI 服务","AI services")) }
                }
                Text((if(ready) t("机器人已连接","Robot connected") else t("不连接机器人也能陪伴","Companionship works without a robot"))+" · "+s.config.provider,style=MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick=r::openRemote,enabled=idle && ready) {Text(t("遥控器","Remote control"))}
                Toggle(t("实时语音","Live voice"),s.liveMode,idle && s.config.provider in listOf("gemini","vertex"),r::setLive)
                Toggle(t("开启相机","Camera"),s.cameraWanted,idle,r::cameraWanted)
                OutlinedButton(onClick=r::flipCamera,enabled=idle) {Text(if(s.frontCamera) t("当前前置镜头 · 切换","Front lens · switch") else t("当前后置镜头 · 切换","Rear lens · switch"))}
                Text(t("启动时申请相机与麦克风；打开机器人列表后才扫描附近设备。","Camera and microphone are requested when starting; nearby devices are scanned from the robot list."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingCard(t("独立测试","Test each component")) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=r::soundTest,enabled=idle) { Text(t("声音测试","Sound test")) }
                    OutlinedButton(onClick=test,enabled=idle && s.config.provider in listOf("gemini","vertex")) { Text(t("AI 语音测试","AI voice test")) }
                    OutlinedButton(onClick={r.actionTest()},enabled=idle && ready) { Text(t("动作测试","Action test")) }
                }
                Text(t("声音测试请听辨；AI 测试需完整接收并播放音频后才通过。","Listen to verify the sound test. The AI test passes only after audio is received and fully played."),style=MaterialTheme.typography.bodySmall)
            }
            SettingCard(t("语言 / Language","Language / 语言")) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("system" to t("跟随系统","System"),"zh" to "简体中文","en" to "English").forEach { (value,label) ->
                        FilterChip(selected=s.language == value,onClick={r.setLanguage(value)},label={Text(label)})
                    }
                }
                Text(t("AI 优先跟随当前对话的语言。","AI replies primarily in the language of your current conversation."),style=MaterialTheme.typography.bodySmall)
            }
            Text(t("本地视觉与前进跟随可在陪伴页开启。实验性相对跟随不提供测距或避障。","Enable local vision and forward following in the companion controls. Experimental relative following does not provide distance sensing or obstacle avoidance."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                if(!idle) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(t("正在准备或测试…","Preparing or testing…"),Modifier.padding(8.dp)); OutlinedButton(onClick=r::cancel) { Text(t("取消","Cancel")) } }
                else Button(onClick=start,enabled=s.loaded,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(t("开始陪伴","Start companionship")) }
            }
        }
    }
}
@Composable private fun SettingCard(title: String,content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() }); content()
        }
    }
}
@Composable private fun Toggle(label: String, checked: Boolean, enabled: Boolean=true,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f)); Switch(checked=checked,onCheckedChange=onChange,enabled=enabled,modifier=Modifier.semantics { contentDescription=label })
    }
}
@Composable private fun ConfigDialog(r: LooiRuntime,s: AppState,authorize: ()->Unit,dismiss: ()->Unit) {
    var draft by remember(s.config) { mutableStateOf(s.config) }
    var key by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=dismiss,title={Text(t("AI 服务设置","AI service settings"))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ProviderConfig.providers.forEach { provider -> FilterChip(selected=draft.provider == provider,onClick={key="";r.selectProvider(provider)},label={Text(if(provider == "offline") t("离线陪伴","Offline") else provider)}) }
            }
            if(draft.provider != "offline") {
                Field(t("文字模型","Text model"),draft.model) {draft=draft.copy(model=it)}
                if(draft.provider in listOf("gemini","vertex")) Field(t("Live 模型","Live model"),draft.liveModel) {draft=draft.copy(liveModel=it)}
                if(draft.provider !in listOf("gemini","vertex")) {
                    Field(t("服务地址","Base URL"),draft.baseUrl) {draft=draft.copy(baseUrl=it)}
                    Toggle(t("此模型支持图片输入","This model supports images"),draft.vision) {draft=draft.copy(vision=it)}
                }
                if(draft.provider == "vertex") {
                    Toggle("Vertex Express",draft.express) {draft=draft.copy(express=it,bearer=!it,googleOAuth=false)}
                    if(!draft.express) {
                        Field(t("项目 ID","Project ID"),draft.project) {draft=draft.copy(project=it)}
                        Field(t("区域","Region"),draft.region) {draft=draft.copy(region=it)}
                        Toggle(t("使用 Google 账号授权","Authorize with a Google account"),draft.googleOAuth) {draft=draft.copy(googleOAuth=it,bearer=true)}
                        if(draft.googleOAuth) OutlinedButton(onClick={r.saveConfig(draft.copy(bearer=true),"");authorize()}) {Text(t("授权 Google Cloud","Authorize Google Cloud"))}
                        else Text(t("输入有效 OAuth 访问令牌。","Enter a valid OAuth access token."),style=MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(key,{key=it},label={Text(if(draft.bearer) t("访问令牌（留空保留）","Access token (blank keeps saved)") else t("API Key（留空保留）","API key (blank keeps saved)"))},visualTransformation=PasswordVisualTransformation(),singleLine=true)
                TextButton(onClick={r.deleteCredential();key=""}) {Text(t("删除已保存凭据","Delete saved credential"))}
                if(draft.provider == "ollama") Text(t("默认通过 USB 转发访问本机。局域网明文地址需加入构建白名单，详见 README。","Default uses USB forwarding. LAN HTTP hosts require the build allowlist; see README."),style=MaterialTheme.typography.bodySmall)
            }
        }
    },confirmButton={TextButton(onClick={r.saveConfig(draft,key);key="";dismiss()}) {Text(t("保存","Save"))}},dismissButton={TextButton(onClick=dismiss) {Text(t("关闭","Close"))}})
}
@Composable private fun Field(label: String,value: String,onChange: (String)->Unit) { OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true) }
@Composable private fun RobotDialog(r: LooiRuntime,s: AppState,scan: ()->Unit,dismiss: ()->Unit) {
    val rows by r.robot.robots.collectAsState(); val scanning by r.robot.scanning.collectAsState(); val status by r.robot.status.collectAsState(); val ready by r.robot.ready.collectAsState()
    var managing by remember { mutableStateOf(false) }
    if(managing) DeviceManagementDialog(r) { managing=false }
    var edited by remember { mutableStateOf<RobotRow?>(null) }; var alias by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=dismiss,title={Text(t("我的机器人","My robots"))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(t("明确选择一台机器人。连接成功后请核对设备信息。","Choose a robot explicitly. Check the device information after connecting."))
            Text(when(status) {"connecting" -> t("正在连接","Connecting"); "ready" -> t("已就绪","Ready"); else -> t("未连接","Disconnected") })
            if(scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            rows.forEach { row ->
                LaunchedEffect(row.address) { val saved=r.settings.alias(row.address); if(saved.isNotBlank()) r.robot.robots.update { list -> list.map { if(it.address == row.address) it.copy(alias=saved) else it } } }
                HorizontalDivider()
                Text((row.alias.ifBlank {row.name})+" · …"+row.address.takeLast(5),fontWeight=FontWeight.Bold)
                Text("${row.rssi} dBm"+if(row.address == s.myRobot) " · "+t("我的机器人","My robot") else if(row.address == s.lastRobot) " · "+t("上次连接","Last connected") else "")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={r.connect(row)}) {Text(if(ready && r.robot.address == row.address) t("重新连接","Reconnect") else t("连接","Connect"))}
                    TextButton(onClick={r.own(row)}) {Text(t("设为我的","Mark as mine"))}
                    TextButton(onClick={edited=row;alias=row.alias}) {Text(t("别名","Alias"))}
                }
            }
            TextButton(onClick={ managing=true }) { Text(t("Wi-Fi / 配网 / 校准 / 升级", "Wi-Fi / Setup / Calibration / Update")) }
            if(rows.isEmpty()) Text(t("尚未发现设备","No devices found yet"))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=scan,enabled=!scanning) {Text(t("扫描 15 秒","Scan for 15s"))}
                TextButton(onClick={r.cancelConnect()}) {Text(t("取消连接 / 断开","Cancel / disconnect"))}
            }
        }
    },confirmButton={TextButton(onClick=dismiss) {Text(t("完成","Done"))}})
    edited?.let { row -> AlertDialog(onDismissRequest={edited=null},title={Text(t("本地别名","Local alias"))},text={Field(t("名称","Name"),alias) {alias=it}},confirmButton={TextButton(onClick={r.alias(row,alias);edited=null}) {Text(t("保存","Save"))}}) }
}
@Composable private fun CompanionScreen(r: LooiRuntime,s: AppState,requestCamera: ()->Unit) {
    val cameraRunning by r.camera.running.collectAsState()
    val detections by r.camera.detections.collectAsState()
    val aspect by r.camera.frameAspect.collectAsState()
    val sound by r.audio.status.collectAsState()
    val transcript by r.live.transcript.collectAsState()
    val liveConnected by r.live.connected.collectAsState()
    val context=LocalContext.current
    val accessibility=context.getSystemService(AccessibilityManager::class.java)
    var controls by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<ByteArray?>(null) }
    val scope=rememberCoroutineScope()
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri != null) scope.launch {
            attachment=withContext(Dispatchers.IO) { runCatching {
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
                val sample=(maxOf(options.outWidth,options.outHeight)/1024).coerceAtLeast(1)
                val image=context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) } ?: error("image")
                ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,it); image.recycle() }.toByteArray().also { check(it.size <= 2000000) }
            }.getOrNull() }
            if(attachment == null) r.report(t("图片无法读取或过大","Could not read the image, or it is too large"))
        }
    }
    DisposableEffect(r.face) { r.face.onTap={controls=true}; onDispose {r.face.onTap={}} }
    LaunchedEffect(controls,s.liveMode,text,accessibility.isTouchExplorationEnabled) {
        if(controls && s.liveMode && text.isEmpty() && !accessibility.isEnabled) { delay(5000); controls=false }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if(cameraRunning) {
            Box(Modifier.align(Alignment.TopStart).padding(12.dp).width(176.dp).aspectRatio(4f/3f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                var previewView by remember { mutableStateOf<PreviewView?>(null) }
                AndroidView(factory={PreviewView(it).also { v -> previewView=v;r.camera.attach(v); v.setOnTouchListener { view,event -> if(event.action == android.view.MotionEvent.ACTION_UP) {controls=true;view.performClick()};false } }},modifier=Modifier.fillMaxSize(),onRelease={r.camera.detach(it)})
                Canvas(Modifier.fillMaxSize()) {
                    val imageW=if(size.width/size.height > aspect) size.height*aspect else size.width
                    val imageH=imageW/aspect; val dx=(size.width-imageW)/2; val dy=(size.height-imageH)/2
                    detections.forEach { d ->
                        val x=if(r.camera.front) 1-d.box.x-d.box.w else d.box.x
                        drawRect(if(r.follow.target?.id == d.id || s.selected == d.id) Color.Yellow else Color(0xFF8DE8CB),Offset(dx+x*imageW,dy+d.box.y*imageH),Size(d.box.w*imageW,d.box.h*imageH),style=Stroke(2.dp.toPx()))
                    }
                }
                TextButton(onClick={controls=true},modifier=Modifier.align(Alignment.BottomEnd)) {Text(t("控制","Controls"),color=Color.White)}
            }
        }
        if(!controls) {
            TextButton(onClick={controls=true},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp)) {Text(t("显示控制","Show controls"))}
        }
        if(controls) {
            Surface(Modifier.align(Alignment.CenterEnd).padding(12.dp).widthIn(max=380.dp).fillMaxHeight(.9f),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(t("陪伴控制","Companion controls"),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick={controls=false}) {Text(t("收起","Hide"))}
                        TextButton(onClick=r::cancel) {Text(t("退出","Exit"))}
                    }
                    Text(if(liveConnected) t("Live 已连接","Live connected") else if(s.config.provider == "offline") t("离线陪伴","Offline companion") else t("文字模式或连接待恢复","Text mode or connection needs recovery"),style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick=r::openRemote,enabled=r.robot.ready.collectAsState().value) {Text(t("遥控器","Remote control"))}
                    Toggle(t("相机","Camera"),s.cameraWanted) { value -> r.cameraWanted(value); if(value && !cameraRunning) requestCamera() }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick=r::flipCamera,enabled=cameraRunning) {Text(t("切换镜头","Switch lens"))}
                        OutlinedButton(onClick=r::soundTest) {Text(t("测试声音","Test sound"))}
                        OutlinedButton(onClick=r::recover) {Text(t("恢复采集","Restore capture"))}
                    }
                    Toggle(t("本地目标识别","Local object detection"),s.detecting,cameraRunning,r::detect)
                    if(s.detecting) {
                        Text(t("选择具体目标","Select a target"),style=MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            detections.forEachIndexed { index,d ->
                                FilterChip(selected=r.follow.target?.let { it.label == d.label && it.box.iou(d.box) > .5f } == true,onClick={r.selectTarget(d)},label={Text("${objectName(d.label)} ${index+1}")})
                            }
                        }
                        Text(t("实验性相对跟随：不提供实际距离或避障。","Experimental relative following: no measured distance or obstacle avoidance."),style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick=r::calibrateFollow,enabled=s.selected != null) {Text(if(s.followReference) t("参考已设定，重新设定","Reference set · reset") else t("以当前位置为参考","Use current position as reference"))}
                        Toggle(t("允许本次身体运动","Allow motion for this session"),s.motion,onChange=r::arm)
                        Button(onClick=r::startFollow,enabled=s.followReference && s.motion && !s.following) {Text(t("开始前进跟随","Start forward following"))}
                        if(s.following) Text(t("正在跟随","Following"),modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    } else Toggle(t("允许本次身体运动","Allow motion for this session"),s.motion,onChange=r::arm)
                    OutlinedButton(onClick={r.scope.launch {r.stopMotion()}},modifier=Modifier.fillMaxWidth()) {Text(t("停止身体运动","Stop body motion"))}
                    Text(t("缓冲","Buffer")+": ${sound.pendingMs} ms · "+t("录音","Mic")+": ${sound.recording} · "+t("视频帧","Video frames")+": ${r.live.videoCount}",style=MaterialTheme.typography.bodySmall)
                    Text(t("输出","Output")+": ${sound.route}",style=MaterialTheme.typography.bodySmall)
                    if(sound.interrupted) Text(t("系统音频中断","System audio interruption"),color=MaterialTheme.colorScheme.error)
                    Button(onClick=r::cancel,modifier=Modifier.fillMaxWidth()) {Text(t("退出陪伴","Exit companionship"))}
                }
            }
        }
        if(!s.liveMode || text.isNotEmpty() || controls) {
            Surface(Modifier.align(Alignment.BottomStart).padding(12.dp).widthIn(max=420.dp).fillMaxWidth(if(controls) .45f else .85f).imePadding(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.95f)) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    val response=if(s.reply.isNotBlank()) s.reply else transcript
                    if(response.isNotBlank()) Text(response.takeLast(400),style=MaterialTheme.typography.bodySmall,modifier=Modifier.heightIn(max=80.dp).verticalScroll(rememberScrollState()))
                    OutlinedTextField(text,{text=it},label={Text(t("说点什么","Say something"))},modifier=Modifier.fillMaxWidth(),maxLines=3)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={photoPicker.launch("image/*")},enabled=!s.liveMode) {Text(if(attachment == null) t("添加图片","Add image") else t("更换图片","Change image"))}
                        if(attachment != null) TextButton(onClick={attachment=null}) {Text(t("移除图片","Remove image"))}
                        Button(onClick={r.text(text,attachment);text="";attachment=null},enabled=text.isNotBlank() && !s.busy) {Text(t("发送","Send"))}
                    }
                }
            }
        }
    }
}
