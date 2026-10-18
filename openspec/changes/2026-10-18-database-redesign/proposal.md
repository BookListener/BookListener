---
id: database-redesign
status: proposed
title: 数据库设计：增量刷新 + 标识体系 + 播放上下文识别（全部待确认）
---

## Status

> **本提案全部条目均为「待确认」**——仅记录当前讨论收敛出的方案，未定稿、未实施。逐条确认后方可归档为 spec。

## Why

现状数据层存在四类缺陷（已查证）：

1. **刷新机制为整批替换（全量改写）**：每次扫描无脑 `replaceEntryIndex` 全量重写该条目所有行——**无变更也改写数据库**，写入浪费且无变更检测能力。
2. **`AudioItem.id` 每次扫描由工厂实时派生**（`folder_$docId` 等前缀格式）：id 从不落库、带用途前缀、每次重算——无意义且与缓存索引耦合。
3. **文件夹与专辑在数据层未被识别**：`folder_progress` 只有条目级 key，专辑播放与文件夹播放共用同一条进度记录；恢复靠"取第一条有 album 的文件"猜测，真实专辑间进度互踩；播放上下文（Playback Context）概念不存在于数据模型。
4. **死代码层**：`play_record`（单曲级进度）/`favorite`（收藏）只有 `MediaRepository` 接口、无任何 UI 消费方。

## Context（现状事实）

- `entry_index`（主键 `entryId+uri`）：无 id 列、无 mtime/size 列；写入 = `replaceEntryIndex` 整批替换 + `assignTrackNumbers`。
- `assignTrackNumbers` 真实语义（v1.6.13）：**仅对无 tag 轨号的文件**按名称自然序在"无 tag 组内"赋 1..N；有 tag 文件保持标签原值；幂等。
- 排序在代码层（`NaturalOrder`：专辑号→音轨号→名称自然序）；`albumSongs` 查询无 ORDER BY；无 SQL 依赖 track 排序。
- `AudioItem.id` 消费方：`cache_index.itemId`（真实功能）、`play_record`/`favorite`（死表）。`folder_progress` 主键 = 条目级 folderUri。
- 专辑兜底：`COALESCE(i.album, s.displayName)`——无 tag 文件归入"文件夹名专辑"。
- WebDAV 缓存 id 为 `href.hashCode()`（不可逆）。

## What Changes（方案，全部待确认）

### A · 标识体系：id = UUID，首次入库生成、落库读回【待确认】

- 生成：`UUID.randomUUID().toString()`（无参），**仅首次入库时生成一次**。
- 存储：`entry_index` 加 `id` 列（与 B 的 mtime/size **同一批结构扩展**）。
- 刷新下行为：新增 → 生成 + insert；未变更/变更 → **从库读回 / 保持**；删除 → 删行。**永不重新生成**。
- 已入库旧行：id 列为空 → **升级后首次刷新统一补一次**（一次性、非破坏）。
- 缓存：`cache_index.itemId` 新写入 = UUID；旧行不动、evict 自然清；缓存**文件名**用 path 的安全化 hash（磁盘命名，与索引 key 解耦）。

### B · 刷新机制：增量更新（变更驱动）【待确认】

- **全量改写（整批替换）淘汰**；原则：**无变更 → 零扫描解析、零写库**。
- `entry_index` 加 `mtime/size` 两列（一次性结构扩展；**快照 = 表本身**，扫描副产物写入）。
- 每次扫描：列举 `(path, mtime, size)` → diff 表内 → 新增 `insert`+解析 / 删除 `delete` / 修改 `update`+重解析 / 未变更**零写**。
- 孤儿：diff 的 delete 收敛。
- WebDAV：用 PROPFIND 的 last-modified/size 检测，机制与本地统一、不双轨。

### C · 轨号：落库（方案 A）【待确认】

- `assignTrackNumbers` 维持现状语义（无 tag 组内名称序赋号，有 tag 保原值，幂等）。
- 增量下轨号成本：仅"无 tag 组"成员增删时重算组内；有 tag 行永不动。
- 分页友好：每行自带轨号，`albumSongs` 分页直接取行。

### D · 双进度体系：清理死接口，表保留为预留【待确认】

- 删除 `MediaRepository` 死接口（`favorites/playRecords/savePlayRecord`，git 可恢复）。
- `play_record` / `favorite` 表**保留为预留**（结构不动）。

### E · 播放上下文：统一识别【待确认】

- `folder_progress` 加 `contextType`（FOLDER/ALBUM）+ `contextKey`（专辑名 或 cuePath）。
- 文件夹播放 = FOLDER+null；真实专辑 = ALBUM+专辑名；cue 专辑 = ALBUM+cuePath。
- 恢复：按"条目 + 上下文"精确定位，不再猜测。

### F · 专辑兜底：保留兜底 + 明确语义【待确认】

- 保留 `COALESCE(album, displayName)`。
- GLOSSARY/注释明确："文件夹名专辑"是**视图回退**，不是真实专辑。

## 挂起分支（cue 落点，未最终拍板）

- cue 虚拟轨 id = UUID（独立生成，天然与整轨不同）；轨号 = CUE `TRACK n`，不参与 `assignTrackNumbers`。
- cue 专辑 = ALBUM 上下文 + cuePath 为 contextKey（与真实专辑统一，无特殊机制）。

## 未决点

- C 轨号"落库重排"为推荐方案；"即时生成"因分页矛盾被否（序号依赖无 tag 组全量成员，分页无法即时计算）。
- A 旧行补 id 的时机与方式（升级后首次刷新统一补一次）待确认。
