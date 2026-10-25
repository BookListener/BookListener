package com.booklistener.core

import android.content.Context
import android.content.SharedPreferences
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import org.json.JSONArray

/**
 * v1.6.0 首启迁移：把三个业务 Store 的 prefs-JSON 旧数据搬运到 Room 表。
 * - **幂等**：迁移标记 + 目标表空判定双保险（重复运行安全，不丢数据）；
 * - **不删除 prefs**：回滚安全（卸载旧数据可追溯），后续版本可再消费；
 * - 旧 legacy（单 Uri / Uri 数组）与 saved_entries_json 新格式一并处理；
 * - 在 MainActivity.onCreate 同步执行（数据量小、毫秒级），Store 首次 load 即读到完整数据。
 */
object StoreMigration {

    private const val FLAG = "store_migration_v1_done"

    private const val KEY_ENTRIES = "saved_entries_json"
    private const val KEY_LEGACY_SINGLE = "saved_folder_uri"
    private const val KEY_LEGACY_LIST = "saved_folders_json"
    private const val KEY_PROGRESS = "folder_progress_json"
    private const val KEY_INDEX = "saved_entries_index_json"

    fun run(prefs: SharedPreferences, db: AppDatabase) {
        if (prefs.getBoolean(FLAG, false)) return
        kotlinx.coroutines.runBlocking {
            migrateSavedEntries(prefs, db.dao())
            migrateProgress(prefs, db.dao())
            migrateIndex(prefs, db.dao())
        }
        prefs.edit().putBoolean(FLAG, true).apply()
    }

    // ---- saved_entry ----

    private suspend fun migrateSavedEntries(prefs: SharedPreferences, dao: com.booklistener.db.AppDao) {
        if (dao.countSavedEntries() > 0) return
        val entries = readLegacySavedEntries(prefs)
        if (entries.isEmpty()) return
        dao.insertSavedEntries(entries.mapIndexed { index, e -> e.toEntity(index) })
    }

    private fun readLegacySavedEntries(prefs: SharedPreferences): List<SavedEntry> {
        // 新格式：saved_entries_json（JSONArray of 对象）
        val fromNew = readJsonArray(prefs, KEY_ENTRIES)?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    parseSavedEntry(arr.optJSONObject(i))?.let { add(it) }
                }
            }
        }
        if (!fromNew.isNullOrEmpty()) return fromNew
        // 旧格式：单 Uri / Uri 数组 → LOCAL SavedEntry
        val uris = LinkedHashSet<String>()
        prefs.getString(KEY_LEGACY_SINGLE, null)?.takeIf { it.isNotBlank() }?.let { uris.add(it) }
        readJsonArray(prefs, KEY_LEGACY_LIST)?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { uris.add(it) }
            }
        }
        return uris.map { uri ->
            SavedEntry(
                id = uri,
                displayName = folderDisplayName(android.net.Uri.parse(uri)),
                uri = uri,
                sourceType = SourceType.LOCAL
            )
        }
    }

    private fun parseSavedEntry(obj: org.json.JSONObject?): SavedEntry? {
        obj ?: return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        val sourceType = runCatching { SourceType.valueOf(obj.optString("sourceType", "LOCAL")) }
            .getOrDefault(SourceType.LOCAL)
        return SavedEntry(
            id = id,
            displayName = obj.optString("displayName"),
            uri = obj.optString("uri"),
            sourceType = sourceType,
            coverUri = obj.optString("coverUri").takeIf { it.isNotBlank() },
            fileCount = obj.optInt("fileCount", 0),
            lastPlayedAt = obj.optLong("lastPlayedAt", 0L),
            artist = obj.optString("artist").takeIf { it.isNotBlank() },
            lastOpenedAt = obj.optLong("lastOpenedAt", 0L)
        )
    }

    /** 旧版本：从 SAF tree Uri 取目录显示名 */
    private fun folderDisplayName(uri: android.net.Uri): String =
        runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }
            .getOrElse { uri.lastPathSegment ?: uri.toString() }
            .let { raw ->
                val decoded = android.net.Uri.decode(raw)
                val afterColon = decoded.substringAfter(':').trim('/')
                if (afterColon.isBlank()) decoded.substringBefore(':').ifBlank { decoded }
                else afterColon.substringAfterLast('/')
            }

    // ---- folder_progress ----

    private suspend fun migrateProgress(prefs: SharedPreferences, dao: com.booklistener.db.AppDao) {
        if (dao.getAllFolderProgress().isNotEmpty()) return
        readJsonArray(prefs, KEY_PROGRESS)?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val folderUri = obj.optString("folderUri")
                if (folderUri.isBlank()) continue
                dao.upsertFolderProgress(
                    com.booklistener.db.FolderProgressEntity(
                        folderUri = folderUri,
                        trackIndex = obj.optInt("trackIndex", 0).coerceAtLeast(0),
                        positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                        updatedAt = obj.optLong("updatedAt", 0L)
                    )
                )
            }
        }
    }

    // ---- entry_index ----

    private suspend fun migrateIndex(prefs: SharedPreferences, dao: com.booklistener.db.AppDao) {
        if (dao.getAllEntryIndex().isNotEmpty()) return
        readJsonArray(prefs, KEY_INDEX)?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val entryId = obj.optString("entryId")
                if (entryId.isBlank()) continue
                val filesArr = obj.optJSONArray("files") ?: continue
                val rows = buildList {
                    for (j in 0 until filesArr.length()) {
                        val f = filesArr.optJSONObject(j) ?: continue
                        val name = f.optString("displayName")
                        val uri = f.optString("uri")
                        if (name.isNotBlank()) add(
                            com.booklistener.db.EntryIndexEntity(
                                entryId = entryId,
                                displayName = name,
                                uri = uri,
                                album = null, artist = null, track = null, disc = null,
                                durationMs = null, rawName = null
                            )
                        )
                    }
                }
                if (rows.isNotEmpty()) dao.insertEntryIndex(rows)
            }
        }
    }

    private fun readJsonArray(prefs: SharedPreferences, key: String): JSONArray? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { JSONArray(raw) }.getOrNull()
    }
}

