package com.booklistener.core

import com.booklistener.db.WorkEntity

/**
 * 单文件夹的播放进度记录（v1.6.0 prefs→Room 遗留类型，v4.0 起仅用于老数据迁移解析）
 */
data class FolderProgress(
    val folderUri: String,
    val trackIndex: Int,
    val positionMs: Long,
    val updatedAt: Long
)

/**
 * v4.0 作品回填纯函数（DESIGN-数据库重构-v4 §5.3）：
 * 迁移回填与扫描聚合共用的 key 规则 / 聚合 / 排序 / 老进度解析，全部无 Android 依赖，JVM 单测可覆盖。
 */
object WorkBackfill {

    /**
     * 作品键规则（tag 优先，文件夹兜底）：
     * - 本地有 album tag → "local:<norm(album)>"，可跨文件夹合并。
     *   v2.1.5：不再把 albumArtist/artist 纳入 key——同一专辑不同表演者（如"周杰伦"与
     *   "周杰伦 with 五月天阿信"的同名专辑曲目）必须合并为一个作品；artist 仅用于展示。
     * - 无 tag / 网盘 → "entry:<entryId>|<norm(folderName)>"，不跨 entry、不跨源合并
     *   （网盘不做标签解析，按名字跨源合并会让不同盘的"新建文件夹"撞成一本书）
     */
    fun workKey(
        entryId: String,
        folderName: String,
        album: String?,
        albumArtist: String?,
        artist: String?
    ): String =
        if (!album.isNullOrBlank()) "local:${norm(album)}"
        else "entry:$entryId|${norm(folderName)}"

    /** norm：trim + 小写 + 连续空白折叠为一个空格 */
    fun norm(s: String?): String =
        s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase() ?: ""

    /**
     * 可播放媒体判定（作品聚合的唯一入口）：
     * - CUE 虚拟轨：uri 含 "#track_"（VirtualTrackUri），无论整轨是什么格式都算媒体——
     *   它是 CUE 声明的曲目，封面/播放由整轨承担。
     * - 普通音频：扩展名在 [AudioExt.SUPPORTED] 内（当前解码栈 MediaCodec 可播的格式）。
     * CUE 文件本身、图片、种子、文本等非媒体文件返回 false——它们仍入库（内容文件列表
     * 忠实显示），但不参与作品聚合，不产生"文件夹名"专辑。
     */
    fun isPlayableMedia(f: IndexedFile): Boolean {
        if (VirtualTrackUri.isVirtual(f.uri)) return true
        return AudioExt.isAudio(f.rawName ?: f.uri)
    }

    /**
     * 按作品键聚合文件清单 → 作品实体列表（songCount / totalDurationMs 由聚合计算；
     * lastPlayedAt 由调用方传入——扫描置 0（upsert 不更新该字段），迁移回填传 MAX(条目 lastPlayedAt)）。
     *
     * 仅聚合 [isPlayableMedia] 文件；非媒体文件（cue/图片/种子/文本）不参与聚合。
     */
    fun buildWorks(
        files: List<IndexedFile>,
        entryId: String,
        folderName: String,
        sourceScope: String,
        lastPlayedAt: Long = 0L
    ): List<WorkEntity> {
        val byKey = LinkedHashMap<String, MutableList<IndexedFile>>()
        files.forEach { f ->
            if (!isPlayableMedia(f)) return@forEach
            val key = workKey(entryId, folderName, f.album, f.albumArtist, f.artist)
            byKey.getOrPut(key) { mutableListOf() }.add(f)
        }
        return byKey.map { (key, list) ->
            val first = list.first()
            WorkEntity(
                key = key,
                title = first.album ?: folderName,
                albumArtist = first.albumArtist ?: first.artist,
                sourceScope = sourceScope,
                coverUri = null,
                songCount = list.size,
                totalDurationMs = list.mapNotNull { it.durationMs }.sum(),
                lastPlayedAt = lastPlayedAt,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /** 按作品键合并两组作品（迁移回填：多条目同 key 跨文件夹合并，songCount/时长累加，lastPlayedAt 取新） */
    fun mergeWorks(existing: List<WorkEntity>, fresh: List<WorkEntity>): List<WorkEntity> {
        val byKey = LinkedHashMap<String, WorkEntity>()
        existing.forEach { byKey[it.key] = it }
        fresh.forEach { w ->
            val old = byKey[w.key]
            byKey[w.key] = old?.copy(
                title = w.title,
                albumArtist = w.albumArtist,
                sourceScope = w.sourceScope,
                songCount = old.songCount + w.songCount,
                totalDurationMs = old.totalDurationMs + w.totalDurationMs,
                lastPlayedAt = maxOf(old.lastPlayedAt, w.lastPlayedAt),
                updatedAt = w.updatedAt
            ) ?: w
        }
        return byKey.values.toList()
    }

    /**
     * 展示序（AudioSortKey.byName：disc → track → 名称自然序）下的条目内排序下标。
     * 语义与 [sortIndexedFiles] 100% 等价，SQL 端按整数 sortRank 排序最快。
     */
    fun assignSortRank(files: List<IndexedFile>): List<Pair<String, Int>> =
        sortIndexedFiles(files).mapIndexed { index, f -> f.uri to index }

    /**
     * 老 folder_progress 解析（坑 2：老下标可能已偏——按当前 sortRank 顺序映射到 uri，
     * 统一标 matchMode = 0，保守不假装精确）。
     * @return (uri, 修正后 orderIndex)；越界 → clamp 到末尾；空列表 → null（丢弃该记录）
     */
    fun resolveLegacyProgress(
        rec: FolderProgress,
        orderedUris: List<String>
    ): Pair<String, Int>? {
        if (orderedUris.isEmpty()) return null
        val idx = rec.trackIndex.coerceIn(0, orderedUris.lastIndex)
        return orderedUris[idx] to idx
    }
}
