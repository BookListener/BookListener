package com.booklistener.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * 音频标签解析（v1.6.0，参照 Voice 的 tag 解析设计，差异化实现）：
 * - 用平台 MediaMetadataRetriever（CoverManager 同源，零新依赖）抽取
 *   title / artist / album / track / disc（MP3 ID3 / FLAC / M4A 等平台支持）；
 * - **只 title 回退文件名**（`title ?: 去扩展名文件名`），artist/album 缺失保持 null；
 * - 解析失败静默回退文件名，不崩溃、不影响其它字段；
 * - 提取器通过 [extractor] 注入（默认平台实现），纯回退/排序逻辑可单测。
 *
 * 作用域边界（用户确认 Q3）：仅本地 SAF 文件解析；网盘文件不解析（逐文件网络拉流
 * 开销大、失败率高），保持文件名显示 + 自然排序。
 */
class TagResolver(
    private val context: Context,
    private val extractor: (Uri) -> AudioTags? = { uri -> defaultExtract(context, uri) }
) {

    /** 解析出的标签（全部可为 null，title 回退由调用方负责）；durationMs 为毫秒 */
    data class AudioTags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val track: Int?,
        val disc: Int?,
        val durationMs: Long? = null
    )

    /** 解析标签；失败返回 null（调用方按文件名回退） */
    fun resolve(uri: Uri): AudioTags? = runCatching { extractor(uri) }.getOrNull()

    /** 显示名：tag title（非空白）优先，否则去扩展名文件名（".mp3" 形式保留原名） */
    fun displayNameFor(uri: Uri, fileName: String): String {
        val title = resolve(uri)?.title?.takeIf { it.isNotBlank() }
        if (title != null) return title
        val nameWithoutExt = fileName.substringBeforeLast('.')
        return nameWithoutExt.takeUnless { it.isEmpty() } ?: fileName
    }

    companion object {
        /** 平台默认提取：MediaMetadataRetriever（本地文件，IO 线程调用） */
        fun defaultExtract(context: Context, uri: Uri): AudioTags? {
            val retriever = MediaMetadataRetriever()
            var released = false
            try {
                retriever.setDataSource(context, uri)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() }
                // CD_TRACK/DISC：平台返回 "3/10"（曲目/总数）或 "3"，取前段
                val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull()
                val disc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull()
                // 时长：平台返回毫秒字符串（v1.7.0 落库展示用）
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0L }
                released = true
                retriever.release()
                return AudioTags(title, artist, album, track, disc, durationMs)
            } finally {
                if (!released) runCatching { retriever.release() }
            }
        }
    }
}
