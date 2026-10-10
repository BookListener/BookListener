package com.booklistener.core

object AudioExt {
    val SUPPORTED = listOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "alac")

    fun isAudio(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED
    }
}

object CacheConfig {
    const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024 * 1024 // 5GB
}

object HttpConfig {
    const val CONNECT_TIMEOUT_SEC = 15L
    const val READ_TIMEOUT_SEC = 30L
    const val RETRY_MAX = 3
    const val RETRY_BASE_DELAY_MS = 1_000L
}

object AliyunConfig {
    // 用户在阿里云开放平台注册应用 client_id（可运行时通过设置修改）
    const val DEFAULT_CLIENT_ID = "YOUR_ALIYUN_CLIENT_ID"
    const val AUTHORIZE_URL = "https://openapi.alipan.com/oauth/authorize"
    const val TOKEN_URL = "https://openapi.alipan.com/oauth/token"
    const val FILE_LIST_URL = "https://openapi.alipan.com/adrive/v1.0/openFile/list"
    const val DOWNLOAD_URL = "https://openapi.alipan.com/adrive/v1.0/openFile/getDownloadUrl"
    const val REDIRECT_URI = "booklistener://oauth/callback"
    const val SCOPE = "file:list:read file:content:read"
}
