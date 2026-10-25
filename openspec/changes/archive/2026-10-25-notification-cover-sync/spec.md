# Spec

## 背景

通知/锁屏封面链路：`PlayableItem.coverUri` → `MediaItem.mediaMetadata.setArtworkUri` → `mediaSession.player.currentMediaItem.mediaMetadata.artworkUri` → 通知 largeIcon。

现状：三处播放队列构建（enterAlbumWithRecord / playAlbumFrom / resumePlaybackOfEntry）的 cover 全部来自条目封面 `entry.coverUri ?: 首曲内嵌`。嵌套目录场景条目封面为 null（findCover 只扫根层），导致通知无封面；而专辑列表已改用作品封面（v2.1.23 works.coverUri）。

## 规则

**播放队列封面 = 作品封面优先，缺失回退条目封面**：

```
cover = album.coverUri            // 作品封面（works.coverUri，AlbumStore 已带，与专辑列表同源）
      ?: entry?.let { coverManager.resolveAlbumCover(it.coverUri, albumCount, 首曲Uri) }   // 旧逻辑兜底
```

`resumePlaybackOfEntry` 用 `work.coverUri ?: ...`（work = WorksEntity）。

## 实现

- PlayerViewModel 三处 cover 表达式改为作品封面优先。
- PlaybackController 增加 `internal val currentArtworkUri: Uri?`（读 player.currentMediaItem 的 artworkUri；生产代码不依赖）。
- AlbumInteractionTest 新增用例：`updateWorkCoverByKey` 写作品封面 → AlbumStore 取摘要（断言 coverUri 可读）→ openAlbum → awaitQueueOwner → `currentArtworkUri == 作品封面`。
- 版本 versionCode 93 / versionName '2.1.24'。

## 验收

1. 新用例通过：通知栏封面（队列 artwork）== 作品封面。
2. 全量 UT 通过（271 用例）。
3. 编译通过；debug 包交付。
