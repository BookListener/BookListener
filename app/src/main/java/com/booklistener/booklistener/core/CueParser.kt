package com.booklistener.core

/**
 * CUE 整轨虚拟分轨解析（v2.2.0，spec: cue-virtual-tracks）：
 * 单遍状态机解析 .cue 文本 → 虚拟轨列表。全部无 Android 依赖，JVM 单测可覆盖。
 *
 * 支持：
 * - 多 FILE 段（轨归属当前 FILE 上下文，referencedFile 随 FILE 行切换）
 * - INDEX 00 与 INDEX 01（INDEX 01 为准；INDEX 00 记录为上一轨结束备选）
 * - MM:SS:FF → ms（1 秒 = 75 帧，CD 红皮书标准）
 * - 轨级 PERFORMER 覆盖全局
 * - PREGAP/POSTGAP 忽略；无有效 INDEX 的轨跳过；末轨结束=null（播到文件尾）
 */
data class CueTrack(
    /** CD 轨号（TRACK NN，1 起始） */
    val number: Int,
    val title: String,
    val performer: String?,
    /** 起始毫秒（INDEX 01） */
    val startTimeMs: Long,
    /** 结束毫秒：下一轨起始回填；末轨为 null（播到文件尾） */
    val endTimeMs: Long?,
    /** 该轨所属 FILE 段引用的音频文件名（多 FILE 段归属） */
    val referencedFile: String
)

/** 单条 .cue 的解析结果 */
data class CueSheet(
    /** FILE 行引用的音频文件（最后一个 FILE 段兜底；空=未声明） */
    val referencedFile: String?,
    /** 全局专辑名（TITLE，轨级无 TITLE 时继承） */
    val album: String?,
    /** 全局表演者（PERFORMER，轨级无 PERFORMER 时继承） */
    val performer: String?,
    val tracks: List<CueTrack>
)

object CueParser {

    /**
     * 解析 .cue 文本。返回空 tracks 表示无有效轨（调用方按"无 cue"处理，整轨退化普通条目）。
     *
     * @param cueContent .cue 文件文本
     * @return CueSheet；referencedFile 可能为 null（FILE 未命中时整轨定位交给同名探测）
     */
    fun parse(cueContent: String): CueSheet {
        val tracks = mutableListOf<CueTrack>()
        var globalPerformer: String? = null
        var globalTitle: String? = null
        var currentFile: String? = null
        var lastReferencedFile: String? = null

        var trackNumber: Int? = null
        var trackTitle: String? = null
        var trackPerformer: String? = null
        var trackStart: Long? = null
        // INDEX 00 前导间隙：仅 INDEX 01 缺失时作该轨起点兜底
        var indexZeroMs: Long? = null

        fun flush() {
            val num = trackNumber
            // INDEX 01 优先；仅 INDEX 00 时以 00 为起点（否则该轨无起点，跳过）
            val start = trackStart ?: indexZeroMs
            if (num != null && start != null) {
                val file = currentFile ?: lastReferencedFile.orEmpty()
                tracks.add(
                    CueTrack(
                        number = num,
                        title = trackTitle ?: "Track $num",
                        performer = trackPerformer ?: globalPerformer,
                        startTimeMs = start,
                        endTimeMs = null,
                        referencedFile = file
                    )
                )
            }
            trackNumber = null
            trackTitle = null
            trackPerformer = null
            trackStart = null
            indexZeroMs = null
        }

        for (rawLine in cueContent.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val upper = line.uppercase()
            when {
                upper.startsWith("FILE ") || upper == "FILE" -> {
                    // 多 FILE 段：新 FILE 行切换当前上下文（flush 上一 FILE 的残留轨）
                    flush()
                    currentFile = parseQuoted(line.substringAfter("FILE").trim())
                    if (!currentFile.isNullOrEmpty()) lastReferencedFile = currentFile
                }
                upper.startsWith("PERFORMER ") || upper == "PERFORMER" -> {
                    val v = parseQuoted(line.substringAfter("PERFORMER").trim())
                    if (trackNumber == null) globalPerformer = v else trackPerformer = v
                }
                upper.startsWith("TITLE ") || upper == "TITLE" -> {
                    val v = parseQuoted(line.substringAfter("TITLE").trim())
                    if (trackNumber == null) globalTitle = v else trackTitle = v
                }
                upper.startsWith("TRACK ") || upper == "TRACK" -> {
                    flush()
                    trackNumber = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                }
                upper.startsWith("INDEX ") -> {
                    val rest = line.substringAfter("INDEX").trim()
                    val idx = rest.split(Regex("\\s+"))
                    val kind = idx.getOrNull(0)
                    val time = idx.getOrNull(1)
                    when (kind) {
                        "00" -> indexZeroMs = time?.let { parseCueTime(it) }
                        "01" -> {
                            val t = time?.let { parseCueTime(it) }
                            if (t != null) trackStart = t
                        }
                    }
                }
                // PREGAP / POSTGAP / REM / 其余行：忽略
            }
        }
        flush()

        // 结束时间回填：同一 FILE 段内下一轨起始；跨段/末轨保持 null（播到各自文件尾）
        for (i in 0 until tracks.size - 1) {
            if (tracks[i].referencedFile == tracks[i + 1].referencedFile) {
                tracks[i] = tracks[i].copy(endTimeMs = tracks[i + 1].startTimeMs)
            }
        }
        // 末轨 end=null（播到文件尾）
        return CueSheet(
            referencedFile = lastReferencedFile,
            album = globalTitle,
            performer = globalPerformer,
            tracks = tracks
        )
    }

    /** MM:SS:FF → 毫秒；非法返回 null（调用方跳过该时间） */
    fun parseCueTime(time: String): Long? {
        val parts = time.split(':')
        if (parts.size != 3) return null
        val min = parts[0].toLongOrNull() ?: return null
        val sec = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        return min * 60_000L + sec * 1_000L + frames * 1_000L / 75
    }

    /** 提取引号内内容（FILE "x.flac" WAVE → x.flac）；无引号取整串；空 → null */
    private fun parseQuoted(s: String): String? {
        val open = s.indexOf('"')
        if (open >= 0) {
            val close = s.indexOf('"', open + 1)
            val v = if (close >= 0) s.substring(open + 1, close) else s.substring(open + 1)
            return v.trim().takeIf { it.isNotEmpty() }
        }
        return s.trim().takeIf { it.isNotEmpty() }
    }
}
