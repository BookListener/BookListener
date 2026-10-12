package com.booklistener.player

import android.util.Base64
import com.booklistener.auth.CredentialStore
import com.booklistener.source.normalizeWebDavUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * WebDAV 播放认证拦截器（v1.6.28）：
 * ExoPlayer 通过 OkHttpDataSource 拉流时请求是裸 URL，不带 Basic 认证（WebDAV 服务端必 401）。
 * 拦截器按 URL 前缀匹配已配置账号，为匹配的请求注入 Authorization: Basic。
 * 不匹配的请求（本地/其他 http）原样放行。
 * 多账号各自独立的播放认证由此解决；PROPFIND 请求自带 Authorization，不受影响。
 */
class WebDavAuthInterceptor(
    private val credentialStore: CredentialStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val cred = credentialStore.webDavAccounts()
            .firstOrNull { normalizeWebDavUrl(url).startsWith(it.normalizedUrl) }
        return if (cred != null) {
            val token = Base64.encodeToString(
                "${cred.username}:${cred.password}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            chain.proceed(
                request.newBuilder()
                    .header("Authorization", "Basic $token")
                    .build()
            )
        } else {
            chain.proceed(request)
        }
    }
}
