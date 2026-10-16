package app.looi.android
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.Base64

class AIRequestTest {
    @Test fun processesAllPartsAndCompletionTogether() {
        val p=JSONObject("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQI="}},{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AwQ="}}]},"inputTranscription":{"text":"你好"},"outputTranscription":{"text":"hello"},"turnComplete":true}}""")
        val e=parseLiveEvent(p) {Base64.getDecoder().decode(it)}; assertEquals(2,e.audio.size); assertEquals("你好",e.input); assertEquals("hello",e.output); assertTrue(e.complete)
    }
    @Test fun interruptedEventStillParsesTranscript() { val e=parseLiveEvent(JSONObject("""{"serverContent":{"interrupted":true,"inputTranscription":{"text":"停下"},"turnComplete":true}}""")) {ByteArray(0)}; assertTrue(e.interrupted);assertTrue(isStopRequest(e.input));assertTrue(e.complete) }
    @Test fun liveCredentialNeverGoesInUrl() { val (r,m)=ProviderRequests.live(ProviderConfig.defaults("gemini"),"test-credential");assertFalse(r.url.toString().contains("test-credential"));assertEquals("test-credential",r.header("x-goog-api-key"));assertTrue(m.startsWith("models/")) }
    @Test fun vertexRequiresProjectAndBearer() { assertThrows(IllegalStateException::class.java) {ProviderRequests.validate(ProviderConfig("vertex",project="project",bearer=false),"token",true)} }
    @Test fun vertexResourceIsFullyQualified() { val (r,m)=ProviderRequests.live(ProviderConfig("vertex",project="project",region="us-central1",bearer=true),"token");assertTrue(m.startsWith("projects/project/locations/us-central1/"));assertEquals("Bearer token",r.header("Authorization")) }
    @Test fun publicHttpRejected() { assertThrows(IllegalArgumentException::class.java) { ProviderRequests.safeUrl("http://example.com") } }
    @Test fun privateHttpAllowedByValidator() { assertEquals("http://192.168.1.2:11434",ProviderRequests.safeUrl("http://192.168.1.2:11434/")) }
    @Test fun embeddedCredentialRejected() { assertThrows(IllegalArgumentException::class.java) {ProviderRequests.safeUrl("https://user:pass@example.com")} }
    @Test fun unsupportedProviderCannotUseLive() { assertThrows(IllegalStateException::class.java) {ProviderRequests.validate(ProviderConfig.defaults("ollama"),"",true)} }
    @Test fun languagePolicyPrioritizesUserLanguage() { L10n.language="en";val en=languageInstruction();assertTrue(en.contains("current spoken or written message"));L10n.language="zh";assertTrue(languageInstruction().contains("Mandarin Chinese"));L10n.language="system" }
    @Test fun providerConfigRoundTrips() { val c=ProviderConfig("custom","private-model",baseUrl="https://example.com",vision=true);assertEquals(c,ProviderConfig.from(c.json())) }
    @Test fun parseMultipleTextParts() { assertEquals("hello world",ProviderRequests.parse("""{"candidates":[{"content":{"parts":[{"text":"hello "},{"text":"world"}]}}]}""","gemini")) }
    @Test fun stopIntentSupportsBothLanguages() { listOf("停下！","请不要移动","stop moving","STOP").forEach { assertTrue(isStopRequest(it)) };assertFalse(isStopRequest("你好")) }
}
class AdditionalPolicyTest {
    @Test fun vertexExpressRejectsLiveBeforeNetwork() { assertThrows(IllegalStateException::class.java) { ProviderRequests.validate(ProviderConfig("vertex",express=true),"test",true) } }
    @Test fun offlineUsesInputLanguageOverUiLanguage() { L10n.language="en"; assertTrue(offlineReply("你好").text.contains("这里"));L10n.language="zh";assertEquals("I'm here with you.",offlineReply("hello").text);L10n.language="system" }
    @Test fun followRequestNeverAutomaticallyArmsMotion() { assertEquals("pose_attentive",offlineReply("follow me").scenario) }
}
