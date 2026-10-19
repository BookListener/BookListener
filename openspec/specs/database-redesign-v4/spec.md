# database-redesign-v4 Specification

## Purpose

作品实体化 + 进度内容引用重构（v2.1.0，DESIGN-数据库重构-v4）。将"专辑"从查询时派生升为一等实体（works），播放进度锚点从"位置序号"改为"内容引用 (作品, 曲目 uri)"，老表停写保留。规格已实施并通过 256 用例回归。

## Requirements

### Requirement: 作品实体（works）

作品（专辑）MUST 作为一等实体存储：`works` 表含 `key`（唯一索引）/`title`/`albumArtist`/`sourceScope`/`coverUri`/`songCount`/`totalDurationMs`/`lastPlayedAt`/`updatedAt`。作品键 `key` MUST 遵循：本地有 album tag → `local:<norm(albumArtist ?: artist)>|<norm(album)>`（可跨文件夹合并）；无 tag / 网盘 → `entry:<entryId>|<norm(folderName)>`（不跨源合并）。`norm` = trim + 小写 + 连续空白折叠。

#### Scenario: 跨文件夹同名专辑合并

- **WHEN** 两个本地条目内含同名专辑"专辑甲"
- **THEN** 扫描后仅一个作品，songCount/时长累加（派生重算），albumCountInEntry=2，封面按多条目规则解析

#### Scenario: 无标签文件独立成作品

- **WHEN** 本地文件夹文件无 album tag / 网盘文件夹扫描
- **THEN** 按 `entry:<条目>|<文件夹名>` 独立作品，不与其他条目/来源合并

### Requirement: 扫描 upsert by key（防进度断链）

扫描写入 MUST 走 `saveEntryIndexWithWorks` 事务：逐文件算 key → upsert works by key（select-then-write，**禁止整删重建**，作品 id 稳定）→ 写 `entry_index`（含 workId/sortRank/sizeBytes/albumArtist）→ **派生重算**作品统计（songCount/时长 = 该作品全部引用行真实聚合，跨条目累加、条目缩容正确收敛）→ 清理失效行与无引用作品（连带进度）。作品统计 MUST NOT 由单次扫描覆盖写入（否则跨条目合并丢失贡献）。

#### Scenario: 二次扫描 workId 稳定

- **WHEN** 同一内容二次扫描（正常重扫路径）
- **THEN** works.id 不变，已存 work_progress 不丢（进度不得因扫描丢失）

#### Scenario: 跨条目合并统计正确

- **WHEN** 条目B 扫描同名专辑后
- **THEN** 该作品 songCount = 两条目曲目之和，totalDurationMs 为两条目之和

### Requirement: 作品级进度（work_progress）

进度 MUST 以作品为粒度单点存储：`work_progress` 主键 `workId`，含 `fileUri`（内容引用）/`orderIndex`（近似回退）/`matchMode`（1=uri 命中，0=近似）/`positionMs`/`speed`（每作品独立倍速）/`updatedAt`。恢复逻辑 `FolderProgressLogic.resolveStart` MUST：uri 命中 → 精确定位；uri 失配 → orderIndex 近似（clamp 到列表内）并保留位置；无记录 → (0,0)。

#### Scenario: 文件改名后恢复

- **WHEN** 曲目改名（uri 变化）但顺序不变
- **THEN** 按 orderIndex 近似定位到同位置（matchMode=0，UI 可提示"可能定位不准"）

#### Scenario: 每作品独立进度

- **WHEN** 单文件夹多专辑各自播放后
- **THEN** 每个专辑（作品）保留各自进度，不再互踩

### Requirement: 老表处置与回滚能力

`folder_progress` / `play_record` MUST 停写保留不删（回滚触发器，实体与 DAO 查询保留）；`cache_index` MUST 搬家为 `cloud_cache`（minSdk 26 无 RENAME，走建新表→搬数据→删旧表）；`favorite` MUST 增加 `workId` 列（收藏粒度升作品，映射不到保留 NULL 标"未知条目"，不静默删除）。`entry_index` MUST +4 列：`workId`/`sortRank`（条目内展示序下标，SQL 整数排序）/`sizeBytes`（二期指纹）/`albumArtist`。

#### Scenario: 老库升级

- **WHEN** 已安装 v3 用户升级 v2.1.0
- **THEN** MIGRATION_3_4 一次性回填（作品聚合/排序下标/老进度/收藏），不丢进度；folder_progress/play_record 数据保留

### Requirement: 迁移回填正确性

`MIGRATION_3_4` MUST 按序执行：① 结构 SQL + cache 搬家 ② works 回填（跨条目同 key 用 mergeWorks 合并，lastPlayedAt=MAX(条目)）+ entry_index.workId ③ sortRank（Kotlin 层按 AudioSortKey.byName 排序写下标）④ 老 folder_progress → work_progress（matchMode=0 保守）⑤ favorite.workId。回填逻辑 MUST 位于 `WorkBackfill` 纯函数（无 Android 依赖，JVM 可单测）。

#### Scenario: 迁移数据断言

- **WHEN** 手建 v3 库（两条目同名专辑 + 老进度 + 收藏 + cache 数据）迁移
- **THEN** 作品合并 songCount=3/时长 160s/lastPlayedAt=MAX；老进度映射到 uri://a2（matchMode=0）；cloud_cache 数据在、cache_index 表删除；收藏映射不到保留 NULL

### Requirement: 行为变更与验收

行为 MUST 与规格一致：单文件夹多专辑从"一个进度"变"每专辑各自进度"；同名专辑跨文件夹合并后进度合并为一个；文件大量改名退化为 orderIndex 近似并提示。验收 MUST 含：`exportSchema=true` 且 `app/schemas/4.json` 入库、全量单测回归通过、debug 包可安装。

#### Scenario: 真机验收

- **WHEN** 安装 v2.1.0（versionCode 70）升级老库并操作
- **THEN** 进度按 (作品, uri) 精确/近似恢复；二次扫描 workId 稳定；老用户升级不崩溃；全量 256 用例通过
