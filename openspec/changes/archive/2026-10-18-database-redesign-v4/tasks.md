## 01-implement-data-layer
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

## 02-tests-build-archive
# 迁移、消费方、测试与交付

## 任务

1. `db/AppDatabase.kt` version=4 + `MIGRATION_3_4`（结构 SQL + cache 搬家 + 四步回填：works 跨条目合并 → entry_index.workId → sortRank → 老进度 → favorite.workId）。
2. `core/WorkStore.kt`（load/loadMostRecentByEntry/workIdForUri/save/touchPlayed）；`ui/PlayerViewModel.kt` 全量改接：进度保存按当前曲目反查 workId、条目恢复取最近播放作品、专辑队列 ownerId=`work:<id>`、时长补录走 getEntryIdByUri。
3. 测试：新增 WorkBackfillTest（纯 JVM）/ WorkStoreTest / ScanUpsertTest（防进度断链核心）/ Migration34Test（手建 v3 库回填断言）；重写 AlbumStoreTest / AlbumInteractionTest / FolderProgressLogicTest / DirectoryScreenFolderViewTest；更新 Migration23Test / StoreMigrationTest 迁移链。
4. 构建 debug APK（v2.1.0/70），git 提交，CHANGELOG 行为变更说明。

## 验收

- 全量 256 用例通过、0 失败。
- debug APK 可安装；老 v3 库升级走 MIGRATION_3_4 不丢进度。

