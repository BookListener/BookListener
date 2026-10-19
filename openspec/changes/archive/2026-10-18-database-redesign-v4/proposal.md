---
id: database-redesign-v4
status: implemented
title: 作品实体化 + 进度内容引用（v2.1.0，按 DESIGN-数据库重构-v4 实施）
---

## Why

专辑在查询时派生（GROUP BY + 内存排序），播放进度锚点为"位置序号"（条目级），文件增删/排序变化后进度错位；无作品概念导致跨文件夹同名专辑无法聚合、专辑进度互踩。v4 文档（用户拍板）将作品升为一等实体、进度改为内容引用。

## Context

- 前置 grill 讨论（增量刷新/UUID id 方向）与 v4 方案并存，v4 为用户最终拍板实施依据；grill proposal 全部条目待确认，未采用，归档保留。
- 硬约束：minSdk 26（SQLite 3.18 无 RENAME / 无 ON CONFLICT DO UPDATE）；无 instrumented 测试（回填逻辑必须纯函数）；works upsert by key 防 workId 重建断链。

## What Changes

1. `works`（作品实体）+ `work_progress`（作品级进度，内容引用 + orderIndex 近似 + matchMode 诚实标注）。
2. `entry_index` +4 列（workId/sortRank/sizeBytes/albumArtist）；专辑内排序改 sortRank 整数下标，SQL 分页下推。
3. `saveEntryIndexWithWorks` 扫描事务：upsert works by key → 写索引 → 统计派生重算 → 清理孤儿（作品连带进度）。
4. 老表处置：folder_progress/play_record 停写保留；cache_index 搬家 cloud_cache；favorite +workId（映射不到保留 NULL）。
5. `MIGRATION_3_4` 四步回填（works 合并/排序下标/老进度/收藏），回填逻辑抽 `WorkBackfill` 纯函数。
6. 消费方：WorkStore 替代 FolderProgressStore；PlayerViewModel 进度按当前曲目反查 workId、条目恢复最近播放作品、专辑队列 ownerId=`work:<id>`。

## Out of Scope

增量刷新（mtime/size）、UUID id 体系、entry_index 加 id 列、全量改写刷新优化、独立快照表/懒回填——均属被搁置的 grill proposal，不在 v4 范围。

## How to Test

- 全量单测（Robolectric + Room in-memory）：256 用例通过；新增 WorkBackfill/WorkStore/ScanUpsert（防进度断链）/Migration34 四组。
- schema：`app/schemas/com.booklistener.db.AppDatabase/4.json` 已入库（exportSchema=true，8 实体）。
- 迁移：Migration34Test 手建 v3 库验证结构 + 回填数据断言。
- 交付：debug APK `BookListener-v2.1.0-20261019-debug.apk`（versionCode 70）。
