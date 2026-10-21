package com.booklistener.core

import com.booklistener.db.AppDao
import com.booklistener.db.EntryIndexEntity
import com.booklistener.db.SavedEntryEntity

/**
 * 已添加内容条目（本地文件夹 / 网盘源，合并展示）。
 * - LOCAL：uri = SAF tree uri（displayName 为目录名，非完整路径）
 * - WEBDAV / ALIYUN：uri = 网盘路径（"/" 为源根目录），displayName 为源名
 */
data class SavedEntry(
    val id: String,
    val displayName: String,
    val uri: String,
    val sourceType: SourceType,
    val coverUri: String? = null,
    val fileCount: Int = 0,
    val lastPlayedAt: Long = 0L, // 0 = 从未播放
    val artist: String? = null, // 作者信息（本地扫描自首曲元数据，网盘暂缺）
    val lastOpenedAt: Long = 0L // 0 = 从未打开（浏览过也用于杀后台恢复）
) {
    val isLocal: Boolean get() = sourceType == SourceType.LOCAL

    fun toEntity(sortOrder: Int): SavedEntryEntity = SavedEntryEntity(
        id = id,
        displayName = displayName,
        uri = uri,
        sourceType = sourceType.name,
        coverUri = coverUri,
        fileCount = fileCount,
        lastPlayedAt = lastPlayedAt,
        artist = artist,
        lastOpenedAt = lastOpenedAt,
        sortOrder = sortOrder
    )
}

fun SavedEntryEntity.toEntry(): SavedEntry {
    val sourceType = runCatching { SourceType.valueOf(sourceType) }
        .getOrDefault(SourceType.LOCAL)
    return SavedEntry(
        id = id,
        displayName = displayName,
        uri = uri,
        sourceType = sourceType,
        coverUri = coverUri,
        fileCount = fileCount,
        lastPlayedAt = lastPlayedAt,
        artist = artist,
        lastOpenedAt = lastOpenedAt
    )
}

/**
 * 已添加内容持久化（v1.6.0：prefs-JSON → Room，API 不变）。
 * 写入改为**逐行操作**（同 id REPLACE / UPDATE / DELETE），不再整表替换：
 * 并发（异步扫描 afterLoad 与用户保存/删除）下基于过期列表的整表替换会覆盖先提交数据，
 * 逐行写从根上消除该竞态；sortOrder 由调用方按当前列表计算（并发下顺序近似，不丢数据）。
 * 旧数据首启迁移统一走 StoreMigration（本类不再持有 prefs）。
 */
class SavedEntryStore(private val dao: AppDao) {

    fun load(): List<SavedEntry> = kotlinx.coroutines.runBlocking {
        dao.getAllSavedEntries().map { it.toEntry() }
    }

    /** 追加/替换条目（同 id 覆盖），返回新列表 */
    fun upsert(current: List<SavedEntry>, entry: SavedEntry): List<SavedEntry> {
        val updated = current.filterNot { it.id == entry.id } + entry
        writeOne(entry, indexOf(current, entry.id, current.size))
        return updated
    }

    fun remove(current: List<SavedEntry>, id: String): List<SavedEntry> {
        val updated = current.filterNot { it.id == id }
        kotlinx.coroutines.runBlocking { dao.deleteSavedEntry(id) }
        return updated
    }

    /** 原位替换条目（保持列表顺序，供异步扫描后补充封面/文件数） */
    fun replace(current: List<SavedEntry>, entry: SavedEntry): List<SavedEntry> {
        val updated = current.map { if (it.id == entry.id) entry else it }
        writeOne(entry, indexOf(current, entry.id, current.size))
        return updated
    }

    fun touchLastPlayed(current: List<SavedEntry>, id: String, ts: Long): List<SavedEntry> {
        val updated = current.map { if (it.id == id) it.copy(lastPlayedAt = ts) else it }
        kotlinx.coroutines.runBlocking { dao.updateSavedEntryLastPlayed(id, ts) }
        return updated
    }

