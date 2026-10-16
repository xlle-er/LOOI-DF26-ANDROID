package app.looi.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

 data class AppState(
    val phase: Phase=Phase.IDLE, val config: ProviderConfig=ProviderConfig(), val language: String="system",
    val message: String="", val liveMode: Boolean=true, val cameraWanted: Boolean=true, val detecting: Boolean=false,
    val frontCamera: Boolean=true, val motion: Boolean=false, val following: Boolean=false, val followReference: Boolean=false, val selected: Long?=null,
    val reply: String="", val busy: Boolean=false, val loaded: Boolean=false, val myRobot: String="", val lastRobot: String=""
)
class LooiApplication: Application(), DefaultLifecycleObserver {
    lateinit var runtime: LooiRuntime; private set
    override fun onCreate() {
        super<Application>.onCreate(); runtime=LooiRuntime(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
    override fun onStop(owner: LifecycleOwner) { runtime.background() }
}
class LooiRuntime(private val context: Context) {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val state=MutableStateFlow(AppState())
    val gate=SessionGate()
    val settings=SettingsStore(context)
    private val credentials=CredentialStore(context)
    val audio=AudioEngine(context,scope,::report)
    val robot=RobotClient(context,scope,::report)
    val motion=MotionArbiter(robot)
    val camera=CameraEngine(context,scope,::report)
    val face=FaceHost(context,scope,::report)
    val live=LiveClient(context,scope,audio,::report)
    val follow=FollowPolicy()
    private val http=OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
    private val scenarios: Set<String> = JSONArray(context.assets.open("scenarios.json").bufferedReader().use { it.readText() }).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    private var work: Job? = null
    private var requestJob: Job? = null
    private var actionJob: Job? = null
    private var followJob: Job? = null
    private var idleJob: Job? = null
    private var activeLease: Long? = null
    private var activeSource=MotionSource.SCENARIO
    private var actionEpoch=0L
    private val scenarioLock=Mutex()
    private var lastActivity=SystemClock.elapsedRealtime()
    private var permissionsToken: Long? = null
    private var testOnly=false
    init {
        scope.launch {
            settings.migrateStoredScenes()
            L10n.language=settings.language()
            val config=settings.load(settings.selected())
            val front=settings.option("frontCamera",true); if(camera.front != front) camera.flip()
            val cameraWanted=settings.option("cameraWanted",true)
            state.update { it.copy(config=config,language=L10n.language,loaded=true,frontCamera=front,cameraWanted=cameraWanted,myRobot=settings.own(),lastRobot=settings.last(),liveMode=config.provider in listOf("gemini","vertex")) }
        }
        robot.onDisconnect={ motion.revoke(); stopFollowLocal(); state.update { it.copy(motion=false) }; face.publish(faceState()) }
        camera.onDetectorUnavailable={ state.update {it.copy(detecting=false)} }
        camera.onInvalidated={ invalidateFollow() }
        camera.onFrame={ if(state.value.phase == Phase.ACTIVE && state.value.liveMode) live.frame(it) }
        camera.onDetections={ values ->
            if(follow.target != null) {
                if(!follow.update(values,SystemClock.elapsedRealtime())) invalidateFollow()
                state.update { it.copy(selected=follow.target?.id,followReference=follow.reference != null) }
            }
        }
        face.photo={ camera.snapshot() }
        face.command={ command ->
            if(command.optString("type") == "stop") { check(robot.ready.value) { t("停止未获确认：机器人未连接", "Stop unconfirmed: robot disconnected") }; motion.stop(); JSONObject().put("type","ack") }
            else {
                check(gate.phase == Phase.ACTIVE || activeSource == MotionSource.TEST)
                val lease=activeLease ?: throw LooiException("动作许可已失效", "Motion lease expired")
                motion.send(command,activeSource,lease)
            }
        }
        live.onScenario={ runScenario(it,MotionSource.SCENARIO) }
        live.onCancel={ cancelAction(); scope.launch { stopMotion() } }
        live.onStop={ scope.launch { stopMotion() } }
        live.onFailure={
            if(gate.phase == Phase.ACTIVE || gate.phase == Phase.STARTING) {
                scope.launch { audio.stop(); invalidateFollow(); runCatching { motion.stop(true) }; state.update { it.copy(motion=false) } }
            }
        }
        scope.launch {
            while(isActive) {
                delay(50)
                if(follow.enabled && follow.target?.let { SystemClock.elapsedRealtime()-it.timeMs > 300 } == true) invalidateFollow()
            }
        }
        scope.launch {
            audio.status.distinctUntilChangedBy { it.playing }.collect { face.publish(faceState()) }
        }
    }
    fun report(message: String) { state.update { it.copy(message=message) } }
    fun clearMessage() { state.update { it.copy(message="") } }
    private fun faceState() = JSONObject().put("connected",robot.ready.value && motion.allowed).put("cameraRunning",camera.running.value)
        .put("speaking",audio.status.value.playing).put("thinking",state.value.busy).put("expression",if(state.value.busy) "curious" else "neutral")
    fun setLanguage(value: String) { scope.launch { settings.setLanguage(value); state.update { it.copy(language=value,message="") } } }
    fun selectProvider(value: String) { scope.launch { val config=settings.load(value); settings.save(config); state.update { it.copy(config=config,liveMode=value in listOf("gemini","vertex")) } } }
    fun saveConfig(config: ProviderConfig, key: String) { scope.launch {
        try {
            val defaults=ProviderConfig.defaults(config.provider)
            val effective=config.copy(model=config.model.ifBlank { defaults.model },liveModel=config.liveModel.ifBlank { defaults.liveModel })
            withContext(Dispatchers.IO) { credentials.set(config.provider,key) }; settings.save(effective)
            state.update { it.copy(config=effective) }; report(t("配置已保存", "Settings saved"))
        } catch(_: Exception) { report(t("无法保存凭据或配置", "Could not save settings or credential")) }
    } }
    private suspend fun credential(config: ProviderConfig): String {
        if(config.provider == "vertex" && config.googleOAuth) {
            val account=withContext(Dispatchers.IO) { credentials.get("google.account") }
            check(account.isNotBlank()) { t("请先完成 Google 授权", "Authorize with Google first") }
            return GoogleAuth.token(context,account)
        }
        return withContext(Dispatchers.IO) { credentials.get(config.provider) }
    }
    fun authorized(account: String) {
        scope.launch {
            withContext(Dispatchers.IO) { credentials.set("google.account",account) }
            report(t("Google Cloud 授权成功", "Google Cloud authorized"))
        }
    }
    fun deleteCredential() { scope.launch { withContext(Dispatchers.IO) { credentials.remove(state.value.config.provider); if(state.value.config.provider == "vertex") credentials.remove("google.account") }; report(t("凭据已删除", "Credential deleted")) } }
    fun setLive(value: Boolean) { if(gate.phase == Phase.IDLE) state.update { it.copy(liveMode=value) } }
    fun prepareStart(test: Boolean=false): Long? {
        val token=gate.begin(test) ?: return null
        testOnly=test; permissionsToken=token
        state.update { it.copy(phase=gate.phase,message="",reply="") }; return token
    }
    fun requiredPermissions(test: Boolean): Array<String> {
        val list=mutableListOf<String>()
        if(!test && state.value.liveMode && state.value.config.provider != "offline") list += Manifest.permission.RECORD_AUDIO
        if(!test && state.value.cameraWanted) list += Manifest.permission.CAMERA
        return list.filter { ContextCompat.checkSelfPermission(context,it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }
    fun startGranted(token: Long) {
        if(!gate.current(token) || permissionsToken != token) return
        work=scope.launch {
            try {
                val s=state.value
                if(!testOnly && s.liveMode && s.config.provider != "offline") check(has(Manifest.permission.RECORD_AUDIO)) { t("语音陪伴需要麦克风权限", "Voice companionship requires microphone permission") }
                if(s.config.provider != "offline" && (s.liveMode || testOnly)) {
                    audio.startOutput()
                    val key=credential(s.config)
                    live.start(s.config,key,testOnly)
                    if(!gate.current(token)) return@launch
                    if(testOnly) {
                        val failureBaseline=audio.failures.get(); val playedBaseline=audio.renderedFrames.get()
                        live.text(if(L10n.chinese) "请开始语音测试" else "Please start the voice test")
                        withTimeout(25000) {
                            while(!(live.receivedAudio > 0 && live.turnComplete && audio.pendingMs() == 0L && audio.renderedFrames.get() > playedBaseline)) {
                                check(audio.failures.get() == failureBaseline && live.connected.value) { t("AI 测试播放或连接失败", "AI test playback or connection failed") }; delay(50)
                            }
                            check(audio.failures.get() == failureBaseline)
                        }
                        report(t("AI 语音测试成功：已收到并播放完成", "AI voice test passed: audio received and fully played"))
                        stop(preserveMessage=true); return@launch
                    }
                    audio.startInput(live::pcm)
                } else if(testOnly) {
                    report(t("离线模式请使用声音测试", "Use Sound test in offline mode")); stop(true); return@launch
                }
                if(!gate.current(token)) return@launch
                gate.active(token); state.update { it.copy(phase=Phase.ACTIVE) }
                if(s.cameraWanted && has(Manifest.permission.CAMERA)) camera.start()
                else if(s.cameraWanted) report(t("相机未授权，已降级为语音或文字陪伴", "Camera denied; voice or text companionship is available"))
                startIdle(token)
            } catch(e: CancellationException) { if(e is TimeoutCancellationException && gate.current(token)) { report(t("连接或测试超时", "Connection or test timed out")); stop(true) } }
            catch(e: Exception) { if(gate.current(token)) { report(e.message ?: t("启动失败", "Could not start")); stop(true) } }
        }
    }
    private fun has(permission: String) = ContextCompat.checkSelfPermission(context,permission) == PackageManager.PERMISSION_GRANTED
    fun cancel() { scope.launch { stop() } }
    suspend fun stop(preserveMessage: Boolean=false) {
        if(gate.phase == Phase.STOPPING) return
        gate.stop(); permissionsToken=null
        val self=currentCoroutineContext()[Job]
        if(work !== self) work?.cancel(); work=null
        requestJob?.cancel(); requestJob=null; idleJob?.cancel(); idleJob=null
        cancelAction(); stopFollowLocal(); camera.stop(); live.close(); audio.stop()
        val stopped=runCatching { motion.stop(true) }; motion.revoke()
        gate.idle(); state.update { it.copy(phase=Phase.IDLE,motion=false,following=false,busy=false,selected=null,followReference=false,message=if(stopped.isFailure) t("停止未获确认，请检查机器人", "Stop was not confirmed; check the robot") else if(preserveMessage) it.message else "") }
    }
    fun background() {
        scope.launch { if(gate.phase != Phase.IDLE) stop(); else { runCatching { motion.stop(true) }; camera.stop(); audio.stop() }; robot.stopScan(); robot.disconnect() }
    }
    fun cameraWanted(value: Boolean) {
        state.update { it.copy(cameraWanted=value) }; scope.launch {settings.saveOption("cameraWanted",value)}
        if(gate.phase == Phase.ACTIVE) { if(value && has(Manifest.permission.CAMERA)) camera.start() else camera.stop() }
        face.publish(faceState())
    }
    fun cameraGranted() { if(gate.phase == Phase.ACTIVE && state.value.cameraWanted && has(Manifest.permission.CAMERA)) camera.start() }
    fun flipCamera() { invalidateFollow(); camera.flip(); state.update {it.copy(frontCamera=camera.front)}; scope.launch {settings.saveOption("frontCamera",camera.front)} }
    fun detect(value: Boolean) {
        camera.detectEnabled=value; state.update { it.copy(detecting=value) }
        if(!value) invalidateFollow()
    }
    fun selectTarget(value: Detection) { invalidateFollow(); follow.select(value); state.update { it.copy(selected=value.id,followReference=false) } }
    fun calibrateFollow() {
        val ok=follow.calibrate(SystemClock.elapsedRealtime()); state.update { it.copy(followReference=ok) }
        if(!ok) report(t("目标不稳定，请重新选择", "Target is not stable; select it again"))
    }
    fun arm(value: Boolean) {
        if(value && !robot.ready.value) { report(t("请先连接机器人", "Connect a robot first")); return }
        motion.arm(value); state.update { it.copy(motion=value) }; face.publish(faceState())
        if(!value) scope.launch { stopMotion() }
    }
    fun startFollow() {
        if(!motion.allowed || !robot.ready.value || !camera.running.value || !camera.detectEnabled || !follow.start(SystemClock.elapsedRealtime())) {
            report(t("需要相机、目标参考和本次运动许可", "Camera, target reference and motion permission are required")); return
        }
        followJob?.cancel()
        followJob=scope.launch {
            try {
                var lease=motion.acquire(MotionSource.FOLLOW); state.update { it.copy(following=true) }
                var moving=false
                while(isActive && follow.enabled) {
                    val now=SystemClock.elapsedRealtime(); val intent=follow.intent(now)
                    if(!follow.enabled) { follow.invalidate(); break }
                    if(intent.linear == 0.0 && intent.angular == 0.0) {
                        if(moving) { motion.stop(); moving=false }
                    } else {
                        if(motion.owner != MotionSource.FOLLOW) lease=motion.acquire(MotionSource.FOLLOW)
                        motion.send(JSONObject().put("type","motion").put("linear",intent.linear).put("angular",intent.angular).put("duration_ms",150).put("ramp_ms",0),MotionSource.FOLLOW,lease)
                        moving=true
                    }
                    delay((200-(SystemClock.elapsedRealtime()-now)).coerceAtLeast(1))
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { motion.revoke(); report(t("跟随动作失败，运动许可已撤销", "Follow command failed; motion permission revoked")); state.update { it.copy(motion=false) } }
            finally { follow.stop(); state.update { it.copy(following=false) }; withContext(NonCancellable) { runCatching { motion.stop() }.onFailure { report(t("停止未获确认", "Stop was not confirmed")) } } }
        }
    }
    private fun stopFollowLocal() { followJob?.cancel(); followJob=null; follow.invalidate(); state.update { it.copy(following=false,selected=null,followReference=false) } }
    private fun invalidateFollow() {
        val moving=follow.enabled; stopFollowLocal()
        if(moving) scope.launch { runCatching { motion.stop() }.onFailure { report(t("停止未获确认", "Stop was not confirmed")) } }
    }
    suspend fun stopMotion() {
        cancelAction(); stopFollowLocal(); motion.arm(false); state.update { it.copy(motion=false) }; face.publish(faceState())
        runCatching { motion.stop(true) }.onFailure { report(t("停止未获确认", "Stop was not confirmed")) }
    }
    private fun cancelAction() { actionEpoch++; actionJob?.cancel(); actionJob=null; activeLease=null; face.cancel() }
    private suspend fun runScenario(name: String, source: MotionSource, internal: Boolean=false) {
        val requestedEpoch=actionEpoch
        scenarioLock.withLock {
        check(requestedEpoch == actionEpoch) { t("动作已取消", "Action cancelled") }
        check(name in scenarios || internal && name in setOf("pose_neutral","pose_attentive","pose_curious","pose_happy","pose_sleepy","pose_scared","pose_shy","wake_activation")) { t("未知动作", "Unknown action") }
        check(!testOnly || source == MotionSource.TEST)
        val token=actionEpoch
        if(follow.enabled) { followJob?.cancelAndJoin(); stopFollowLocal() }
        val lease=if(motion.allowed) motion.acquire(source) else null
        activeSource=source; activeLease=lease; lastActivity=SystemClock.elapsedRealtime(); face.publish(faceState())
        try { face.run(name,lease != null) }
        catch(e: Exception) { if(lease != null) { motion.revoke(); state.update { it.copy(motion=false) }; runCatching { motion.stop(true) } }; throw e }
        finally { if(token == actionEpoch) { activeLease=null; if(lease != null) motion.release(source,lease) } }
    }
    }
    fun actionTest(name: String="ack_yes") {
        if(!robot.ready.value) { report(t("请先连接机器人", "Connect a robot first")); return }
        val testToken=gate.begin(true) ?: return
        state.update { it.copy(phase=Phase.TESTING) }; cancelAction()
        actionJob=scope.launch {
            try { motion.arm(true); runScenario(name,MotionSource.TEST); report(t("动作序列执行完成", "Action sequence completed")) }
            catch(_: Exception) { report(t("动作测试失败或已取消", "Action test failed or cancelled")) }
            finally { motion.revoke(); state.update { it.copy(motion=false) }; runCatching { motion.stop(true) }; face.publish(faceState()); if(gate.current(testToken)) { gate.stop(); gate.idle(); state.update { it.copy(phase=Phase.IDLE) } } }
        }
    }
    fun connect(row: RobotRow) { work=scope.launch {
        try {
            robot.connect(row); settings.last(row.address); state.update { it.copy(lastRobot=row.address) }
            motion.revoke()
        } catch(e: CancellationException) { robot.disconnect() }
        catch(_: Exception) { robot.disconnect(); report(t("机器人连接失败", "Robot connection failed")) }
    } }
    fun cancelConnect() { work?.cancel(); robot.disconnect() }
    fun alias(row: RobotRow, value: String) { scope.launch { settings.alias(row.address,value); robot.robots.update { rows -> rows.map { if(it.address == row.address) it.copy(alias=value) else it } } } }
    fun own(row: RobotRow) { scope.launch { settings.own(row.address); state.update { it.copy(myRobot=row.address) } } }
    fun soundTest() {
        scope.launch {
            try { audio.startOutput(); audio.tone(); report(t("提示音已提交，实际输出请听辨", "Test tone queued; listen to verify output")) }
            catch(_: Exception) { report(t("声音测试失败", "Sound test failed")) }
            if(gate.phase == Phase.IDLE) { delay(1500); if(gate.phase == Phase.IDLE) audio.stop() }
        }
    }
    fun recover() {
        val token=gate.generation
        scope.launch {
            try {
                check(gate.phase == Phase.ACTIVE)
                if(state.value.liveMode && state.value.config.provider != "offline") {
                    if(!live.connected.value) {
                        val key=credential(state.value.config)
                        live.start(state.value.config,key,resume=true)
                        if(!gate.current(token)) { live.close(); return@launch }
                        audio.startOutput(); audio.startInput(live::pcm)
                    } else audio.recover()
                }
                if(state.value.cameraWanted && has(Manifest.permission.CAMERA)) { if(!camera.running.value) camera.start() }
                report(t("恢复请求已执行，请检查音视频状态", "Recovery requested; verify audio and video status"))
            } catch(_: Exception) { if(gate.current(token)) report(t("恢复失败，请检查权限、凭据和网络", "Recovery failed; check permissions, credential and network")) }
        }
    }
    fun text(value: String, image: ByteArray?) {
        if(value.isBlank() || gate.phase != Phase.ACTIVE || requestJob?.isActive == true) return
        if(isStopRequest(value)) { scope.launch { stopMotion() }; return }
        lastActivity=SystemClock.elapsedRealtime()
        if(state.value.liveMode && live.connected.value && image == null) { live.text(value); return }
        val token=gate.generation; val c=state.value.config
        requestJob=scope.launch {
            state.update { it.copy(busy=true) }; face.publish(faceState())
            try {
                if(c.provider == "offline") {
                    val response=offlineReply(value)
                    state.update { it.copy(reply=response.text) }; runCatching { runScenario(response.scenario,MotionSource.SCENARIO,internal=true) }
                } else {
                    val key=credential(c)
                    val base=JSONObject(context.assets.open("live-setup.json").bufferedReader().use { it.readText() }).getJSONObject("setup").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
                    val system=base+"\n"+languageInstruction()+"\nReturn JSON {\"text\":\"reply\",\"scenario\":null} or one exact allowed scenario: "+scenarios.joinToString(",")
                    val raw=http.await(ProviderRequests.text(c,key,value,image,system))
                    if(!gate.current(token)) return@launch
                    val response=ProviderRequests.parse(raw,c.provider)
                    val parsed=runCatching { JSONObject(response.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
                    state.update { it.copy(reply=parsed?.optString("text") ?: response) }
                    parsed?.optString("scenario")?.takeIf { it in scenarios }?.let { runScenario(it,MotionSource.SCENARIO) }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(gate.current(token)) report(e.message ?: t("文字请求失败", "Text request failed")) }
            finally { if(gate.current(token)) { state.update { it.copy(busy=false) }; face.publish(faceState()) } }
        }
    }
    private fun startIdle(token: Long) {
        idleJob?.cancel()
        idleJob=scope.launch {
            while(gate.current(token)) {
                delay(18000)
                if(!gate.current(token) || follow.enabled || actionJob?.isActive == true || state.value.busy || audio.status.value.playing || SystemClock.elapsedRealtime()-lastActivity < 15000) continue
                runCatching {
                    runScenario("pose_curious",MotionSource.IDLE,internal=true)

                }
            }
        }
    }
}
