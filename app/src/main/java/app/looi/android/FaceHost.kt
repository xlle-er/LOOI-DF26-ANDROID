package app.looi.android

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

class FaceHost(private val context: Context, private val scope: CoroutineScope, private val report: (String)->Unit) {
    private var view: WebView? = null
    private var ready=false
    val isReady get() = ready
    private var activeId: String? = null
    private val completions=mutableMapOf<String,CompletableDeferred<Unit>>()
    var command: suspend (JSONObject)->JSONObject = { throw LooiException("动作不可用", "Action unavailable") }
    var photo: ()->ByteArray = { throw LooiException("相机未启动", "Camera is off") }
    private var state=JSONObject()
    var onTap: ()->Unit = {}
    private val allowedOrigin="https://appassets.androidplatform.net"
    fun create(): WebView {
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build()
        return WebView(context).apply {
            view=this; setBackgroundColor(Color.BLACK)
            setOnTouchListener { v,event -> if(event.action == android.view.MotionEvent.ACTION_UP) { onTap(); v.performClick() }; false }
            settings.javaScriptEnabled=true; settings.allowFileAccess=false; settings.allowContentAccess=false; settings.domStorageEnabled=false
            settings.mediaPlaybackRequiresUserGesture=true; settings.setSupportMultipleWindows(false)
            webViewClient=object: WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                    ?: WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(ByteArray(0)))
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true
                override fun onRenderProcessGone(v: WebView,detail: RenderProcessGoneDetail): Boolean {
                    ready=false; completions.values.forEach { it.completeExceptionally(LooiException("眼睛组件已重载", "Face component reloaded")) }; completions.clear()
                    report(t("眼睛组件停止，请退出后重新进入", "Face renderer stopped; exit and enter again")); return true
                }
            }
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) throw LooiException("请更新 Android System WebView", "Update Android System WebView")
            WebViewCompat.addWebMessageListener(this,"LooiNative",setOf(allowedOrigin)) { _, message, origin, mainFrame, _ ->
                if(mainFrame && origin.scheme == "https" && origin.host == "appassets.androidplatform.net") scope.launch { receive(message.data ?: "") }
            }
            loadUrl("$allowedOrigin/assets/face/index.html")
        }
    }
    private suspend fun receive(raw: String) {
        if(raw.length > 65536) return
        val o=runCatching { JSONObject(raw) }.getOrNull() ?: return
        when(o.optString("kind")) {
            "ready" -> { ready=true; publish(state) }
            "scenarioDone" -> {
                val id=o.optString("runId"); val completion=completions.remove(id) ?: return
                if(o.optBoolean("ok")) completion.complete(Unit) else completion.completeExceptionally(LooiException("动作未完成", "Action did not complete"))
            }
            "command", "photo" -> {
                val id=o.optString("id"); val run=o.optString("runId")
                if(run != activeId) { reply(id,null,t("动作已取消", "Action cancelled")); return }
                try {
                    val value=if(o.optString("kind") == "command") command(o.getJSONObject("payload"))
                        else "data:image/jpeg;base64,"+android.util.Base64.encodeToString(photo(),android.util.Base64.NO_WRAP)
                    reply(id,value,null)
                } catch(_: Exception) { reply(id,null,t("原生操作失败或已取消", "Native operation failed or was cancelled")) }
            }
        }
    }
    private fun jsonValue(value: Any?): String = org.json.JSONArray().put(value ?: JSONObject.NULL).toString().let { it.substring(1,it.length-1) }
    private fun reply(id: String, value: Any?, error: String?) {
        view?.evaluateJavascript("window.looiFace?.reply(${JSONObject.quote(id)},${jsonValue(value)},${jsonValue(error)})",null)
    }
    suspend fun layoutDiagnostics(): String = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        val v=view
        if(v == null) { c.resumeWith(Result.success("no_view")); return@suspendCancellableCoroutine }
        v.evaluateJavascript("JSON.stringify({viewport:[innerWidth,innerHeight],body:document.body.getBoundingClientRect().toJSON(),eye:document.querySelector('.looi-eye')?.getBoundingClientRect().toJSON(),core:document.querySelector('.looi-eye__core')?.getBoundingClientRect().toJSON(),color:document.querySelector('.looi-eye__core')?getComputedStyle(document.querySelector('.looi-eye__core')).backgroundColor:'none',height:getComputedStyle(document.body).height,sheets:[...document.styleSheets].map(s=>s.href),opacity:getComputedStyle(document.body).opacity,visibility:getComputedStyle(document.body).visibility})") { result ->
            if(c.isActive) c.resumeWith(Result.success("native=${v.width}x${v.height}, alpha=${v.alpha}, visibility=${v.visibility}; dom=$result"))
        }
    }
    fun publish(value: JSONObject) { state=value; if(ready) view?.evaluateJavascript("window.looiFace?.state($value)",null) }
    suspend fun run(name: String, motion: Boolean) {
        check(ready) { t("眼睛尚未准备好", "Face is not ready") }
        check(activeId == null) { t("动作正在执行", "An action is already running") }
        val id=UUID.randomUUID().toString(); val completion=CompletableDeferred<Unit>(); completions[id]=completion; activeId=id
        try {
            view?.evaluateJavascript("window.looiFace.run(${JSONObject.quote(name)},$motion,${JSONObject.quote(id)}).then(()=>window.LooiNative.postMessage(JSON.stringify({kind:'scenarioDone',runId:${JSONObject.quote(id)},ok:true}))).catch(()=>window.LooiNative.postMessage(JSON.stringify({kind:'scenarioDone',runId:${JSONObject.quote(id)},ok:false})))",null)
            withTimeout(60000) { completion.await() }
        } finally { completions.remove(id); if(activeId == id) activeId=null }
    }
    fun cancel() {
        activeId=null; completions.values.forEach { it.cancel() }; completions.clear()
        view?.evaluateJavascript("window.looiFace?.cancel()",null)
    }
    fun release(old: WebView) { if(old === view) destroy() else old.destroy() }
    fun destroy() { cancel(); ready=false; view?.stopLoading(); view?.destroy(); view=null }
}