    /** 记录"最近打开"（进入条目视图即调用，支撑杀后台恢复到浏览过的条目） */
    fun touchOpened(current: List<SavedEntry>, id: String, ts: Long): List<SavedEntry> {
        val updated = current.map { if (it.id == id) it.copy(lastOpenedAt = ts) else it }
        kotlinx.coroutines.runBlocking { dao.updateSavedEntryLastOpened(id, ts) }
        return updated
    }

    /** 单行 upsert；sortOrder 取条目在当前列表中的索引（新 id 追加末尾） */
    private fun writeOne(entry: SavedEntry, index: Int) = kotlinx.coroutines.runBlocking {
        dao.upsertSavedEntry(entry.toEntity(index.coerceAtLeast(0)))
    }

    /** 条目在当前列表中的位置（不存在 → 默认追加末尾） */
    private fun indexOf(current: List<SavedEntry>, id: String, fallback: Int): Int {
        val i = current.indexOfFirst { it.id == id }
        return if (i >= 0) i else fallback
    }

    private fun blockingRun(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}

/** 主界面分区纯逻辑：最近播放（已播放过，新→旧）/ 尚未播放 */
fun splitHomeSections(entries: List<SavedEntry>): HomeSections {
    val played = entries.filter { it.lastPlayedAt > 0L }
        .sortedByDescending { it.lastPlayedAt }
    val never = entries.filter { it.lastPlayedAt <= 0L }
    return HomeSections(played, never)
}

data class HomeSections(
    val recentPlayed: List<SavedEntry>,
    val notPlayed: List<SavedEntry>
)

// ---- 已添加内容索引（支撑搜索 + 专辑/播放列表 db 加载）----

/** 单曲索引行：显示名（标题/去扩展名文件名）+ 原始文件名 + 标签 + 时长（v1.7.0 扩展；v4.0 补作品/排序/体积） */
data class IndexedFile(
    val displayName: String,
    val uri: String,
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val durationMs: Long? = null,
    val rawName: String? = null,
    // v4.0：作品实体化辅助字段（不入库，扫描时由 saveEntryIndexWithWorks 派生写入）
    val albumArtist: String? = null,
    val sizeBytes: Long? = null,
    // v2.2.0：设置侧链路重构
    val relativePath: String? = null,
    val error: String? = null
) {
    fun toEntity(entryId: String, workId: Long? = null, sortRank: Int? = null): EntryIndexEntity = EntryIndexEntity(
        entryId = entryId,
        displayName = displayName,
        uri = uri,
        album = album,
        artist = artist,
        track = track,
        disc = disc,
        durationMs = durationMs,
        rawName = rawName,
        workId = workId,
        sortRank = sortRank,
        sizeBytes = sizeBytes,
        albumArtist = albumArtist,
        relativePath = relativePath,
        error = error
    )

    companion object {
        fun fromEntity(e: EntryIndexEntity): IndexedFile = IndexedFile(
            displayName = e.displayName,
            uri = e.uri,
            album = e.album,
            artist = e.artist,
            track = e.track,
            disc = e.disc,
            durationMs = e.durationMs,
            rawName = e.rawName,
            albumArtist = e.albumArtist,
            sizeBytes = e.sizeBytes,
            relativePath = e.relativePath,
            error = e.error
        )
    }
}

/** AudioItem → 索引行（v1.7.0：本地扫描/网盘浏览出口统一转换；v4.0 补 sizeBytes） */
fun AudioItem.toIndexedFile(): IndexedFile = IndexedFile(
    displayName = displayName,
    uri = path,
    album = album,
    artist = artist,
    track = track,
    disc = disc,
    durationMs = durationMs,
    rawName = rawName,
    sizeBytes = size.takeIf { it > 0L }
)

/**
 * v1.6.13：无音轨号（tag 缺失）的条目，写库前按展示排序（名称自然序）赋予 1..N 序号；
 * 有音轨号的保持标签原值不动。幂等：同一列表重复调用结果一致。
 * 说明：序号只反映「无 tag 条目之间」的相对排序，有 tag 条目不参与重新编号。
 */
fun assignTrackNumbers(files: List<IndexedFile>): List<IndexedFile> {
    // 按名称自然序给无音轨号条目排序，映射 原索引 → 序号
    val ranks = files
        .mapIndexed { index, f -> index to f }
        .filter { (_, f) -> f.track == null }
        .sortedWith { a, b -> IndexedFileByDisplayOrder.compare(a.second, b.second) }
        .mapIndexed { rank, (index, _) -> index to (rank + 1) }
        .toMap()
    if (ranks.isEmpty()) return files
    return files.mapIndexed { index, f ->
        val track = ranks[index]
        if (track != null) f.copy(track = track) else f
    }
}

/** 索引行按展示顺序排序（disc → track → 名称自然序），v1.6.14：db 读取后统一排序 */
fun sortIndexedFiles(files: List<IndexedFile>): List<IndexedFile> =
    files.sortedWith(IndexedFileByDisplayOrder)

/** 索引行展示序比较器：disc → track → 名称自然序（缺失档位排后） */
private val IndexedFileByDisplayOrder: Comparator<IndexedFile> = Comparator { a, b ->
    AudioSortKey.byName.compare(
        AudioSortKey(a.displayName, a.disc, a.track),
        AudioSortKey(b.displayName, b.disc, b.track)
    )
}

/** 索引行 → AudioItem（专辑播放队列用；sourceType 由来源条目提供，网盘按类型解析） */
fun IndexedFile.toAudioItem(sourceType: SourceType): AudioItem = AudioItem(
    id = uri,
    displayName = displayName,
    path = uri,
    sourceType = sourceType,
    size = 0L,
    album = album,
    artist = artist,
    track = track,
    disc = disc,
    durationMs = durationMs,
    rawName = rawName
)

/** 条目内容索引：固化时缓存文件清单，进入文件夹时刷新（v1.6.0：prefs → Room；v1.7.0：全字段落库） */
class EntryIndexStore(private val dao: AppDao) {

