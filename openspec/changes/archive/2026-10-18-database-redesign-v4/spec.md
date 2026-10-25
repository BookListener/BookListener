# Spec: database-redesign-v4（作品实体化 + 进度内容引用）

> 依据：`DESIGN-数据库重构-v4.md`（用户 2026-10-18 拍板）+ proposal.md（status: implemented，v2.1.0/versionCode 69）。
> 后续修订已标注：
> - **v2.1.9 workKey 去 artist**（用户拍板，见 §2）；
> - **v2.1.22 专辑曲目加载改为一次全量查询、禁分页**（`album-single-query` spec）——本 spec §4"SQL 分页下推"仅适用于 v4 当时设计，已被取代，进入专辑只查一次数据库，多消费方复用。

## 1. 作品实体（works）

- 每个作品一行；`key` 唯一索引；`id` 自增主键（**禁止整删重建，防 workId 断链**）。
- `title` = album tag ?: 文件夹名（展示名）；`sourceScope` 收口枚举名（LOCAL/WEBDAV/ALIYUN）。
- `songCount` / `totalDurationMs` 扫描时缓存；`updateDurationMs`（播放后回写时长）增量同步更新，保持缓存一致。
- 索引：`key` 唯一 + `lastPlayedAt`。

## 2. 作品键（workKey）——含 v2.1.9 已确认修订

- **有 album tag** → `"local:${norm(album)}"`。
  - **修订（v2.1.9，用户拍板）**：原 DESIGN v4 §3.5 为 `"local:norm(albumArtist ?: artist)|norm(album)"`；因"同一专辑多个作者被识别为多个专辑"（bug 报告），键规则改为**纯 album**，同专辑多作者合并为一部作品。
- **无 tag / 网盘** → `"entry:$entryId|${norm(folderName)}"`：不跨 entry、不跨源合并（网盘不做标签解析，防不同盘同名文件夹撞车）。
- `norm` = trim + 小写 + 连续空白折叠为一个空格。

## 3. work_progress（作品级进度）

- `workId` 主键（每作品一个当前进度点；多断点属二期另建表，不改主键）。
- `fileUri` 内容引用（主键级）；`orderIndex` 为 uri 匹配失败时的近似回退。
- `matchMode` 诚实标注：`1`=uri 命中；`0`=按 orderIndex 近似（UI 可提示"可能定位不准"）。禁止静默指错曲目。
- `speed` 每作品独立倍速；`updatedAt`。

## 4. entry_index 追加列

- `workId`（可空，扫描后回填，指向 works.id，替代查询时 GROUP BY 聚合）。
- `sortRank`：扫描按 `AudioSortKey.byName` 排完序写整数下标；专辑内 `ORDER BY sortRank LIMIT/OFFSET`（SQL 分页下推，禁止全量取回内存排序）。
- `sizeBytes`（可空，二期内容指纹用）；`albumArtist`（可空）。
- 索引：`(entryId, workId)`、`(workId)`、`(entryId, sortRank)`。

## 5. 扫描写入（防断链核心）

```
scan(entryId, files)
  → 逐文件算 workKey
  → upsert works by key（id 稳定）
  → 查回 workId
  → 写 entry_index（workId, sortRank）
  → 清理该 entry 失效旧行；某 work 无任何文件引用 → 删该 work（连带进度）
```

## 6. 老表处置

- `folder_progress` / `play_record`：**停写保留不删除**（回滚能力）。
- `favorite`：保留 + `workId` 列；老 itemId 映射不到作品的保留原行标"未知条目"，**不静默删除用户数据**。
- `cache_index`：**搬家为 `cloud_cache`**（minSdk 26 = SQLite 3.18 无 `ALTER TABLE RENAME`，走建新表 → 搬数据 → 删旧表）。

## 7. 迁移 MIGRATION_3_4

- 结构 SQL（§5.1：加列、建 works/work_progress、cloud_cache 搬家、索引）→ 四步回填（works 跨条目合并 → entry_index.workId → sortRank + 老进度统一 `matchMode=0` → favorite.workId）。
- 回填逻辑抽 `WorkBackfill` 纯函数（无 instrumented 测试配置，JVM 单测覆盖全部回填语义）。
- `exportSchema = true`，`app/schemas/4.json` 入库。

## 8. 消费方

- `WorkStore` 替代 `FolderProgressStore`（load/loadMostRecentByEntry/workIdForUri/save/touchPlayed）。
- PlayerViewModel：进度保存按当前曲目反查 workId；条目恢复取**最近播放作品**；专辑队列 ownerId=`"work:<id>"`。

## 验收

- `ScanUpsertTest`（二次扫描 works.id 不变，防进度断链，最关键）、`WorkBackfillTest` / `Migration34Test` / `WorkStoreTest` / `AlbumStoreTest` 全绿。
- 全量 UT 通过；schema 4.json 入库；老 v3 库升级走 MIGRATION_3_4 不丢进度。
