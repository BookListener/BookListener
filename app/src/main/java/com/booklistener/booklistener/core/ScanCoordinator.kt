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
    private val indexStore: EntryIndexStore
) {

    /**
     * 全量扫描并写索引。
     * @param entryId 条目标识（写索引的 key；本地 SAF tree uri / 网盘 "webdav://..." ）
     * @param rootUri 列目录的根标识（本地 SAF tree uri / 网盘完整 URL）
     */
    suspend fun scan(entryId: String, rootUri: String = entryId) {
        val sourceFiles = source.listFiles(rootUri)
        // 只处理文件（目录条目不入库；relativePath 已表达目录结构）
        val files = sourceFiles.filterNot { it.isDirectory }

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
                    // 音频：读标签；失败用文件名兜底，记 error
                    val tags = runCatching { tagReader(file) }.getOrNull()
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