    /** entryId → 文件清单 */
    data class Bucket(val entryId: String, val files: List<IndexedFile>)

    fun load(): Map<String, List<IndexedFile>> = kotlinx.coroutines.runBlocking {
        dao.getAllEntryIndex().groupBy({ it.entryId }, { IndexedFile.fromEntity(it) })
    }

    /** 按条目读取文件清单（v1.6.14：进入条目视图改从 db 读取，不再每次全量扫描） */
    fun loadEntry(entryId: String): List<IndexedFile> = kotlinx.coroutines.runBlocking {
        dao.getEntryIndex(entryId).map { IndexedFile.fromEntity(it) }
    }

    /** v2.1.15：读取条目标题（用作无 tag 时的 workKey folderName 部分） */
    fun getFolderName(entryId: String): String? = kotlinx.coroutines.runBlocking {
        dao.getAllSavedEntries().firstOrNull { it.id == entryId }?.displayName
    }

    /**
     * v4.0 扫描入库（作品实体化）：逐文件算作品键 → upsert works by key（id 稳定）→
     * 写 entry_index（workId/sortRank/sizeBytes）→ 清理失效行与无引用作品（连带进度）。
     * 禁止整删重建 works——workId 一旦重建，work_progress.workId 断链、进度全丢。
     *
     * v5.0（spec: cue-virtual-tracks）：可选 cue 候选。存在时先做 CUE 预处理：
     * 解析 → 定位整轨（FILE 声明优先 + 同名扩展名探测）→ 虚拟轨并入 files、被吸收整轨剔除、
     * cue_sheets 落库（workId 按虚拟轨 uri 反查回填）。
     */
    fun save(entryId: String, files: List<IndexedFile>, cueCandidates: List<CueIndexer.CueCandidate> = emptyList()) = blockingRun {
        val entry = dao.getAllSavedEntries().firstOrNull { it.id == entryId }
        val folderName = entry?.displayName ?: entryId
        val sourceScope = entry?.sourceType ?: SourceType.LOCAL.name
        val cueResult = CueIndexer.index(entryId, files, cueCandidates)
        val merged = files.filterNot { it.uri in cueResult.absorbedAudioUris } + cueResult.virtualTracks
        val withTrack = assignTrackNumbers(merged)
        dao.saveEntryIndexWithWorks(entryId, folderName, sourceScope, withTrack)
        // cue_sheets 与 entry_index 同生命周期整体替换；workId = 该 cue 首个虚拟轨所在作品
        if (cueResult.cueSheets.isNotEmpty()) {
            val sheets = cueResult.cueSheets.map { s ->
                val sample = cueResult.sampleTrackUris[s.cueUri]
                if (s.workId == null && sample != null) {
                    val wid = dao.getWorkIdByUri(sample)
                    if (wid != null) s.copy(workId = wid) else s
                } else s
            }
            dao.replaceCueSheets(entryId, sheets)
        }
    }

