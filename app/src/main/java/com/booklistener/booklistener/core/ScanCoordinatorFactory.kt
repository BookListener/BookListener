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

        /** v2.2.7（spec: m4b-moov-range-read）：流式定位失败时整读回退的文件大小上限（防大文件 OOM） */
        private const val MOOV_FALLBACK_LIMIT = 64L * 1024 * 1024
        private const val MAX_MOOV_BYTES = 128L * 1024 * 1024
    }

    /** 本地：SAF 列目录 + 新解析器（MetadataExtractor + Mp4IlstReader，唯一后端）+ SAF 读 cue + 内嵌封面提取 */
    fun forLocal(): ScanCoordinator {
        val metadataExtractor = MetadataExtractor()
        return ScanCoordinator(
            source = LocalSource(context),
            tagReader = { file -> resolveLocalTags(metadataExtractor, file) },
            cueTextReader = { file -> readSafText(file.path) },
            indexStore = indexStore,
            // v2.2.8：封面回填优先用扫描时自研 covr 提取结果（coverUriByUri，零额外 IO、
            // 不依赖 MediaMetadataRetriever 对 615MB 级 m4b 的慢速/不稳提取）；
            // 非 MP4 或 covr 缺失回退 MediaMetadataRetriever。
            coverExtractor = { uri ->
                val real = VirtualTrackUri.parse(uri)?.first ?: uri
                coverUriByUri[real] ?: coverManager?.extractCover(uri)
            },
            errorDetailProvider = { uri -> diagnostics[uri] }
        )
    }

    /** v2.2.6：扫描诊断（uri → 文本；内容文件列表点信息图标弹窗展示，不入库） */
    private val diagnostics = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** v2.2.8：自研 covr 提取结果缓存（整轨 uri → 封面 file:// uri；扫描回填直接用） */
    private val coverUriByUri = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        val isMp4 = ext in ILST_EXTS
        val read = if (isMp4) readMoovRangeBytes(file.path) else MoovRead(null, "非 MP4 容器（整读）")
        val bytes: ByteArray = read.bytes ?: run {
            // v2.2.7：大文件（>64MB）定位失败不再整读（整读必然 OOM，v2.2.6 实测 615MB 失败）；
            // 小文件保留整读回退（保底不丢识别）
            val size = file.sizeBytes ?: 0L
            if (size > MOOV_FALLBACK_LIMIT) {
                diagnostics[file.path] =
                    "tag parse failed\n文件: ${file.name}\n大小: ${file.sizeBytes ?: "?"} B\n${read.detail}\n整读回退跳过（>64MB 防 OOM）"
                return null
            }
            val fb = readSafBytes(file.path)
            if (fb == null) {
                diagnostics[file.path] =
                    "tag parse failed\n文件: ${file.name}\n大小: ${file.sizeBytes ?: "?"} B\n${read.detail}\n整读失败"
                return null
            }
            fb
        }
        val diagBase =
            "tag parse failed\n文件: ${file.name}\n大小: ${file.sizeBytes ?: "?"} B\n${read.detail}\n读取: ${bytes.size} B"
        return if (isMp4) {
            val m3 = extractor.extract(bytes, ext)
            // 合并：Media3 标准映射帧 + 自研扩展原子（键名不同体系，全保留）
            val entries = Mp4IlstReader.read(bytes) + m3.entries
            // v2.2.4：m4b 内嵌章节（iTunes 章节表优先 + chpl 兜底；无章节 → null）
            val chapters = if (ext == "m4b") {
                Mp4ChapterReader.read(bytes).takeIf { it.isNotEmpty() }
            } else null
            // v2.2.8：自研 covr 字节提取 → 写封面文件 → 缓存（专辑行/系列分组封面回填用）
            Mp4IlstReader.readCover(bytes)?.let { covr ->
                coverManager?.saveCoverBytes(file.path, covr.mime, covr.bytes)?.let {
                    coverUriByUri[file.path] = it
                }
            }
            val firstChapter = chapters?.firstOrNull()
            diagnostics[file.path] = "$diagBase\n解析: ilst=${entries.size} dur=${m3.durationMs} chapters=${chapters?.size ?: 0} 首章=${firstChapter?.title?.take(24) ?: "无"}"
            ResolvedTags.fromEntries(entries, m3.durationMs).copy(chapters = chapters)
        } else {
            val m3 = extractor.extract(bytes, ext)
            diagnostics[file.path] = "$diagBase\n解析: dur=${m3.durationMs} entries=${m3.entries.size}"
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

    /** v2.2.6：moov 区间读取结果（bytes 为 null = 定位失败，由调用方回退整读） */
    private class MoovRead(val bytes: ByteArray?, val detail: String)

    /**
     * v2.2.7（spec: m4b-moov-range-read）：MP4 容器只读 moov 区间。
     * 流式 box 链遍历定位 moov（文件任意位置，mdat 直接跳过，不读音频数据）；
     * 命中后只读 moov 区间字节。定位失败 bytes=null（调用方按文件大小决定是否回退整读）。
     */
    private fun readMoovRangeBytes(uri: String): MoovRead = runCatching {
        val afd = context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r")
            ?: return MoovRead(null, "openAssetFileDescriptor 失败")
        afd.use {
            val length = it.length
            if (length <= 0) return MoovRead(null, "文件长度=$length")
            java.io.FileInputStream(it.fileDescriptor).use { fis ->
                val channel = fis.channel
                val head = readExact(channel, 0, minOf(length, 64L).toInt())
                val headHex = head.take(48).joinToString("") { "%02x".format(it) }
                val range = MoovLocator.streamMoovRange(channel, length)
                if (range != null) {
                    val moovLen = range.last - range.first
                    // v2.2.16：moov 区间大小上限——定位器误判（随机数据碰巧构成伪 moov + 巨大 size）
                    // 时 range 会异常巨大，readExact 试图读数百 MB → 卡 + 内存暴涨；moov 正常几 MB~
                    // 几十 MB，超过 128MB 视为误判，放弃本次解析（走回退/记 error），不再读。
                    if (moovLen > MAX_MOOV_BYTES) {
                        return MoovRead(
                            null,
                            "moov 区间过大(${moovLen}B)疑似误判，放弃读取\n头部hex: $headHex"
                        )
                    }
                    val bytes = readExact(channel, range.first, moovLen.toInt())
                    return MoovRead(
                        bytes,
                        "moov: 流式命中 range=${range.first}..${range.last}（len=${bytes.size}B）\n头部hex: $headHex"
                    )
                }
                MoovRead(
                    null,
                    "moov: 流式遍历未命中（文件 ${length}B）\n头部hex: $headHex"
                )
            }
        }
    }.getOrElse { MoovRead(null, "读取异常: ${it.message}") }

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
