package com.booklistener.core

import android.content.Context
import com.booklistener.auth.CredentialStore
import com.booklistener.net.OkHttpHolder
import com.booklistener.source.WebDavCredential
import com.booklistener.source.normalizeWebDavUrl
import okhttp3.Credentials
import okhttp3.Request

/**
 * 按来源类型构造 [ScanCoordinator]（v2.2.0）。
 * 本地/网盘的差异只在 Source 与读取器实现，扫描流程一致。
 */
class ScanCoordinatorFactory(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val indexStore: EntryIndexStore,
    private val coverManager: CoverManager? = null
) {

    companion object {
        /** MP4 容器扩展名（ilst 原子树；含 alac——其容器仍是 mp4）；v2.2.4：m4b 同容器纳入 */
        val ILST_EXTS = setOf("m4a", "mp4", "alac", "m4b")

        /** v2.2.5（spec: m4b-moov-range-read）：moov 区间探测阈值 */
        private const val MOOV_HEAD_LIMIT = 1 shl 20          // 头部探测 1MB
        private const val MOOV_TAIL_LIMIT = 16L shl 20        // 尾部探测 16MB
    }

    /** 本地：SAF 列目录 + 新解析器（MetadataExtractor + Mp4IlstReader，唯一后端）+ SAF 读 cue + 内嵌封面提取 */
    fun forLocal(): ScanCoordinator {
        val metadataExtractor = MetadataExtractor()
        return ScanCoordinator(
            source = LocalSource(context),
            tagReader = { file -> resolveLocalTags(metadataExtractor, file) },
            cueTextReader = { file -> readSafText(file.path) },
            indexStore = indexStore,
            coverExtractor = coverManager?.let { mgr -> { uri -> mgr.extractCover(uri) } }
        )
    }

    /**
     * v2.2.0（spec: tag-fields-full）唯一解析后端：
     * - m4a/mp4/alac：Media3 Mp4Extractor（标准原子映射帧 + 时长）∪ 自研 Mp4IlstReader（扩展原子/原始键名）
     * - m4b：同上 + Mp4ChapterReader 章节（v2.2.4）
     * - 其余音频：Media3 提取器直驱（mp3/flac/ogg 全帧）
     * - 无 MediaMediaMetadataRetriever 回退；失败返回 null（文件行走 error + 文件名显示）
     * - v2.2.5（spec: m4b-moov-range-read）：MP4 容器只读 moov 区间，避免 1GB m4b 整读 OOM
     */
    private fun resolveLocalTags(extractor: MetadataExtractor, file: SourceFile): ResolvedTags? {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        // MP4 容器走 moov 区间读取（失败回退整读，保底不丢识别）；其余格式保持整读现状
        val bytes: ByteArray = if (ext in ILST_EXTS) {
            readMoovRangeBytes(file.path) ?: readSafBytes(file.path) ?: return null
        } else readSafBytes(file.path) ?: return null
        return if (ext in ILST_EXTS) {
            val m3 = extractor.extract(bytes, ext)
            // 合并：Media3 标准映射帧 + 自研扩展原子（键名不同体系，全保留）
            val entries = Mp4IlstReader.read(bytes) + m3.entries
            // v2.2.4：m4b 内嵌章节（iTunes 章节表优先 + chpl 兜底；无章节 → null）
            val chapters = if (ext == "m4b") {
                Mp4ChapterReader.read(bytes).takeIf { it.isNotEmpty() }
            } else null
            ResolvedTags.fromEntries(entries, m3.durationMs).copy(chapters = chapters)
        } else {
            val m3 = extractor.extract(bytes, ext)
            if (m3.entries.isEmpty() && m3.durationMs == null) null else ResolvedTags.fromEntries(m3.entries, m3.durationMs)
        }
    }

    /** 网盘：PROPFIND 列目录，无内嵌标签（tagReader 恒 null），GET 读 cue，无内嵌封面 */
    fun forWebDav(): ScanCoordinator {
        return ScanCoordinator(
            source = WebDavSource(credentialProvider = { url -> credentialFor(url) }),
            tagReader = { null },
            cueTextReader = { file -> readWebDavText(file.path) },
            indexStore = indexStore,
            coverExtractor = null
        )
    }

    private fun credentialFor(url: String): WebDavCredential? {
        val norm = normalizeWebDavUrl(url)
        return credentialStore.webDavAccounts().firstOrNull { norm.startsWith(it.normalizedUrl) }
    }

    private fun readSafText(uri: String): String? = runCatching {
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))
            ?.use { CueTextDecoder.decode(it.readBytes()) }
    }.getOrNull()

    private fun readSafBytes(uri: String): ByteArray? = runCatching {
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))
            ?.use { it.readBytes() }
    }.getOrNull()

    /**
     * v2.2.5（spec: m4b-moov-range-read）：MP4 容器只读 moov 区间。
     * 头部形态：读 [0, moovEnd)；尾部形态：读尾 16MB 定位 moov 区间。定位失败返回 null（调用方回退整读）。
     */
    private fun readMoovRangeBytes(uri: String): ByteArray? = runCatching {
        val afd = context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r") ?: return null
        afd.use {
            val length = it.length
            if (length <= 0) return null
            java.io.FileInputStream(it.fileDescriptor).use { fis ->
                val channel = fis.channel
                val headLen = minOf(length, MOOV_HEAD_LIMIT.toLong()).toInt()
                val headMoov = MoovLocator.headMoovRange(readExact(channel, 0, headLen))
                if (headMoov != null) {
                    val end = minOf(headMoov.last.toLong(), length).toInt()
                    return readExact(channel, 0, end)
                }
                // 尾部形态（非 faststart）：mdat 在前、moov 在文件末
                val tailLen = minOf(length, MOOV_TAIL_LIMIT).toInt()
                val tailStart = length - tailLen
                val tailMoov = MoovLocator.tailMoovRange(readExact(channel, tailStart, tailLen))
                    ?: return null
                val absStart = tailStart + tailMoov.first
                val absEnd = minOf(tailStart + tailMoov.last.toLong(), length).toInt()
                return readExact(channel, absStart, absEnd - absStart.toInt())
            }
        }
    }.getOrNull()

    /** 从 [channel] 的 [start] 起精确读 [len] 字节 */
    private fun readExact(channel: java.nio.channels.FileChannel, start: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        if (len == 0) return out
        val buf = java.nio.ByteBuffer.wrap(out)
        channel.position(start)
        var read = 0L
        while (read < len) {
            val n = channel.read(buf)
            if (n < 0) break
            read += n
        }
        return out
    }

    private fun readWebDavText(url: String): String? = runCatching {
        val cred = credentialFor(url) ?: return null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(cred.username, cred.password))
            .build()
        OkHttpHolder.client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) CueTextDecoder.decode(resp.body?.bytes() ?: ByteArray(0)) else null
        }
    }.getOrNull()
}
