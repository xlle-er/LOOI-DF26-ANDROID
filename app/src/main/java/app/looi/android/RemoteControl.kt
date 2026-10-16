package app.looi.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

data class DriveVector(val linear: Double=0.0,val angular: Double=0.0) {
    companion object {
        fun joystick(x: Double,y: Double,speed: Double): DriveVector {
            if(!x.isFinite() || !y.isFinite() || !speed.isFinite()) return DriveVector()
            val radius=hypot(x,y)
            if(radius <= .12 || !radius.isFinite()) return DriveVector()
            val scale=((radius-.12)/.88).coerceAtMost(1.0)*speed.coerceIn(.05,.30)/(abs(x)+abs(y)).coerceAtLeast(.001)
            return DriveVector(-y*scale,x*scale)
        }
    }
}
enum class RemoteMode { JOYSTICK, BUTTONS }
enum class RemotePhase { INACTIVE, PREPARING, READY, STOPPING, FAILED }
data class RemoteState(val presented: Boolean=false,val phase: RemotePhase=RemotePhase.INACTIVE,
    val mode: RemoteMode=RemoteMode.JOYSTICK,val speed: Double=.15,val moving: Boolean=false,
    val message: String="")

/** Main-thread controller: one gesture, one pending ACK, latest input only. */
class RemoteControl(private val scope: CoroutineScope,private val connected: ()->Boolean,
    private val send: suspend (JSONObject)->Unit,private val stop: suspend ()->Boolean,
    private val takeover: ()->Unit={},private val fault: ()->Unit={}) {
    val state=MutableStateFlow(RemoteState())
    private var epoch=0L
    private var nextGesture=0L
    private var gesture: Long?=null
    private var x=0.0; private var y=0.0
    private var loop: Job?=null
    private var stopping: Job?=null
    val ready get()=state.value.presented && state.value.phase == RemotePhase.READY && connected()
    val busy get()=state.value.presented || state.value.phase == RemotePhase.STOPPING
    fun open() {
        if(busy) return
        state.value=state.value.copy(presented=true)
        takeover(); halt(false)
    }
    fun close() { if(state.value.presented) { state.value=state.value.copy(presented=false); halt(true) } }
    fun emergencyStop() { if(state.value.presented) halt(false) }
    fun retry() { if(state.value.presented && state.value.phase == RemotePhase.FAILED) halt(false) }
    fun mode(value: RemoteMode) { if(value != state.value.mode) { state.value=state.value.copy(mode=value); emergencyStop() } }
    fun speed(value: Double) { if(value.isFinite()) state.value=state.value.copy(speed=value.coerceIn(.05,.30)) }
    private fun invalidate(): Long {
        epoch++; loop?.cancel(); loop=null; gesture=null; x=0.0; y=0.0
        state.value=state.value.copy(moving=false)
        return epoch
    }
    private suspend fun confirmedStop(): Boolean = try { withTimeout(6000) { stop() } }
        catch(e: CancellationException) { if(e is TimeoutCancellationException) false else throw e }
        catch(_: Exception) { false }
    private fun halt(closing: Boolean) {
        val token=invalidate()
        state.value=state.value.copy(phase=if(closing) RemotePhase.STOPPING else RemotePhase.PREPARING,
            message=t("正在确认机器人停止…","Confirming robot stop…"))
        // Stop calls are not cancelled: a real stop must complete even across rapid UI changes.
        val previous=stopping
        stopping=scope.launch {
            previous?.join()
            if(token != epoch) return@launch
            val confirmed=confirmedStop()
            if(token != epoch) return@launch
            if(!confirmed) fault()
            state.value=state.value.copy(phase=if(closing) RemotePhase.INACTIVE else if(confirmed && connected()) RemotePhase.READY else RemotePhase.FAILED,
                message=if(confirmed && connected()) t("按住操作，松手停止","Hold to move; release to stop") else t("停止未获确认，请检查连接后重试","Stop unconfirmed; check the connection and retry"))
        }
    }
    fun connectionLost() {
        if(!state.value.presented || state.value.phase == RemotePhase.FAILED) return
        val token=invalidate(); fault()
        state.value=state.value.copy(phase=RemotePhase.FAILED,message=t("遥控连接或指令确认失败，已停止续发","Remote connection or ACK failed; renewals stopped"))
        scope.launch { if(token == epoch && connected()) confirmedStop() }
    }
    fun begin(): Long? {
        if(!ready || gesture != null) return null
        return (++nextGesture).also { gesture=it }
    }
    fun update(id: Long,dx: Double,dy: Double) {
        if(!ready || gesture != id) return
        x=dx; y=dy
        if(loop != null) return
        val token=epoch
        loop=scope.launch(start=CoroutineStart.LAZY) {
            try {
                while(isActive && token == epoch && gesture == id && ready) {
                    val vector=DriveVector.joystick(x,y,state.value.speed)
                    if(vector == DriveVector()) {
                        if(state.value.moving) {
                            check(confirmedStop())
                            if(token != epoch) return@launch
                            state.value=state.value.copy(moving=false)
                        }
                    } else {
                        withTimeout(6000) { send(BodyProtocol.validate(JSONObject().put("type","motion").put("linear",vector.linear)
                            .put("angular",vector.angular).put("duration_ms",400).put("ramp_ms",60).put("label","manual_remote"),true)) }
                        if(token != epoch) return@launch
                        state.value=state.value.copy(moving=true)
                    }
                    delay(100)
                }
            } catch(e: CancellationException) { if(e is TimeoutCancellationException && token == epoch) connectionLost() else throw e }
            catch(_: Exception) { if(token == epoch) connectionLost() }
            finally { if(token == epoch) loop=null }
        }.also { it.start() }
    }
    fun release(id: Long) { if(gesture == id) emergencyStop() }
    fun pulse(dx: Double,dy: Double) {
        val id=begin() ?: return
        update(id,dx,dy)
        scope.launch { delay(200); release(id) }
    }
}
