package com.booklistener.core

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File


/**
 * 封面管理（独立类，v1.5.10 抽象）：
 * 统一负责"条目封面与作者"的解析策略与缓存，供 ViewModel/列表/详情页共用。
 * 解析优先级：文件夹内常见命名图片（CoverScanner）→ 首曲内嵌封面（MediaMetadataRetriever，
 * 写入 cacheDir/covers 并返回 file:// uri）→ 无封面（null，由 UI 回退渐变占位）。
 */
class CoverManager(
    private val app: Application,
    private val coverScanner: CoverScanner
) {

    /** 解析文件夹条目的封面 uri：文件夹图片优先，否则取首曲内嵌封面 */
    fun resolveFolderCover(treeUri: String, firstAudioPath: String?): String? {
        val folderImage = runCatching { coverScanner.findCover(treeUri) }.getOrNull()
        if (folderImage != null) return folderImage
        return firstAudioPath?.let { extractEmbeddedCover(it) }
    }

    /**
     * 专辑封面规则（v1.6.3 修复"当前专辑没封面却用了其他专辑图"）：
     * - 来源条目只聚合出 1 个专辑（单专辑文件夹）→ 用文件夹封面（entry.coverUri，零 IO）
     * - 条目聚合出多个专辑 → 只用该专辑内文件的内嵌封面（首曲，磁盘缓存），解析不到返回 null（绝不串图）
     * - 返回 null 时由 UI 回退渐变占位
     */
    fun resolveAlbumCover(entryCoverUri: String?, albumCountInEntry: Int, firstAudioUri: String?): String? =
        if (albumCountInEntry <= 1) entryCoverUri
        else firstAudioUri?.let { extractEmbeddedCover(it) }

    /** 解析首曲作者信息（无则 null，用于条目作者行展示） */
    fun resolveArtist(firstAudioPath: String?): String? =
        firstAudioPath?.let { extractArtist(it) }

    /** 用 MediaMetadataRetriever 提取音频内嵌封面，写入 cacheDir/covers/{id}.jpg 返回 file:// uri */
    private fun extractEmbeddedCover(audioUriStr: String): String? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            var released = false
            try {
                val fd = app.contentResolver
                    .openAssetFileDescriptor(Uri.parse(audioUriStr), "r")
                    ?: return null
                retriever.setDataSource(fd.fileDescriptor)
                val art = retriever.embeddedPicture ?: return null
                val coverDir = CacheDirs.coverCacheDir(app)
                coverDir.mkdirs()
                val file = File(coverDir, sanitize(coverDir, audioUriStr) + ".jpg")
                file.writeBytes(art)
                released = true
                retriever.release()
                Uri.fromFile(file).toString()
            } finally {
                if (!released) runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    /** 提取首曲作者信息（无则 null） */
    private fun extractArtist(audioUriStr: String): String? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            var released = false
            try {
                val fd = app.contentResolver
                    .openAssetFileDescriptor(Uri.parse(audioUriStr), "r")
                    ?: return null
                retriever.setDataSource(fd.fileDescriptor)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                released = true
                retriever.release()
                artist?.takeIf { it.isNotBlank() }
            } finally {
                if (!released) runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    private fun sanitize(dir: File, uriStr: String): String {
        val base = dir.absolutePath.hashCode().toString() + uriStr.hashCode().toString()
        return base
    }
}
