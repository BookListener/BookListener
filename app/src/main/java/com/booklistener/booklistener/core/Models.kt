package com.booklistener.core

data class AudioItem(
    val id: String,
    val displayName: String,
    val path: String,
    val sourceType: SourceType,
    val size: Long,
    // v1.7.0：媒体标签/时长（可空；本地 SAF 解析、MediaStore 曲库有，网盘暂缺）
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val durationMs: Long? = null,
    /** 原始文件名含扩展名（文件列表展示用；网盘 source 出口保留原始名） */
    val rawName: String? = null
)

data class AudioMeta(
    val title: String,
    val artist: String,
    val durationMs: Long
)

/** 元数据缺省值（霰弹收敛 B3）：各数据源"无作者信息"兜底单点定义 */
object AudioMetaDefaults {
    const val UNKNOWN_ARTIST = "Unknown"
}

enum class SourceType {
    LOCAL, WEBDAV, ALIYUN
}

sealed class AudioSourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(message: String) : AudioSourceException(message)
    class NetworkLimit(message: String) : AudioSourceException(message)
    class NetworkError(message: String, cause: Throwable?) : AudioSourceException(message, cause)
    class NotFound(message: String) : AudioSourceException(message)
}

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<out T>(val data: T) : UiState<T>()
    data class Error(val msg: String) : UiState<Nothing>()
    object Empty : UiState<Nothing>()
}
