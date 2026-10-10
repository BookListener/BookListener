package com.booklistener.auth

import android.content.Context
import android.net.Uri
import com.booklistener.core.AliyunConfig
import com.booklistener.core.AudioSourceException
import com.booklistener.net.OkHttpHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 阿里云盘 OAuth2 PKCE 授权管理。
 * 客户端生成 code_verifier/code_challenge，浏览器授权后拿 code 换 token，
 * 全程不需要 client_secret，适合开源客户端分发。
 */
class AliyunAuthManager(private val context: Context) {

    private val store = CredentialStore(context)

    /** 生成 PKCE 参数并返回授权跳转 URL */
    fun buildAuthorizeUrl(): String {
        val verifier = generateCodeVerifier()
        val challenge = codeChallenge(verifier)
        // verifier 缓存到内存，授权回调后使用（进程存活期间）
        pendingVerifier = verifier
        return Uri.parse(AliyunConfig.AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", store.aliyunClientId())
            .appendQueryParameter("redirect_uri", AliyunConfig.REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", AliyunConfig.SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /** 授权回调：用 code + code_verifier 换取 access_token / refresh_token */
    suspend fun exchangeCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val verifier = pendingVerifier ?: throw AudioSourceException.AuthFailed("missing code_verifier")
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", store.aliyunClientId())
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", AliyunConfig.REDIRECT_URI)
                .build()
            val request = Request.Builder().url(AliyunConfig.TOKEN_URL).post(body).build()
            OkHttpHolder.client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AudioSourceException.AuthFailed("token exchange failed: ${resp.code} $text")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token")
                val refresh = json.optString("refresh_token")
                val expiresIn = json.optLong("expires_in", 7200L)
                val driveId = json.optString("default_drive_id", "root")
                if (refresh.isEmpty()) throw AudioSourceException.AuthFailed("no refresh_token in response")
                store.setAliyunTokens(
                    accessToken = access.ifEmpty { null },
                    refreshToken = refresh,
                    expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
                )
                store.setDriveId(driveId)
                pendingVerifier = null
            }
        }
    }

    /** 获取有效 access_token，过期自动用 refresh_token 刷新 */
    suspend fun getAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = store.aliyunAccessToken()
            if (!existing.isNullOrEmpty() && store.aliyunExpiresAt() > System.currentTimeMillis()) {
                return@runCatching existing
            }
            val refresh = store.aliyunRefreshToken()
                ?: throw AudioSourceException.AuthFailed("not authorized")
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", store.aliyunClientId())
                .add("refresh_token", refresh)
                .build()
            val request = Request.Builder().url(AliyunConfig.TOKEN_URL).post(body).build()
            OkHttpHolder.client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    store.clearAliyun()
                    throw AudioSourceException.AuthFailed("refresh failed: ${resp.code}")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token")
                val newRefresh = json.optString("refresh_token").ifEmpty { refresh }
                val expiresIn = json.optLong("expires_in", 7200L)
                if (access.isEmpty()) throw AudioSourceException.AuthFailed("empty access_token")
                store.setAliyunTokens(access, newRefresh, System.currentTimeMillis() + expiresIn * 1000L)
                access
            }
        }
    }

    fun isAuthorized(): Boolean = !store.aliyunRefreshToken().isNullOrEmpty()

    fun driveId(): String = store.driveId()

    private var pendingVerifier: String? = null

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
