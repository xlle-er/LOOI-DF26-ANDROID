package app.looi.android

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okio.ByteString
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ProviderRequests {
    fun identifier(value: String): String { require(value.matches(Regex("[A-Za-z0-9._-]+"))) { t("模型、项目或区域无效", "Invalid model, project or region") }; return value }
    fun validate(config: ProviderConfig, key: String, live: Boolean) {
        require(config.provider in ProviderConfig.providers)
        if(config.provider == "offline") return
        if(config.provider !in listOf("ollama","custom")) check(key.isNotBlank()) { t("请配置凭据", "Configure a credential first") }
        if(config.provider == "vertex") {
            if(!config.express) { identifier(config.project); identifier(config.region); check(config.bearer) { t("标准 Vertex 需要 OAuth 访问令牌", "Standard Vertex requires an OAuth access token") } }
            else check(!config.bearer) { t("Express 使用 API Key", "Express uses an API key") }
        }
        if(live && config.provider == "vertex") check(!config.express) { t("Vertex Live 需要标准项目，请关闭 Express 或使用文字模式", "Vertex Live requires a standard project; disable Express or use text mode") }
        if(live) check(config.provider in listOf("gemini","vertex")) { t("此服务不支持 Live，请选择文字模式", "This provider supports text mode only") }
    }
    fun host(c: ProviderConfig) = if(c.region == "global") "aiplatform.googleapis.com" else "${identifier(c.region)}-aiplatform.googleapis.com"
    fun live(c: ProviderConfig, key: String): Pair<Request,String> {
        validate(c,key,true)
        val model=identifier(c.liveModel.removePrefix("models/"))
        val resource=if(c.provider == "vertex" && !c.express) "projects/${identifier(c.project)}/locations/${identifier(c.region)}/publishers/google/models/$model" else "models/$model"
        val url=if(c.provider == "gemini") "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
            else if(c.express) "https://aiplatform.googleapis.com/ws/google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent"
            else "https://${host(c)}/ws/google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent"
        return Request.Builder().url(url).header(if(c.bearer) "Authorization" else "x-goog-api-key",if(c.bearer) "Bearer $key" else key).build() to resource
    }
    fun safeUrl(value: String): String {
        val uri=URI(value)
        require(uri.scheme in listOf("https","http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { t("服务地址无效", "Invalid service URL") }
        if(uri.scheme == "http") {
            val host=uri.host; val parts=host.split('.').mapNotNull(String::toIntOrNull)
            val local=host in listOf("localhost","::1","[::1]") || host.endsWith(".local") || (parts.size == 4 && parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31))
            require(local) { t("公网服务必须使用 HTTPS", "Public services must use HTTPS") }
        }
        return value.trimEnd('/')
    }
    fun text(c: ProviderConfig, key: String, text: String, image: ByteArray?, system: String): Request {
        validate(c,key,false)
        if(image != null) check(c.vision || c.provider in listOf("gemini","vertex")) { t("请先确认模型支持视觉输入", "Confirm that the model supports images first") }
        val builder=Request.Builder(); val body: JSONObject; val url: String
        if(c.provider in listOf("gemini","vertex")) {
            val model=identifier(c.model.removePrefix("models/"))
            url=if(c.provider == "gemini") "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                else if(c.express) "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:generateContent"
                else "https://${host(c)}/v1/projects/${identifier(c.project)}/locations/${identifier(c.region)}/publishers/google/models/$model:generateContent"
            builder.header(if(c.bearer) "Authorization" else "x-goog-api-key",if(c.bearer) "Bearer $key" else key)
            val parts=JSONArray().put(JSONObject().put("text",text))
            image?.let { parts.put(JSONObject().put("inlineData",JSONObject().put("mimeType","image/jpeg").put("data",Base64.encodeToString(it,Base64.NO_WRAP)))) }
            body=JSONObject().put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",system))))
                .put("contents",JSONArray().put(JSONObject().put("role","user").put("parts",parts))).put("generationConfig",JSONObject().put("maxOutputTokens",1024))
        } else {
            val base=safeUrl(c.baseUrl); val user=JSONObject().put("role","user").put("content",text)
            if(c.provider == "ollama") {
                url="$base/api/chat"
                image?.let { user.put("images",JSONArray().put(Base64.encodeToString(it,Base64.NO_WRAP))) }
            } else {
                url="$base/chat/completions"
                if(key.isNotBlank()) builder.header("Authorization","Bearer $key")
                image?.let { user.put("content",JSONArray().put(JSONObject().put("type","text").put("text",text)).put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64,"+Base64.encodeToString(it,Base64.NO_WRAP))))) }
            }
            body=JSONObject().put("model",c.model).put("messages",JSONArray().put(JSONObject().put("role","system").put("content",system)).put(user))
            if(c.provider == "ollama") body.put("stream",false) else body.put("max_tokens",1024)
            if(c.provider == "qwen") body.put("enable_thinking",false)
        }
        return builder.url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
    fun parse(text: String, provider: String): String {
        val o=JSONObject(text)
        val value=if(provider in listOf("gemini","vertex")) {
            val parts=o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            (0 until (parts?.length() ?: 0)).joinToString("") { parts?.optJSONObject(it)?.optString("text","") ?: "" }
        } else if(provider == "ollama") o.optJSONObject("message")?.optString("content")
        else o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        check(!value.isNullOrBlank()) { t("模型未返回有效内容", "The model returned no usable content") }; return value
    }
}
/** Pure event parser: audio and transcripts are processed even when completion is in the same packet. */
data class LiveEvent(val audio: List<ByteArray>,val input: String,val output: String,val interrupted: Boolean,val complete: Boolean)
fun parseLiveEvent(o: JSONObject, decode: (String)->ByteArray): LiveEvent {
    val c=o.optJSONObject("serverContent") ?: JSONObject(); val parts=c.optJSONObject("modelTurn")?.optJSONArray("parts")
    val audio=mutableListOf<ByteArray>(); val texts=StringBuilder()
    for(i in 0 until (parts?.length() ?: 0)) {
        val p=parts!!.optJSONObject(i) ?: continue
        p.optJSONObject("inlineData")?.let { blob -> if(blob.optString("mimeType").startsWith("audio/pcm")) audio += decode(blob.getString("data")) }
        if(p.has("text") && !p.optBoolean("thought")) texts.append(p.optString("text"))
    }
    return LiveEvent(audio,c.optJSONObject("inputTranscription")?.optString("text") ?: "",c.optJSONObject("outputTranscription")?.optString("text") ?: texts.toString(),c.optBoolean("interrupted"),c.optBoolean("turnComplete"))
}
class LiveClient(private val context: Context, private val scope: CoroutineScope, private val audio: AudioEngine, private val report: (String)->Unit) {
    val connected=MutableStateFlow(false)
    val transcript=MutableStateFlow("")
    var onStop: ()->Unit = {}
    var onCancel: ()->Unit = {}
    var onScenario: suspend (String)->Unit = {}
    var onFailure: ()->Unit = {}
    var receivedAudio=0; private set
    var turnComplete=false; private set
    var videoCount=0; private set
    private val http=OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var generation=0L
    private var setupDone=CompletableDeferred<Unit>()
    private var outgoing: Channel<String>? = null
    private var writer: Job? = null
    private val queued=AtomicInteger(0)
    private var resumableHandle: String? = null
    private val tools=mutableMapOf<String,Job>()
    private val cancelled=mutableSetOf<String>()
    private var failureReported=false
    suspend fun start(c: ProviderConfig, key: String, test: Boolean=false, resume: Boolean=false) {
        close(clearHandle=!resume)
        val token=++generation; failureReported=false; receivedAudio=0; turnComplete=false; videoCount=0; transcript.value=""
        setupDone=CompletableDeferred(); val (request,model)=ProviderRequests.live(c,key)
        val setup=JSONObject(context.assets.open("live-setup.json").bufferedReader().use { it.readText() }).getJSONObject("setup")
        setup.put("model",model)
        val parts=setup.getJSONObject("systemInstruction").getJSONArray("parts")
        parts.put(JSONObject().put("text",languageInstruction()))
        parts.put(JSONObject().put("text","Local personality profile: "+context.assets.open("personality.json").bufferedReader().use { it.readText() }))
        if(test) {
            setup.put("tools",JSONArray()).put("sessionResumption",JSONObject())
            setup.put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",if(L10n.chinese) "只用中文说一次：你好，语音测试成功。然后结束。" else "Say exactly once: Hello, the voice test was successful. Then finish."))))
            setup.getJSONObject("generationConfig").put("maxOutputTokens",256)
        } else if(resume) resumableHandle?.let { setup.put("sessionResumption",JSONObject().put("handle",it)) }
        val channel=Channel<String>(48); outgoing=channel
        socket=http.newWebSocket(request,object: WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) {
                if(token != generation) { ws.cancel(); return }; ws.send(JSONObject().put("setup",setup).toString())
            }
            override fun onMessage(ws: WebSocket,text: String) {
                if(text.toByteArray().size > 4000000) { scope.launch { if(token == generation) fail() }; return }
                scope.launch { if(token == generation) runCatching { message(JSONObject(text),test) }.onFailure { fail() } }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { if(bytes.size <= 4000000) onMessage(ws,bytes.utf8()) else scope.launch { if(token == generation) fail() } }
            override fun onFailure(ws: WebSocket,error: Throwable,response: Response?) { scope.launch { if(token == generation) fail() } }
            override fun onClosed(ws: WebSocket,code: Int,reason: String) { scope.launch { if(token == generation) fail() } }
        })
        writer=scope.launch {
            for(value in channel) {
                queued.decrementAndGet()
                if(token != generation) break
                if((socket?.queueSize() ?: 0) > 512000 || socket?.send(value) != true) { fail(); break }
            }
        }
        try { withTimeout(15000) { setupDone.await() }; check(token == generation); connected.value=true }
        catch(e: Exception) { close(clearHandle=false); throw e }
    }
    private fun message(o: JSONObject, test: Boolean) {
        if(o.has("error")) { fail(); return }
        if(o.has("setupComplete")) setupDone.complete(Unit)
        o.optJSONObject("sessionResumptionUpdate")?.let { if(it.optBoolean("resumable")) resumableHandle=it.optString("newHandle").takeIf(String::isNotBlank) }
        o.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
            for(i in 0 until ids.length()) { val id=ids.getString(i); cancelled.add(id); tools.remove(id)?.cancel() }; onCancel()
        }
        val event=parseLiveEvent(o) { Base64.decode(it,Base64.DEFAULT) }
        if(event.interrupted) audio.interrupt()
        if(event.input.isNotEmpty()) { transcript.value=(transcript.value+event.input).takeLast(2000); if(isStopRequest(event.input)) onStop() }
        if(event.output.isNotEmpty()) transcript.value=(transcript.value+event.output).takeLast(3000)
        if(!event.interrupted) event.audio.forEach { audio.enqueue(it); receivedAudio++; turnComplete=false }
        if(event.complete) { turnComplete=true; audio.completeTurn() }
        val calls=o.optJSONObject("toolCall")?.optJSONArray("functionCalls")
        for(i in 0 until (calls?.length() ?: 0)) {
            val call=calls!!.getJSONObject(i); val id=call.optString("id"); val token=generation
            if(test || id in cancelled) continue
            tools[id]=scope.launch {
                val result=runCatching { check(call.optString("name") == "run_scenario"); onScenario(call.getJSONObject("args").getString("name")) }
                if(token == generation && id !in cancelled) send(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(JSONObject().put("id",id).put("name","run_scenario").put("response",JSONObject().put("ok",result.isSuccess).put("reason",if(result.isSuccess) "" else "Action unavailable or cancelled"))))))
                tools.remove(id)
            }
        }
        if(o.has("goAway")) report(t("实时会话即将到期，可恢复连接", "Live session is expiring; reconnect is available"))
    }
    private fun fail() {
        if(failureReported) return
        failureReported=true; setupDone.completeExceptionally(LooiException("Live 连接失败，请检查模型、凭据和网络", "Live connection failed; check model, credential and network"))
        connected.value=false; socket?.cancel(); outgoing?.close(); writer?.cancel()
        report(t("Live 已断开，可恢复连接", "Live disconnected; reconnect is available")); onFailure()
    }
    fun send(o: JSONObject, video: Boolean=false) {
        if(!connected.value) return
        if(video && (queued.get() >= 6 || (socket?.queueSize() ?: 0) > 128000)) return
        queued.incrementAndGet()
        if(outgoing?.trySend(o.toString())?.isSuccess != true) { queued.decrementAndGet(); scope.launch { fail() } }
        else if(video) videoCount++
    }
    fun pcm(bytes: ByteArray) = send(JSONObject().put("realtimeInput",JSONObject().put("audio",JSONObject().put("mimeType","audio/pcm;rate=16000").put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)))))
    fun frame(bytes: ByteArray) = send(JSONObject().put("realtimeInput",JSONObject().put("video",JSONObject().put("mimeType","image/jpeg").put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)))),true)
    fun text(value: String) = send(JSONObject().put("clientContent",JSONObject().put("turns",JSONArray().put(JSONObject().put("role","user").put("parts",JSONArray().put(JSONObject().put("text",value))))).put("turnComplete",true)))
    fun close(clearHandle: Boolean=true) {
        generation++; connected.value=false; socket?.cancel(); socket=null; outgoing?.close(); outgoing=null; writer?.cancel(); writer=null; queued.set(0)
        tools.values.forEach(Job::cancel); tools.clear(); cancelled.clear(); if(clearHandle) resumableHandle=null
    }
}
suspend fun OkHttpClient.await(request: Request): String = suspendCancellableCoroutine { continuation ->
    val call=newCall(request); continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object: Callback {
        override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(LooiException("网络请求失败，请检查地址和连接", "Network request failed; check address and connection")) }
        override fun onResponse(call: Call,response: Response) {
            response.use {
                if(!continuation.isActive) return
                if(!response.isSuccessful) continuation.resumeWithException(LooiException("AI 请求失败，检查模型、凭据和配额", "AI request failed; check model, credential and quota"))
                else try {
                    val output=java.io.ByteArrayOutputStream()
                    response.body?.byteStream()?.use { stream ->
                        val buffer=ByteArray(8192)
                        while(true) { val n=stream.read(buffer); if(n < 0) break; check(output.size()+n <= 4000000); output.write(buffer,0,n) }
                    }
                    if(continuation.isActive) continuation.resume(output.toString("UTF-8"))
                } catch(_: Exception) { if(continuation.isActive) continuation.resumeWithException(LooiException("AI 响应无效或过大", "AI response is invalid or too large")) }
            }
        }
    })
}
