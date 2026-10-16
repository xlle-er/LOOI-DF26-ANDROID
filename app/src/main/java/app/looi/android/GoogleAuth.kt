package app.looi.android

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GoogleAuth {
    const val CLOUD_SCOPE="https://www.googleapis.com/auth/cloud-platform"
    fun request(account: String? = null): AuthorizationRequest {
        val builder=AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(CLOUD_SCOPE)))
        if(!account.isNullOrBlank()) builder.setAccount(Account(account,"com.google"))
        else builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        return builder.build()
    }
    suspend fun token(context: Context,account: String): String = suspendCancellableCoroutine { c ->
        Identity.getAuthorizationClient(context).authorize(request(account)).addOnSuccessListener { result ->
            if(!c.isActive) return@addOnSuccessListener
            if(result.hasResolution() || result.accessToken.isNullOrBlank() || CLOUD_SCOPE !in result.grantedScopes) c.resumeWithException(LooiException("Google 授权需要更新，请在 AI 服务中重新授权", "Google authorization needs renewal; authorize again in AI services"))
            else c.resume(result.accessToken!!)
        }.addOnFailureListener { if(c.isActive) c.resumeWithException(LooiException("无法取得 Google 令牌，请重新授权", "Could not get a Google token; authorize again")) }
    }
}