    /** 播放后补录/校正时长（网盘或扫描未知时长）；同步重算作品总时长缓存 */
    fun updateDurationMs(entryId: String, uri: String, durationMs: Long) = blockingRun {
        dao.updateIndexDuration(entryId, uri, durationMs)
        dao.refreshWorkDurationByFile(entryId, uri)
    }

    /** 删除条目内容索引，并清理因此失去引用的作品（连带其进度） */
    fun remove(entryId: String) = blockingRun {
        dao.deleteEntryIndex(entryId)
        dao.cleanupOrphanWorks()
    }

    /**
     * v2.1.15：扫描后按作品键回填封面（首曲内嵌封面提取后入库）。
     * covers: workKey -> coverUri；仅更新非空封面，空封面不覆盖已有值。
     */
    fun updateWorkCovers(covers: Map<String, String>) = blockingRun {
        val now = System.currentTimeMillis()
        covers.forEach { (key, coverUri) ->
            if (coverUri.isNotBlank()) {
                dao.updateWorkCoverByKey(key, coverUri, now)
            }
        }
    }

    private fun blockingRun(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}

/**
 * 搜索结果（v2.0.1：面向数据库的专辑+歌曲搜索，不再命中文件夹）：
 * - Album：已添加条目内聚合出的专辑（专辑名匹配，点击进入专辑视图）
 * - File：专辑内歌曲（文件名匹配，点击直接播放）
 */
sealed class SearchHit {
    data class Album(val album: AlbumSummary) : SearchHit()
    data class File(val entry: SavedEntry, val file: IndexedFile) : SearchHit() {
        val sourceType: SourceType get() = entry.sourceType
    }
}

/**
 * 搜索逻辑：专辑名 + 文件名（忽略大小写包含匹配），数据全部来自 db 内容索引。
 * 专辑聚合口径与 AlbumStore 一致：album 缺失按条目 displayName 兜底。
 */
fun searchEntries(query: String, entries: List<SavedEntry>, index: Map<String, List<IndexedFile>>): List<SearchHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val hits = mutableListOf<SearchHit>()
    entries.forEach { entry ->
        val files = index[entry.id].orEmpty()
        if (files.isEmpty()) return@forEach
        val albums = files.map { it.album ?: entry.displayName }.distinct()
        albums.forEach { album ->
            if (album.contains(q, ignoreCase = true)) {
                val albumFiles = files.filter { (it.album ?: entry.displayName) == album }
                hits += SearchHit.Album(
                    AlbumSummary(
                        workId = 0L, // 搜索命中后由 openAlbum 按 (条目, 专辑名) 反查补全
                        album = album,
                        entryId = entry.id,
                        artist = albumFiles.firstNotNullOfOrNull { it.artist } ?: entry.artist,
                        songCount = albumFiles.size,
                        totalDurationMs = albumFiles.mapNotNull { it.durationMs }.sum(),
                        albumCountInEntry = albums.size,
                        coverUri = null
                    )
                )
            }
        }
        files.forEach { file ->
            if (file.displayName.contains(q, ignoreCase = true)) {
                hits += SearchHit.File(entry, file)
            }
        }
    }
    return hits
}
