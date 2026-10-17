package app.looi.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("looi_settings")
data class ProviderConfig(
    val provider: String = "offline", val model: String = "gemini-3.8-flash", val liveModel: String = "gemini-3.8-live",
    val baseUrl: String = "", val project: String = "", val region: String = "us-central1", val express: Boolean = false,
    val bearer: Boolean = false, val vision: Boolean = false, val googleOAuth: Boolean = false
) {
    fun json() = JSONObject().put("provider",provider).put("model",model).put("liveModel",liveModel).put("baseUrl",baseUrl)
        .put("project",project).put("region",region).put("express",express).put("bearer",bearer).put("vision",vision).put("googleOAuth",googleOAuth)
    companion object {
        val providers = listOf("offline","gemini","vertex","qwen","groq","fireworks","ollama","custom")
        fun defaults(provider: String) = when(provider) {
            "qwen" -> ProviderConfig(provider, "qwen-plus", baseUrl="https://dashscope.aliyuncs.com/compatible-mode/v1")
            "groq" -> ProviderConfig(provider,"openai/gpt-oss-120b",baseUrl="https://api.groq.com/openai/v1")
            "fireworks" -> ProviderConfig(provider,"accounts/fireworks/models/llama-v3p3-70b-instruct",baseUrl="https://api.fireworks.ai/inference/v1")
            "ollama" -> ProviderConfig(provider,"qwen3:8b",baseUrl="http://127.0.0.1:11434")
            "custom" -> ProviderConfig(provider, "")
            else -> ProviderConfig(provider)
        }
        fun from(o: JSONObject) = ProviderConfig(o.optString("provider","offline"),o.optString("model"),o.optString("liveModel","gemini-3.8-live"),o.optString("baseUrl"),o.optString("project"),o.optString("region","us-central1"),o.optBoolean("express"),o.optBoolean("bearer"),o.optBoolean("vision"),o.optBoolean("googleOAuth"))
    }
}
class SettingsStore(private val context: Context) {
    suspend fun migrateStoredScenes() { context.dataStore.edit { prefs ->
        prefs.asMap().toMap().forEach { (key,value) ->
            if(key.name.contains("scenario",true) && value is String) runCatching {
                val scenes=org.json.JSONArray(value)
                if(scenes.length()>0 && (0 until scenes.length()).all { scenes.optJSONObject(it)?.optJSONArray("steps")!=null }) {
                    val cleaned=org.json.JSONArray()
                    for(i in 0 until scenes.length()) {
                        val scene=scenes.getJSONObject(i);val steps=scene.getJSONArray("steps");val kept=org.json.JSONArray();var meaningful=false
                        for(j in 0 until steps.length()) { val step=steps.getJSONObject(j);val kind=step.optString("kind");if(kind in setOf("move","stop","pause","face","expression")) { kept.put(step);if(kind in setOf("move","face","expression"))meaningful=true } }
                        if(meaningful) { scene.put("steps",kept);scene.remove("usesHead");cleaned.put(scene) }
                    }
                    prefs[stringPreferencesKey(key.name)]=cleaned.toString()
                }
            }
        }
    } }

    private val selection = stringPreferencesKey("provider")
    suspend fun selected() = context.dataStore.data.first()[selection] ?: "offline"
    suspend fun load(provider: String): ProviderConfig {
        val text = context.dataStore.data.first()[stringPreferencesKey("config_$provider")]
        return text?.let { runCatching { ProviderConfig.from(JSONObject(it)) }.getOrNull() } ?: ProviderConfig.defaults(provider)
    }
    suspend fun save(config: ProviderConfig) { context.dataStore.edit { it[selection] = config.provider; it[stringPreferencesKey("config_${config.provider}")] = config.json().toString() } }
    suspend fun option(name: String, fallback: Boolean) = context.dataStore.data.first()[booleanPreferencesKey(name)] ?: fallback
    suspend fun saveOption(name: String,value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey(name)] = value } }
    suspend fun language() = context.dataStore.data.first()[stringPreferencesKey("language")] ?: "system"
    suspend fun setLanguage(value: String) { require(value in listOf("system","zh","en")); context.dataStore.edit { it[stringPreferencesKey("language")] = value }; L10n.language = value }
    suspend fun alias(address: String, name: String) { context.dataStore.edit { it[stringPreferencesKey("alias_$address")] = name.take(40) } }
    suspend fun alias(address: String) = context.dataStore.data.first()[stringPreferencesKey("alias_$address")] ?: ""
    suspend fun own(address: String) { context.dataStore.edit { it[stringPreferencesKey("my_robot")] = address } }
    suspend fun own() = context.dataStore.data.first()[stringPreferencesKey("my_robot")] ?: ""
    suspend fun last(address: String) { context.dataStore.edit { it[stringPreferencesKey("last_robot")] = address } }
    suspend fun last() = context.dataStore.data.first()[stringPreferencesKey("last_robot")] ?: ""
}
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("looi_credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("looi_credentials_v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("looi_credentials_v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun set(provider: String, secret: String) {
        if (secret.isEmpty() || (!provider.startsWith("device.") && secret.isBlank())) return // Empty input preserves the stored credential.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal((if(provider.startsWith("device."))secret else secret.trim()).toByteArray()),Base64.NO_WRAP)
        check(prefs.edit().putString(provider,value).commit())
    }
    @Synchronized fun get(provider: String): String {
        val raw = prefs.getString(provider,null) ?: return ""
        val parts = raw.split(":",limit=2); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    @Synchronized fun remove(provider: String) { check(prefs.edit().remove(provider).commit()) }
}
