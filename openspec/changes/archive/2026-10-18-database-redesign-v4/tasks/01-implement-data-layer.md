# 实施：数据层（works/work_progress 实体与 DAO）

## 任务

1. 新增 `db/WorkEntities.kt`（WorkEntity/WorkProgressEntity）+ `db/StoreEntities.kt` 加 workId/sortRank/sizeBytes/albumArtist + `db/DbEntities.kt` favorite+workId、CacheIndex 表名 cloud_cache。
2. 新增 `core/WorkBackfill.kt`（FolderProgress 迁入 + workKey/norm/buildWorks/mergeWorks/assignSortRank/resolveLegacyProgress 纯函数）。
3. 重写 `db/AppDao.kt`：works/work_progress CRUD、saveEntryIndexWithWorks 扫描事务、专辑查询 works 化、albumSongsByWork 按 sortRank 分页下推、cleanupOrphanWorks（先删作品再清进度）、recomputeWorkStats 统计派生重算、workId/entryId 反查。
4. 重写 `core/AlbumStore.kt`（AlbumSummary+workId、recentPlayed/notPlayed 分区、songs/allSongs by workId）+ `core/FolderProgressLogic.kt`（resolveStart 内容引用匹配）。
5. 删除 `core/FolderProgressStore.kt`；改造 `core/SavedEntry.kt`（IndexedFile+albumArtist/sizeBytes、save 走 saveEntryIndexWithWorks、updateDurationMs 联动重算作品时长）。

## 验收

- 主代码 + 测试编译通过。
- `app/schemas/4.json` 生成（8 实体，exportSchema=true）。
