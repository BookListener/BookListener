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

/** 单曲索引行：显示名（标题/去扩展名文件名）+ 原始文件名 + 标签 + 时长（v1.7.0 扩展） */
data class IndexedFile(
    val displayName: String,
    val uri: String,
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val durationMs: Long? = null,
    val rawName: String? = null
) {
    fun toEntity(entryId: String): EntryIndexEntity = EntryIndexEntity(
        entryId = entryId,
        displayName = displayName,
        uri = uri,
        album = album,
        artist = artist,
        track = track,
        disc = disc,
        durationMs = durationMs,
        rawName = rawName
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
            rawName = e.rawName
        )
    }
}

/** AudioItem → 索引行（v1.7.0：本地扫描/网盘浏览出口统一转换） */
fun AudioItem.toIndexedFile(): IndexedFile = IndexedFile(
    displayName = displayName,
    uri = path,
    album = album,
    artist = artist,
    track = track,
    disc = disc,
    durationMs = durationMs,
    rawName = rawName
)

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

    fun save(entryId: String, files: List<IndexedFile>) = blockingRun {
        dao.replaceEntryIndex(entryId, files.map { it.toEntity(entryId) })
    }

    /** 播放后补录/校正时长（网盘或扫描未知时长） */
    fun updateDurationMs(entryId: String, uri: String, durationMs: Long) = blockingRun {
        dao.updateIndexDuration(entryId, uri, durationMs)
    }

    fun remove(entryId: String) = blockingRun {
        dao.deleteEntryIndex(entryId)
    }

    private fun blockingRun(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}

/** 搜索结果：文件夹命中或文件命中 */
sealed class SearchHit {
    data class Folder(val entry: SavedEntry) : SearchHit()
    data class File(val entry: SavedEntry, val file: IndexedFile) : SearchHit() {
        val sourceType: SourceType get() = entry.sourceType
    }
}

/** 搜索逻辑：文件夹名 + 文件名（忽略大小写包含匹配，文件夹优先） */
fun searchEntries(query: String, entries: List<SavedEntry>, index: Map<String, List<IndexedFile>>): List<SearchHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val hits = mutableListOf<SearchHit>()
    entries.forEach { entry ->
        if (entry.displayName.contains(q, ignoreCase = true)) hits += SearchHit.Folder(entry)
    }
    entries.forEach { entry ->
        index[entry.id].orEmpty().forEach { file ->
            if (file.displayName.contains(q, ignoreCase = true)) {
                hits += SearchHit.File(entry, file)
            }
        }
    }
    return hits
}
