# Proposal

## Why

v2.1.23 作品封面（works.coverUri）已入库且软件内（专辑列表/详情）生效，但手机下拉框/锁屏通知仍无封面。根因（已核实）：播放队列的 coverUri 赋值走**条目封面**（`entry.coverUri ?: 首曲内嵌`），嵌套场景条目封面为 null；通知/锁屏封面取 `MediaItem.mediaMetadata.artworkUri` ← `PlayableItem.coverUri`，与专辑列表（works.coverUri）不同源。

## What Changes

- 播放队列封面统一**作品封面优先**（`album.coverUri` / `work.coverUri`），缺失回退条目封面逻辑。
- 三处队列构建同步修复：`enterAlbumWithRecord`、`playAlbumFrom`、`resumePlaybackOfEntry`。
- PlaybackController 加 internal 只读测试辅助 `currentArtworkUri`（与 `setStateForTest` 同惯例）。
- SPECS.md 封面规则补充 v2.1.23/24 两条。

## Impact

- `ui/PlayerViewModel.kt`：3 处 cover 表达式
- `player/PlaybackController.kt`：+internal 测试辅助
- 测试：AlbumInteractionTest 新增 `openAlbum_queueArtwork_usesWorkCover`（AlbumStore 真实入口 → openAlbum → 队列 artwork == 作品封面）
- 版本：2.1.23 → 2.1.24（patch）
