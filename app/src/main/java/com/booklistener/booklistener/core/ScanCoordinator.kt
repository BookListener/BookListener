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

    companion object {
        // v2.1.15：跳过过小/过短的音频文件（铃声/通知音/系统音效）
        const val MIN_AUDIO_SIZE_BYTES = 100L * 1024L   // 100 KB
        const val MIN_AUDIO_DURATION_MS = 30L * 1000L    // 30 秒
    }

    /**
     * 全量扫描并写索引。
     * @param entryId 条目标识（写索引的 key；本地 SAF tree uri / 网盘 "webdav://..." ）
     * @param rootUri 列目录的根标识（本地 SAF tree uri / 网盘完整 URL）
     */
    suspend fun scan(entryId: String, rootUri: String = entryId) {
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

        for (file in files) {
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
                    // 音频：读标签；失败用文件名兜底，记 error（唯一后端，无次级解析）
                    val tags = runCatching { tagReader(file) }.getOrNull()
                    // v2.1.14：跳过播放时间过短的音频（标签解析出时长后判断）
                    val dur = tags?.durationMs
                    if (dur != null && dur in 1..<MIN_AUDIO_DURATION_MS) {
                        continue
                    }
                    // v2.2.4（spec: m4b-audiobook-chapters）：m4b 有章节 → 章节虚拟轨，
                    // 整本被吸收（不入 indexed，同 CUE 吸收语义）；无章节 → 普通媒体条目。
                    val chapters = tags?.chapters.orEmpty()
                    if (tags != null && chapters.isNotEmpty() && file.extension.equals("m4b", ignoreCase = true)) {
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
                        val wholeDuration = tags.durationMs
                        chapters.forEachIndexed { i, ch ->
                            val nextStart = chapters.getOrNull(i + 1)?.startMs
                            indexed += IndexedFile(
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
                        // v2.2.10：章节虚拟轨把原始 .m4b 吸收后，内容文件列表会空（该列表过滤虚拟轨）。
                        // 保留一条原始 .m4b 行（excludeFromWork=true）：内容文件列表显示原始文件、
                        // 不参与作品聚合（曲目列表仍只由章节虚拟轨组成，避免重复）。
                        indexed += IndexedFile(
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
                            encoder = tags.encoder,
                            excludeFromWork = true
                        )
                    } else {
                        indexed += if (tags != null) {
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
                        IndexedFile(
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
                    } else {
                        // 标签解析失败：文件名兜底，归入未知专辑
                        // v2.2.6：error 首行保持 "tag parse failed"（列表副标题显示），
                        // 其后附加扫描诊断（点信息图标弹窗查看完整内容；error 已入库，列表重载后仍可查看）
                        file.toIndexedFile(
                            error = errorDetailProvider(file.path) ?: "tag parse failed"
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
