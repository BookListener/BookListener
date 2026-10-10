package com.booklistener.net

import com.booklistener.core.HttpConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 全局 OkHttp 单例，统一超时、429 指数退避重试。
 * 所有网盘适配器复用同一实例。
 */
object OkHttpHolder {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(HttpConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(HttpConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RateLimitInterceptor())
            .build()
    }
}

/**
 * 429 指数退避拦截器：
 * 收到 429 时依次等待 1s/2s/4s 后重试，最多 RETRY_MAX 次。
 * 用于规避百度/阿里等网盘 API 的全局限流。
 */
class RateLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var delayMs = HttpConfig.RETRY_BASE_DELAY_MS
        while (true) {
            val response = chain.proceed(chain.request())
            if (response.code == 429 && attempt < HttpConfig.RETRY_MAX) {
                response.close()
                attempt++
                runBlocking { delay(delayMs) }
                delayMs *= 2
            } else {
                return response
            }
        }
    }
}
