package com.booklistener.core

import com.booklistener.db.CueSheetEntity

/**
 * CUE 整轨虚拟分轨·扫描索引纯函数（v2.2.0，spec: cue-virtual-tracks）：
 * 输入 = 条目文件清单（IndexedFile）+ 该条目内的 .cue 候选（文本已读好），
 * 输出 = 虚拟轨行（普通媒体条目）+ 被吸收整轨 uri 集合 + cue_sheets 关联行。
 * 全部无 Android 依赖，JVM 单测可覆盖（吸收成功 / 解析失败退化 / 二次扫描稳定）。
 *
 * 吸收语义（spec「整轨吸收与退化」）：
 * - cue 解析出 ≥1 轨且**引用的每个音频文件**都在 files 中定位到（FILE 声明优先，
 *   同名扩展名探测兜底 flac/ape/wav/mp3/m4a/dsf/dff）→ 整轨被吸收（不入 entry_index），
 *   虚拟轨入库（uri = `<整轨uri>#track_<startMs>`）
 * - 解析失败 / 无有效轨 / 任一引用文件未定位 → 整轨保持普通条目，不生成虚拟轨
 */
object CueIndexer {

    /**
     * 整轨候选扩展名（同名探测兜底顺序）。
     * 仅含 Media3 官方解码器支持的格式（flac/wav/mp3/m4a）；ape/dsf/dff 无解码支持
     * （需 FFmpeg 扩展，项目未引入），不纳入吸收探测，整轨保持普通条目。
     */
    val WHOLE_EXTENSIONS = setOf("flac", "wav", "mp3", "m4a")

    /** 扫描发现的 .cue 文件（文本已由调用方按源读取） */
    data class CueCandidate(
        val cueUri: String,
        val displayName: String,
        val text: String
    )

    data class Result(
        /** 虚拟轨行（普通媒体条目，含 cue 专辑/表演者/轨号） */
        val virtualTracks: List<IndexedFile>,
        /** 被吸收的整轨 uri（调用方从 files 剔除，不再写 entry_index） */
        val absorbedAudioUris: Set<String>,
        /** cue_sheets 关联行（workId 由入库侧反查回填） */
        val cueSheets: List<CueSheetEntity>,
        /** cueUri → 该 cue 首个虚拟轨 uri（入库侧据此反查作品 id 回填 cue_sheets.workId） */
        val sampleTrackUris: Map<String, String> = emptyMap()
    )

    fun index(entryId: String, files: List<IndexedFile>, cues: List<CueCandidate>): Result {
        val virtualTracks = mutableListOf<IndexedFile>()
        val absorbed = mutableSetOf<String>()
        val sheets = mutableListOf<CueSheetEntity>()
        val samples = LinkedHashMap<String, String>()
        if (cues.isEmpty()) return Result(virtualTracks, absorbed, sheets, samples)

        for (cue in cues) {
            val sheet = CueParser.parse(cue.text)
            if (sheet.tracks.isEmpty()) {
                // 解析失败/无有效轨：整轨退化为普通条目，cue 行仍记录存在
                sheets += CueSheetEntity(cueUri = cue.cueUri, entryId = entryId, audioUri = null, workId = null)
                continue
            }
            // 多 FILE 段：引用的音频文件集合（去重），全部定位成功才吸收
            val refNames = sheet.tracks.map { it.referencedFile }.filter { it.isNotBlank() }.distinct()
            if (refNames.isEmpty()) {
                sheets += CueSheetEntity(cue.cueUri, entryId, null, null)
                continue
            }
            val located = LinkedHashMap<String, IndexedFile>() // referencedFile → 命中整轨
            var allLocated = true
            for (ref in refNames) {
                val hit = files.firstOrNull { matchesWhole(it, ref) }
                    ?: probeSameBase(cue, ref, files)
                if (hit == null) { allLocated = false; break }
                located[ref] = hit
            }
            if (!allLocated) {
                // 整轨定位失败（FILE 未命中且同名探测未中）：退化为普通条目
                sheets += CueSheetEntity(cue.cueUri, entryId, null, null)
                continue
            }
            // 吸收：剔除整轨，生成虚拟轨
            absorbed += located.values.map { it.uri }
            var firstTrackUri: String? = null
            for (t in sheet.tracks) {
                val whole = located[t.referencedFile] ?: located.values.first()
                val uri = "${whole.uri}#track_${t.startTimeMs}"
                if (firstTrackUri == null) firstTrackUri = uri
                virtualTracks += IndexedFile(
                    displayName = t.title,
                    uri = uri,
                    album = sheet.album,
                    artist = t.performer,
                    track = t.number,
                    disc = null,
                    durationMs = t.endTimeMs?.let { it - t.startTimeMs },
                    rawName = "${cue.displayName}#${t.number}",
                    sizeBytes = null
                )
            }
            firstTrackUri?.let { samples[cue.cueUri] = it }
            sheets += CueSheetEntity(
                cueUri = cue.cueUri,
                entryId = entryId,
                audioUri = located.values.first().uri,
                workId = null
            )
        }
        return Result(virtualTracks, absorbed, sheets, samples)
    }

    /** FILE 声明匹配：rawName 或 uri 尾段与声明文件名大小写不敏感一致 */
    private fun matchesWhole(f: IndexedFile, refName: String): Boolean {
        val ref = refName.lowercase()
        return f.rawName?.lowercase() == ref ||
            f.uri.substringAfterLast('/').lowercase() == ref
    }

    /** 同名扩展名探测：cue 基名（或声明基名）命中 files 中任一整轨扩展名文件 */
    private fun probeSameBase(cue: CueCandidate, refName: String, files: List<IndexedFile>): IndexedFile? {
        val bases = listOf(cue.displayName.substringBeforeLast('.'), refName.substringBeforeLast('.'))
        return files.firstOrNull { f ->
            val name = (f.rawName ?: f.uri.substringAfterLast('/')).lowercase()
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.')
            (base in bases) && ext in WHOLE_EXTENSIONS
        }
    }
}