/**
 * v2.1.26 封面目录迁移：内嵌封面提取产物从 cacheDir/covers（易失）移入 filesDir/covers（持久）。
 * works.coverUri 持久引用封面文件，必须与数据库同清理边界——清缓存不清、清数据/卸载连库一起删，
 * 消除"缓存清了库还在"导致的封面路径悬空。
 * - **幂等**：prefs 标记（store_migration 同 prefs 文件）；
 * - **失败安全**：先复制 → 改库（前缀改写）→ 删旧目录。复制/改库任一失败都不删旧目录，
 *   旧 uri 仍指向旧位置可正常显示，不产生悬空；下次启动（标记未置）自动重试。
 */
object CoverDirMigration {

    private const val FLAG = "cover_dir_migration_v1_done"

    fun run(context: Context, db: AppDatabase) {
        val prefs = context.getSharedPreferences("saved_folders", Context.MODE_PRIVATE)
        if (prefs.getBoolean(FLAG, false)) return
        val done = kotlinx.coroutines.runBlocking { migrate(context, db.dao()) }
        if (done) prefs.edit().putBoolean(FLAG, true).apply()
    }

    /** @return true=迁移完成（置标记）；false=无需迁移或失败（不置标记，下次重试） */
    private suspend fun migrate(context: Context, dao: AppDao): Boolean {
        val src = CacheDirs.coverCacheDir(context)
        val dst = CacheDirs.coverFilesDir(context)
        val files = src.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return true // 无旧产物，无需迁移
        val ok = dst.mkdirs() || dst.isDirectory
        if (!ok) return false
        var copied = 0
        for (f in files) {
            val target = java.io.File(dst, f.name)
            if (runCatching { f.copyTo(target, overwrite = true) }.isSuccess && target.exists()) copied++
        }
        if (copied != files.size) return false // 有文件复制失败：保留旧目录，避免悬空
        // 全部复制成功 → 改写库前缀；改库失败则不删旧目录（旧文件仍在，显示不受影响）
        if (runCatching { dao.migrateCoverCachePrefix() }.isFailure) return false
        runCatching { src.deleteRecursively() }
        return true
    }
}
