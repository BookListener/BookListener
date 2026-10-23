package com.booklistener.core

/**
 * 扫描协调器（v2.2.0 设置侧链路重构）。
 *
 * 统一调度"列目录 → 标签解析 → CUE 收集 → 写索引 → 作品聚合"，
 * 所有降级逻辑集中在此，来源无关。本地/网盘只差异在 [Source] 与读取器实现。
 *
 * 非音频文件（含 .cue）也入库，保证内容文件列表忠实显示所有文件。
 */
class ScanCoordinator(
    private val source: Source,
    /** 读音频标签（本地 MediaMetadataRetriever；网盘无标签返回 null）；失败应返回 null */
    private val tagReader: suspend (SourceFile) -> TagResolver.AudioTags?,
    /** 读 .cue 文本（本地 SAF / 网盘 GET，已做编码探测）；失败返回 null */
    private val cueTextReader: suspend (SourceFile) -> String?,
    private val indexStore: EntryIndexStore,
    /** v2.1.15：提取音频内嵌封面（null 表示不提取作品封面，网盘无内嵌封面传 null） */
    private val coverExtractor: (suspend (audioUri: String) -> String?)? = null
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

        for (file in files) {
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
                    // 音频：读标签；失败用文件名兜底，记 error
                    val tags = runCatching { tagReader(file) }.getOrNull()
                    // v2.1.14：跳过播放时间过短的音频（标签解析出时长后判断）
                    val dur = tags?.durationMs
                    if (dur != null && dur in 1..<MIN_AUDIO_DURATION_MS) {
                        continue
                    }
                    indexed += if (tags != null) {
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
                            error = null
                        )
                    } else {
                        // 标签解析失败：文件名兜底，归入未知专辑
                        file.toIndexedFile(error = "tag parse failed")
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
        indexStore.save(entryId, sorted, cueCandidates)

        // v2.1.15：扫描后为每个作品提取首曲内嵌封面入库（专辑列表零 IO 显示封面）
        coverExtractor?.let { extractor ->
            val folderName = indexStore.getFolderName(entryId) ?: entryId
            // 按 workKey 分组，取每组第一首音频 uri（排序后第一首即 disc/track 最前）
            val firstAudioByKey = LinkedHashMap<String, String>()
            for (f in sorted) {
                if (!AudioExt.isAudio(f.rawName ?: f.uri)) continue
                // 虚拟轨也计入（封面在整轨上，extractor 内部解析）
                val key = WorkBackfill.workKey(entryId, folderName, f.album, f.albumArtist, f.artist)
                if (!firstAudioByKey.containsKey(key)) {
                    firstAudioByKey[key] = f.uri
                }
            }
            val covers = mutableMapOf<String, String>()
            for ((key, audioUri) in firstAudioByKey) {
                runCatching { extractor(audioUri) }.getOrNull()?.let { covers[key] = it }
            }
            if (covers.isNotEmpty()) {
                indexStore.updateWorkCovers(covers)
            }
        }
    }

    private fun SourceFile.toIndexedFile(error: String?): IndexedFile = IndexedFile(
        displayName = name.substringBeforeLast('.').takeUnless { it.isEmpty() } ?: name,
        uri = path,
        rawName = name,
        sizeBytes = sizeBytes,
        relativePath = relativePath,
        error = error
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
