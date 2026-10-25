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
        /** MP4 容器扩展名（ilst 原子树；含 alac——其容器仍是 mp4） */
        val ILST_EXTS = setOf("m4a", "mp4", "alac")
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
     * - 其余音频：Media3 提取器直驱（mp3/flac/ogg 全帧）
     * - 无 MediaMetadataRetriever 回退；失败返回 null（文件行走 error + 文件名显示）
     */
    private fun resolveLocalTags(extractor: MetadataExtractor, file: SourceFile): ResolvedTags? {
        val bytes = readSafBytes(file.path) ?: return null
        val ext = file.name.substringAfterLast('.', "")
        return if (ext.lowercase() in ILST_EXTS) {
            val m3 = extractor.extract(bytes, ext)
            // 合并：Media3 标准映射帧 + 自研扩展原子（键名不同体系，全保留）
            val entries = Mp4IlstReader.read(bytes) + m3.entries
            ResolvedTags.fromEntries(entries, m3.durationMs)
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
