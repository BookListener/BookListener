# Proposal

## Why

进入专辑（openAlbum）当前存在两次全量 `allSongs(workId)` 查询（封面懒解析、播放记录构建）+ 一次分页查询（列表），同一批数据被反复读取，100+ 曲目时明显卡顿。用户规格要求"进入专辑只查数据库、列表动态加载"，且"一次查询能搞定就不要做两次"。分页 + 全量并存属于过度设计：数据量（百级）远未达分页阈值，LazyColumn 本身惰性渲染，全量持有不伤 UI。

## What Changes

- **数据流统一**：进入专辑只做 1 次 `allSongs(workId)` 全量查询，同一份 `List<IndexedFile>` 供 UI 列表展示、播放队列构建复用。
- **删除分页**：移除 `loadAlbumSongsMore`/`songPage`/`_albumSongsEnded`/`songsLoading` 分页状态与滑底加载；DirectoryScreen 删除 loading 项与 snapshotFlow 滑底块。
- **封面零查询**：进入专辑读 `works.coverUri`（AlbumSummary 已带）；`resolveCurrentAlbumCover` 仅在 coverUri 为空时按需 `LIMIT 1` 提取，不作为每次进入的必做路径。
- **播放队列复用**：`enterAlbumWithRecord`/`playAlbumFrom` 从已持有 `_albumSongs` 构建队列，不再二次 `allSongs`。
- **规格更新**：SPECS.md "SQL 分页下推" 改为"一次全量查询、多消费方复用、LazyColumn 惰性渲染"。

## Impact

- `core/AlbumStore.kt`：songs(page) 废弃（主路径不再调用）
- `ui/PlayerViewModel.kt`：openAlbum/loadAlbumSongsMore/enterAlbumWithRecord/playAlbumFrom/resolveCurrentAlbumCover 数据流重构
- `ui/DirectoryScreen.kt`：滑底分页块删除，列表数据源变全量
- `db/AppDao.kt`：不变（allSongsByWork 即唯一查询）
- 测试：AlbumFlowTest 分页断言适配

## Out of Scope

- 扫描端封面入库补全（目录图片优先）——另开变更，本轮只做数据流重构
- 播放器队列机制、ExoPlayer 行为
