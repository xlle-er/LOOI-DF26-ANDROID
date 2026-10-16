package app.looi.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.*

/** Text pairs are deliberately colocated so every UI and native error has both translations. */
object L10n {
    @Volatile var language = "system"
    val chinese get() = language == "zh" || (language == "system" && Locale.getDefault().language == "zh")
    fun t(zh: String, en: String) = if (chinese) zh else en
}
fun t(zh: String, en: String) = L10n.t(zh, en)
class LooiException(zh: String, en: String) : Exception(t(zh, en))
enum class Phase { IDLE, STARTING, ACTIVE, TESTING, STOPPING }
class SessionGate {
    var generation = 0L; private set
    var phase = Phase.IDLE; private set
    @Synchronized fun begin(test: Boolean = false): Long? {
        if (phase != Phase.IDLE) return null
        generation++; phase = if (test) Phase.TESTING else Phase.STARTING
        return generation
    }
    @Synchronized fun active(token: Long): Boolean {
        if (!current(token)) return false
        if (phase == Phase.STARTING) phase = Phase.ACTIVE
        return true
    }
    @Synchronized fun current(token: Long) = generation == token && phase !in setOf(Phase.IDLE, Phase.STOPPING)
    @Synchronized fun stop() { generation++; phase = Phase.STOPPING }
    @Synchronized fun idle() { phase = Phase.IDLE }
}
object BodyProtocol {
    val supportedCommands=setOf("motion","stop","ping","config_get","config_update","device_status","wifi_scan","wifi_configure","wifi_forget","calibration_get","calibration_set","calibration_test","control_acquire","control_renew","control_release","ota_start","ota_exit","ota_status","firmware_check","firmware_install")
    const val SERVICE = "7f2c2b6a-2d8f-4f6b-9a59-8a7f9d7c0001"
    const val WRITE = "7f2c2b6a-2d8f-4f6b-9a59-8a7f9d7c0002"
    const val EVENTS = "7f2c2b6a-2d8f-4f6b-9a59-8a7f9d7c0003"
    fun validate(raw: JSONObject, allowed: Boolean): JSONObject {
        val p = JSONObject(raw.toString()); p.remove("id")
        fun number(key: String, low: Double, high: Double, fallback: Double? = null): Double {
            val value = if (p.has(key)) (p.opt(key) as? Number)?.toDouble() else fallback
            require(value != null && value.isFinite()) { t("动作参数无效", "Invalid motion parameter") }
            return value.coerceIn(low, high)
        }
        when (p.optString("type")) {
            "motion" -> {
                check(allowed) { t("身体动作未开启", "Body motion is disabled") }
                p.put("linear", number("linear", -.4, .4)).put("angular", number("angular", -.4, .4))
                val duration = number("duration_ms", 50.0, 1000.0, 300.0).toInt()
                p.put("duration_ms", duration).put("ramp_ms", number("ramp_ms", 0.0, min(500, duration/2).toDouble(), 120.0).toInt())
            }
            "stop", "ping", "config_get" -> Unit
            else -> throw LooiException("不支持的身体指令", "Unsupported body command")
        }
        return p
    }
    fun packet(p: JSONObject, id: String): ByteArray =
        ((if (p.optString("type") == "stop") "\n" else "") + JSONObject(p.toString()).put("id", id) + "\n").toByteArray(Charsets.UTF_8)
}
class NotificationDecoder {
    private val buffer = ByteArrayOutputStream()
    fun reset() = buffer.reset()
    fun append(bytes: ByteArray): List<JSONObject> {
        if (buffer.size() + bytes.size > 65536) { reset(); throw LooiException("机器人消息过长", "Robot notification overflow") }
        val values = mutableListOf<JSONObject>()
        for (b in bytes) {
            if (b.toInt() == 10) {
                val line = buffer.toString("UTF-8"); buffer.reset()
                runCatching { JSONObject(line) }.getOrNull()?.let(values::add)
            } else buffer.write(b.toInt())
        }
        return values
    }
}
/** Counts rendered frames, never treats AudioTrack.write as playback completion. */
class PcmLedger(val capacityFrames: Int = 24000 * 60) {
    var written = 0L; private set
    var played = 0L; private set
    var generation = 0L; private set
    private val chunks = java.util.ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var byteCount = 0
    val pendingFrames @Synchronized get() = byteCount / 2
    val pendingMs @Synchronized get() = pendingFrames * 1000L / 24000
    @Synchronized fun append(chunk: ByteArray) {
        require(chunk.size % 2 == 0)
        check(pendingFrames + chunk.size/2 <= capacityFrames) { t("音频缓冲已满", "Audio buffer is full") }
        if(chunk.isNotEmpty()) { chunks.addLast(chunk.copyOf()); byteCount += chunk.size }
    }
    @Synchronized fun slice(maxBytes: Int): ByteArray {
        var skip = ((written-played)*2).toInt()+headOffset
        val out = ByteArrayOutputStream(min(maxBytes,byteCount))
        for(chunk in chunks) {
            if(skip >= chunk.size) { skip -= chunk.size; continue }
            val take=min(maxBytes-out.size(),chunk.size-skip)
            out.write(chunk,skip,take); skip=0
            if(out.size() == maxBytes) break
        }
        return out.toByteArray()
    }
    @Synchronized fun wrote(countBytes: Int) { require(countBytes >= 0 && countBytes % 2 == 0); written += countBytes / 2 }
    @Synchronized fun progress(token: Long, framePosition: Long): Boolean {
        if (token != generation || framePosition < played || framePosition > written) return false
        var consumed = ((framePosition-played)*2).toInt()
        if(consumed == 0) return false
        byteCount -= consumed
        while(consumed > 0) {
            val first=chunks.first(); val n=min(consumed,first.size-headOffset)
            consumed -= n; headOffset += n
            if(headOffset == first.size) { chunks.removeFirst(); headOffset=0 }
        }
        played=framePosition; return true
    }
    @Synchronized fun recover() { generation++; written = 0; played = 0 }
    @Synchronized fun clear() { chunks.clear(); headOffset=0; byteCount=0; recover() }
}

