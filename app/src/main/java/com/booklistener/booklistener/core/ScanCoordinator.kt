package com.booklistener.core

import com.booklistener.db.EntryTagExtraEntity

/**
 * 扫描协调器（v2.2.0 设置侧链路重构）。
 *
 * 统一调度"列目录 → 标签解析 → CUE 收集 → 写索引 → 作品聚合"，
 * 所有降级逻辑集中在此，来源无关。本地/网盘只差异在 [Source] 与读取器实现。
 *
 * 非音频文件（含 .cue）也入库，保证内容文件列表忠实显示所有文件。
 *
 * v2.2.0（spec: tag-fields-full）：tagReader 返回 [ResolvedTags]（结构化字段 + 全量键值条目），
 * 全量条目写入 entry_tag_extras（文档字段原始键值不丢）。
 */
class ScanCoordinator(
    private val source: Source,
    /** 读音频标签（本地 MetadataExtractor + Mp4IlstReader；网盘无标签返回 null）；失败应返回 null */
    private val tagReader: suspend (SourceFile) -> ResolvedTags?,
    /** 读 .cue 文本（本地 SAF / 网盘 GET，已做编码探测）；失败返回 null */
    private val cueTextReader: suspend (SourceFile) -> String?,
    private val indexStore: EntryIndexStore,
    /** v2.1.15：提取音频内嵌封面（null 表示不提取作品封面，网盘无内嵌封面传 null） */
    private val coverExtractor: (suspend (audioUri: String) -> String?)? = null,
    /** v2.2.6：扫描失败诊断详情提供者（按 uri 取诊断文本，点信息图标弹窗查看） */
    private val errorDetailProvider: (uri: String) -> String? = { null }
) {

    // v2.2.17：单个文件的标签解析线程池 + 超时——扫描绝不因一个文件的 SAF 读取/解析
    // 死循环或极慢而整体卡死；超时文件先跳过（记"解析超时（将重试）"），主循环结束后
    // 第二轮用更长超时只重试超时文件（v2.2.21：慢 ≠ 失败，超时不再直接判死）。
    private val tagExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(2)

    // v2.2.21：超时分档——按文件大小给 IO 定位/读取留出合理时间（小文件 3s、中 10s、大 30s），
    // 避免 615MB 级 moov 尾部文件流式定位在慢介质上被误判失败
    private fun timeoutFor(sizeBytes: Long?): Long = when {
        sizeBytes == null -> TAG_TIMEOUT_MEDIUM
        sizeBytes <= 5L * 1024 * 1024 -> TAG_TIMEOUT_SMALL
        sizeBytes <= 64L * 1024 * 1024 -> TAG_TIMEOUT_MEDIUM
        else -> TAG_TIMEOUT_LARGE
    }

    companion object {
        // v2.1.15：跳过过小/过短的音频文件（铃声/通知音/系统音效）
        const val MIN_AUDIO_SIZE_BYTES = 100L * 1024L   // 100 KB
        const val MIN_AUDIO_DURATION_MS = 30L * 1000L    // 30 秒

        // v2.2.21：单文件标签解析超时（毫秒）——第一轮分档，第二轮重试放宽
        private const val TAG_TIMEOUT_SMALL = 3_000L
        private const val TAG_TIMEOUT_MEDIUM = 10_000L
        private const val TAG_TIMEOUT_LARGE = 30_000L
        private const val TAG_TIMEOUT_RETRY = 60_000L
    }

    /** 单文件标签解析结果（v2.2.21：区分"成功 / 快速失败 / 超时未完成"） */
    private sealed class TagResult {
        class Ok(val tags: ResolvedTags) : TagResult()
        object Failed : TagResult()
        object TimedOut : TagResult()
    }

    /** 扫描结果（v2.2.21）：失败清单供进度模块驻留展示 + 手动重试 */
    data class ScanResult(
        val failedFiles: List<ScanFailedFile>,
        val total: Int
    )

    /** 失败文件（第二轮仍失败的）：detail = 完整诊断文本（errorDetailProvider） */
    data class ScanFailedFile(
        val uri: String,
        val name: String,
        val detail: String
    )

    /** 单文件手动重扫结果 */
    data class RescanFileResult(
        val success: Boolean,
        val detail: String?,
        val displayName: String? = null
    )

    /**
     * 全量扫描并写索引。
     * @param entryId 条目标识（写索引的 key；本地 SAF tree uri / 网盘 "webdav://..." ）
     * @param rootUri 列目录的根标识（本地 SAF tree uri / 网盘完整 URL）
     */
    suspend fun scan(
        entryId: String,
        rootUri: String = entryId,
        /**
         * v2.2.13：扫描进度回调 (已处理, 总数, 当前文件名)。
         * v2.2.21：追加 retryingCount（>0 表示进入第二轮超时重试，回调 currentName 为正在重试的文件）。
         * 仅文件处理阶段触发；列目录阶段不触发。
         */
        onProgress: ((done: Int, total: Int, currentName: String, retryingCount: Int) -> Unit)? = null
    ): ScanResult {
        val sourceFiles = source.listFiles(rootUri)
        // v2.1.15：过滤目录 + 隐藏文件（. 开头，含文件名和 relativePath 各路径段）；
        // CUE/封面图片不受音频大小/时长过滤影响
        val files = sourceFiles.filterNot { f ->
            f.isDirectory ||
                f.name.startsWith(".") ||
                f.relativePath.split('/').any { it.startsWith(".") }
        }

        val indexed = mutableListOf<IndexedFile>()
        val cueCandidates = mutableListOf<CueIndexer.CueCandidate>()
        // v2.2.0：每文件标签全量条目（uri → extras），扫描末随索引整体替换
        val extrasByUri = LinkedHashMap<String, List<EntryTagExtraEntity>>()
        // v2.2.21：第一轮超时的文件（主循环结束后第二轮用更长超时只重试这些）
        val timedOutFiles = mutableListOf<SourceFile>()
        val failedFiles = mutableListOf<ScanFailedFile>()

        var processed = 0
        for (file in files) {
            processed++
            onProgress?.invoke(processed, files.size, file.name, 0)
            // v2.2.11：目录树访问失败的诊断行（来自 Source 递归列目录），
            // 作为内容文件入索引展示错误，避免"添加父目录一个都扫不到且无任何提示"
            if (file.scanError != null) {
                indexed += file.toIndexedFile(error = "目录访问失败: ${file.scanError}")
                continue
            }
            when {
                file.extension == "cue" -> {
                    // CUE：读文本；失败记 error，仍入库（点击可看失败原因）
                    val text = runCatching { cueTextReader(file) }.getOrNull()
                    if (text != null) {
                        cueCandidates += CueIndexer.CueCandidate(
                            cueUri = file.path,
                            displayName = file.name,
                            text = text
                        )
                        indexed += file.toIndexedFile(error = null)
                    } else {
                        indexed += file.toIndexedFile(error = "cue read failed")
                    }
                }
                AudioExt.isAudio(file.name) -> {
                    // v2.1.14：跳过过小的音频文件（铃声/通知音/系统音效），不入库
                    val size = file.sizeBytes
                    if (size != null && size in 1..<MIN_AUDIO_SIZE_BYTES) {
                        continue
                    }
                    // v2.2.21：三态解析——Ok 正常入库；TimedOut 先按文件名兜底入库并标记
                    // "解析超时（将重试）"，主循环结束后第二轮用更长超时重试；Failed 记 error。
                    when (val r = tryReadTags(file, timeoutFor(size))) {
                        is TagResult.Ok -> {
                            val dur = r.tags.durationMs
                            if (dur != null && dur in 1..<MIN_AUDIO_DURATION_MS) {
                                continue
                            }
                            indexed += buildRowsForTagged(file, r.tags, entryId, extrasByUri)
                        }
                        is TagResult.TimedOut -> {
                            timedOutFiles += file
                            indexed += file.toIndexedFile(error = RETRY_PENDING_ERROR)
                        }
                        is TagResult.Failed -> {
                            // 标签解析失败：文件名兜底，归入未知专辑
                            // v2.2.6：error 首行保持 "tag parse failed"（列表副标题显示），
                            // 其后附加扫描诊断（点信息图标弹窗查看完整内容；error 已入库，列表重载后仍可查看）
                            indexed += file.toIndexedFile(
                                error = errorDetailProvider(file.path) ?: "tag parse failed"
                            )
                            // v2.2.21：快速失败（解析执行完毕但无结果）也进失败清单——
                            // 失败清单 = 本次扫描全部失败文件（驻留展示 + 手动重试）
                            failedFiles += ScanFailedFile(
                                uri = file.path,
                                name = file.name,
                                detail = errorDetailProvider(file.path) ?: "tag parse failed"
                            )
                        }
                    }
                }
                else -> {
                    // 非音频非 cue：仍入库（内容文件列表忠实显示），不做标签解析
                    indexed += file.toIndexedFile(error = null)
                }
            }
        }

        // v2.2.21：第二轮——主循环已结束、线程池空闲，用更长超时只重试第一轮超时文件。
        // 成功 → 替换入库行（含可能的 m4b 章节虚拟轨）；仍失败/超时 → 进失败清单（供进度模块驻留 + 手动重试）。
        for (f in timedOutFiles) {
            onProgress?.invoke(processed, files.size, f.name, timedOutFiles.size)
            when (val r = tryReadTags(f, TAG_TIMEOUT_RETRY)) {
                is TagResult.Ok -> {
                    val rows = buildRowsForTagged(f, r.tags, entryId, extrasByUri)
                    indexed.removeAll { it.uri == f.path }
                    indexed.addAll(rows)
                }
                else -> {
                    failedFiles += ScanFailedFile(
                        uri = f.path,
                        name = f.name,
                        detail = errorDetailProvider(f.path) ?: "tag parse failed"
                    )
                }
            }
        }

        // 出口统一排序（disc → track → 名称自然序），列表顺序稳定
        val sorted = sortIndexed(indexed)
        indexStore.save(entryId, sorted, cueCandidates, extrasByUri)

        // v2.1.15：扫描后为每个作品提取首曲内嵌封面入库（专辑列表零 IO 显示封面）
        // v2.1.23：目录图片封面——专辑全部文件在同一目录时优先用该目录的图片，
        // 无目录图片或跨目录专辑回退首曲内嵌封面（防跨目录串图）。
        coverExtractor?.let { extractor ->
            val folderName = indexStore.getFolderName(entryId) ?: entryId
            // 按 workKey 分组，取每组第一首音频 uri（排序后第一首即 disc/track 最前）
            val firstAudioByKey = LinkedHashMap<String, String>()
            // v2.1.23：同一作品的文件目录集合（relativePath 目录部分，根目录=""）
            val dirsByKey = LinkedHashMap<String, MutableSet<String>>()
            for (f in sorted) {
                // v2.2.8：章节/CUE 虚拟轨也计入（rawName 形如 "整轨.m4b#1"，isAudio 对 rawName
                // 判扩展名失败 → 必须显式放行虚拟轨；封面在整轨上，extractor 内部解析，目录不影响判定）
                if (!AudioExt.isAudio(f.rawName ?: f.uri) && !VirtualTrackUri.isVirtual(f.uri)) continue
                // 虚拟轨也计入（封面在整轨上，extractor 内部解析；虚拟轨与整轨同目录，不影响目录判定）
                val key = WorkBackfill.workKey(entryId, folderName, f.album, f.albumArtist, f.artist)
                if (!firstAudioByKey.containsKey(key)) {
                    firstAudioByKey[key] = f.uri
                }
                dirsByKey.getOrPut(key) { mutableSetOf() } += f.relativePath ?: ""
            }
            // 目录图片优先（仅当作品全部文件在同一目录），否则首曲内嵌
            val covers = mutableMapOf<String, String>()
            for ((key, audioUri) in firstAudioByKey) {
                val dirs = dirsByKey[key].orEmpty()
                val dirCover = if (dirs.size == 1) findDirCover(files, dirs.first()) else null
                val cover = dirCover ?: runCatching { extractor(audioUri) }.getOrNull()
                cover?.let { covers[key] = it }
            }
            if (covers.isNotEmpty()) {
                indexStore.updateWorkCovers(covers)
            }
        }

        return ScanResult(failedFiles, files.size)
    }

    /**
     * v2.1.23：在扫描文件列表的指定目录（relativePath 精确匹配）内查找封面图片。
     * 优先级与 CoverScanner.findCover 一致：常见命名（cover/folder/front/AlbumArt*）→ 任意图片。
     * 基于已扫描的文件列表，零额外 IO；本地与 WebDAV 统一生效。
     */
    private fun findDirCover(files: List<SourceFile>, dir: String): String? {
        val inDir = files.filter { it.relativePath == dir }
        for (f in inDir) {
            val name = f.name.lowercase()
            if (name in CoverScanner.PREFERRED_NAMES) return f.path
        }
        for (f in inDir) {
            if (CoverScanner.isImage(f.name.lowercase())) return f.path
        }
        return null
    }

    private fun SourceFile.toIndexedFile(error: String?, errorDetail: String? = null): IndexedFile = IndexedFile(
        displayName = name.substringBeforeLast('.').takeUnless { it.isEmpty() } ?: name,
        uri = path,
        rawName = name,
        sizeBytes = sizeBytes,
        relativePath = relativePath,
        error = error,
        errorDetail = errorDetail
    )

    /** v2.2.21：第一轮超时文件的入库标记（列表副标题显示；第二轮成功即被替换） */
    private val RETRY_PENDING_ERROR = "解析超时（将重试）"

    /**
     * v2.2.21：单文件标签解析（三态）。tagReader 是 suspend，在 worker 线程 runBlocking 执行；
     * 超时 cancel(true) 中断线程（IO 阻塞会抛中断异常，线程可回收；纯 CPU 死循环无法中断，
     * 靠 2 线程池兜底）。区分超时（本次未完成，可重试）与快速失败（解析执行完毕但返回 null）。
     */
    private suspend fun tryReadTags(file: SourceFile, timeoutMs: Long): TagResult = try {
        val fut: java.util.concurrent.Future<ResolvedTags?> =
            tagExecutor.submit(java.util.concurrent.Callable<ResolvedTags?> {
                kotlinx.coroutines.runBlocking { tagReader(file) }
            })
        try {
            val tags = fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (tags != null) TagResult.Ok(tags) else TagResult.Failed
        } catch (e: java.util.concurrent.TimeoutException) {
            fut.cancel(true)
            TagResult.TimedOut
        } catch (e: java.util.concurrent.ExecutionException) {
            TagResult.Failed
        }
    } catch (e: Exception) {
        TagResult.Failed
    }

    /**
     * v2.2.21：由成功解析结果构造入库行（普通媒体单行；m4b 有章节 → 章节虚拟轨 + 原始 .m4b 行，
     * 与主循环同一逻辑，第一轮 / 第二轮重试 / 单文件手动重扫共用），并写入标签扩展键值。
     */
    private fun buildRowsForTagged(
        file: SourceFile,
        tags: ResolvedTags,
        entryId: String,
        extrasByUri: MutableMap<String, List<EntryTagExtraEntity>>
    ): List<IndexedFile> {
        tags.entries.takeIf { it.isNotEmpty() }?.let { entries ->
            extrasByUri[file.path] = entries.map { e ->
                EntryTagExtraEntity(
                    entryId = entryId,
                    uri = file.path,
                    key = e.key,
                    value = e.value,
                    source = e.source
                )
            }
        }
        val rows = mutableListOf<IndexedFile>()
        val chapters = tags.chapters.orEmpty()
        if (chapters.isNotEmpty() && file.extension.equals("m4b", ignoreCase = true)) {
            // v2.2.4（spec: m4b-audiobook-chapters）：有章节 → 章节虚拟轨 + 原始 .m4b 行（v2.2.20）
            val wholeDuration = tags.durationMs
            chapters.forEachIndexed { i, ch ->
                val nextStart = chapters.getOrNull(i + 1)?.startMs
                rows += IndexedFile(
                    displayName = ch.title.ifBlank { "第 ${i + 1} 章" },
                    uri = "${file.path}#track_${ch.startMs}",
                    album = tags.album,
                    artist = tags.artist,
                    track = i + 1,
                    disc = tags.disc,
                    durationMs = when {
                        nextStart != null -> nextStart - ch.startMs
                        wholeDuration != null -> wholeDuration - ch.startMs
                        else -> null
                    },
                    rawName = "${file.name}#${i + 1}",
                    sizeBytes = null,
                    relativePath = file.relativePath,
                    error = null,
                    albumArtist = tags.albumArtist,
                    genre = tags.genre,
                    year = tags.year,
                    composer = tags.composer,
                    series = tags.series,
                    seriesNumber = tags.seriesNumber,
                    mediaType = tags.mediaType,
                    encoder = tags.encoder
                )
            }
            // v2.2.20：原始 .m4b 正常入库（内容文件列表显示原始文件）；作品聚合时
            // 由 WorkBackfill.buildWorks 的「虚拟轨整轨源排除」挡在曲目列表外。
            rows += IndexedFile(
                displayName = file.name,
                uri = file.path,
                album = tags.album,
                artist = tags.artist,
                rawName = file.name,
                sizeBytes = file.sizeBytes,
                relativePath = file.relativePath,
                error = null,
                albumArtist = tags.albumArtist,
                genre = tags.genre,
                year = tags.year,
                composer = tags.composer,
                series = tags.series,
                seriesNumber = tags.seriesNumber,
                mediaType = tags.mediaType,
                encoder = tags.encoder
            )
        } else {
            rows += IndexedFile(
                displayName = tags.title?.takeIf { it.isNotBlank() }
                    ?: file.name.substringBeforeLast('.').takeUnless { it.isEmpty() }
                    ?: file.name,
                uri = file.path,
                album = tags.album,
                artist = tags.artist,
                track = tags.track,
                disc = tags.disc,
                durationMs = tags.durationMs,
                rawName = file.name,
                sizeBytes = file.sizeBytes,
                relativePath = file.relativePath,
                error = null,
                albumArtist = tags.albumArtist,
                genre = tags.genre,
                year = tags.year,
                composer = tags.composer,
                series = tags.series,
                seriesNumber = tags.seriesNumber,
                mediaType = tags.mediaType,
                encoder = tags.encoder
            )
        }
        return rows
    }

    /**
     * v2.2.21：单文件手动重扫（失败清单的「重试」入口）——只重新解析这一个文件。
     * 重新列目录定位该 uri（保证文件真实存在）；解析超时放宽到 60s（用户主动重试愿意等）。
     * 成功 → 替换该行（含可能的 m4b 章节虚拟轨）+ 合并标签扩展键值 → 整体入库重聚合；
     * 失败 → 返回完整诊断文本（errorDetailProvider 的 moov/ilst 各步详情）。
     */
    suspend fun rescanFile(entryId: String, rootUri: String, uri: String): RescanFileResult {
        val sourceFiles = source.listFiles(rootUri)
        val file = sourceFiles.firstOrNull { it.path == uri }
            ?: return RescanFileResult(false, "文件不存在或已被移除")
        when (val r = tryReadTags(file, TAG_TIMEOUT_RETRY)) {
            is TagResult.Ok -> {
                val extras = LinkedHashMap<String, List<EntryTagExtraEntity>>()
                val rows = buildRowsForTagged(file, r.tags, entryId, extras)
                // 更新库：读当前行 → 替换该 uri 行 + 追加新行（虚拟轨）→ 整体入库（extras 合并保留其他文件）
                val current = indexStore.loadEntry(entryId).toMutableList()
                current.removeAll { it.uri == uri }
                current.addAll(rows)
                val allExtras = indexStore.loadExtras(entryId).toMutableMap()
                allExtras.putAll(extras)
                indexStore.save(entryId, current, extrasByUri = allExtras)
                return RescanFileResult(true, null, rows.firstOrNull()?.displayName)
            }
            is TagResult.TimedOut -> return RescanFileResult(
                false, errorDetailProvider(uri) ?: "解析超时（60s 仍未完成）"
            )
            is TagResult.Failed -> return RescanFileResult(
                false, errorDetailProvider(uri) ?: "tag parse failed"
            )
        }
    }

    private fun sortIndexed(files: List<IndexedFile>): List<IndexedFile> {
        val nameComparator = Comparator<String> { a, b -> NaturalOrder.compare(a, b) }
        return files.sortedWith(
            compareBy<IndexedFile> { it.relativePath ?: "" }
                .thenBy { it.disc ?: 0 }
                .thenBy { it.track ?: 0 }
                .thenBy(nameComparator) { it.rawName ?: it.displayName }
        )
    }
}