data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x+w/2; val cy get() = y+h/2; val area get() = w*h
    fun iou(b: Box): Float {
        val intersection = max(0f, min(x+w,b.x+b.w)-max(x,b.x))*max(0f,min(y+h,b.y+b.h)-max(y,b.y))
        return intersection / (area+b.area-intersection).coerceAtLeast(.00001f)
    }
}
data class Detection(val id: Long, val label: String, val score: Float, val box: Box, val timeMs: Long)
data class FollowIntent(val linear: Double = 0.0, val angular: Double = 0.0)
class FollowPolicy {
    var target: Detection? = null; private set
    var reference: Float? = null; private set
    var enabled = false; private set
    private var advancing = false
    fun select(value: Detection) { target = value; reference = null; enabled = false; advancing = false }
    fun calibrate(now: Long): Boolean {
        val value = target ?: return false
        if (now-value.timeMs !in 0..300 || value.box.area <= 0) return false
        reference = value.box.area; return true
    }
    fun start(now: Long): Boolean { enabled = reference != null && target?.let { now-it.timeMs in 0..300 } == true; return enabled }
    fun stop() { enabled = false; advancing = false }
    fun invalidate() { stop(); target = null; reference = null }
    fun update(detections: List<Detection>, now: Long): Boolean {
        val previous = target ?: return false
        val matches = detections.filter { it.label == previous.label && now-it.timeMs in 0..300 && it.box.iou(previous.box) > .15f }
            .sortedByDescending { it.box.iou(previous.box) }
        if (matches.isEmpty() || (matches.size > 1 && matches[0].box.iou(previous.box)-matches[1].box.iou(previous.box) < .15f)) {
            invalidate(); return false
        }
        target = matches.first().copy(id = previous.id); return true
    }
    fun intent(now: Long): FollowIntent {
        val value = target
        if (!enabled || value == null || now-value.timeMs !in 0..300) { stop(); return FollowIntent() }
        val error = value.box.cx-.5f
        if (abs(error) > .14f) { advancing = false; return FollowIntent(angular = if(error < 0) -.1 else .1) }
        val ratio = value.box.area / (reference ?: return FollowIntent())
        if (ratio >= .95f) advancing = false else if (ratio < .8f) advancing = true
        return FollowIntent(linear = if(advancing) .1 else 0.0)
    }
}
fun isStopRequest(text: String): Boolean {
    val v = text.lowercase().replace(Regex("[\\s，。！？,.!?]"), "")
    return v in setOf("停","停止","请停止","stop") || listOf("停下","别动","停止移动","停止运动","不要移动","stopmoving").any(v::contains)
}
fun languageInstruction() = "Respond primarily in the language of the user's current spoken or written message. Switch naturally when the user switches language. If no language can be inferred, default to ${if(L10n.chinese) "Mandarin Chinese" else "English"}. Never announce the language selection."

/** Streaming 3:1 PCM16 conversion carries partial input groups across AudioRecord reads. */
class Pcm48To16 {
    private var remainder=ByteArray(0)
    fun convert(input: ByteArray): ByteArray {
        val all=remainder+input
        val groups=all.size/6; val out=ByteArray(groups*2)
        fun sample(i: Int) = ((all[i].toInt() and 255) or (all[i+1].toInt() shl 8)).toShort().toInt()
        repeat(groups) { g -> val v=(sample(g*6)+sample(g*6+2)+sample(g*6+4))/3; out[g*2]=v.toByte(); out[g*2+1]=(v shr 8).toByte() }
        remainder=all.copyOfRange(groups*6,all.size); return out
    }
}

data class OfflineReply(val text: String, val scenario: String)
/** Bilingual port of the web fallback's explicit-intent classifications. */
fun offlineReply(input: String): OfflineReply {
    val zh=input.any { it.code in 0x4e00..0x9fff }; val value=input.lowercase()
    fun reply(cn: String,en: String,scenario: String)=OfflineReply(if(zh) cn else en,scenario)
    return when {
        listOf("跟随","follow","track").any(value::contains) -> reply("先在控制面板选择目标、设定参考并开启本次运动许可吧。","Select a target, set a reference and enable session motion in the controls first.","pose_attentive")
        listOf("拍照","photo","picture","selfie").any(value::contains) -> reply("好，稍等一下。","Okay, hold still.","take_picture")
        listOf("向左","左转","look left","turn left").any(value::contains) -> reply("好，我向左看看。","Okay, I'll look left.","look_left")
        listOf("向右","右转","look right","turn right").any(value::contains) -> reply("好，我向右看看。","Okay, I'll look right.","look_right")
        listOf("过来","靠近","come closer","come here","forward").any(value::contains) -> reply("好，我试着靠近一点。","Okay, I'll try moving a little closer.","come_closer")
        listOf("后退","back up","go back").any(value::contains) -> reply("好，我让开一点。","Okay, I'll give you some space.","back_up")
        else -> reply("我在这里陪着你。","I'm here with you.","pose_happy")
    }
}

data class Letterbox(val width: Float,val height: Float) {
    private val scale=min(320f/width,320f/height)
    val scaledWidth=width*scale; val scaledHeight=height*scale
    val offsetX=(320-scaledWidth)/2; val offsetY=(320-scaledHeight)/2
    fun normalized(left: Float,top: Float,right: Float,bottom: Float): Box {
        val x=((left-offsetX)/scaledWidth).coerceIn(0f,1f); val y=((top-offsetY)/scaledHeight).coerceIn(0f,1f)
        val x2=((right-offsetX)/scaledWidth).coerceIn(x,1f); val y2=((bottom-offsetY)/scaledHeight).coerceIn(y,1f)
        return Box(x,y,x2-x,y2-y)
    }
}
