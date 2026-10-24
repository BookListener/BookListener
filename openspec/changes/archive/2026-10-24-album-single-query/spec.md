# Spec

## 背景

进入专辑时数据库读取存在重复：分页列表（songs）+ 封面懒解析全量（allSongs）+ 播放记录全量（allSongs）。规格要求进入专辑只查数据库、一次查询多次使用。本轮改为**一次全量查询，UI 与播放队列多消费方复用**。

## 数据流（目标态）

```
openAlbum(album):
  withContext(IO) { albumStore.allSongs(workId) }   ← 唯一 1 次数据库读取
  → _albumSongs.value = 全量结果                      ← UI 列表（LazyColumn 惰性渲染）
  → _albumSongsEnded = true（语义变为"一次加载完"）
  → 封面：album.coverUri（works 表已带，零查询；null 才 LIMIT 1 提取）
  → 播放队列：enterAlbumWithRecord 复用 _albumSongs 构建（不二次查询）
```

## 1. AlbumStore

- `songs(workId, page)`：从主路径移除调用；保留方法或标注废弃（供兼容，不在运行路径）。
- `allSongs(workId)`：不变，唯一查询入口。

## 2. PlayerViewModel

- `openAlbum`：删 `loadAlbumSongsMore()`、`resolveCurrentAlbumCover()`（全量版）；改为一次全量查询后直接赋值 `_albumSongs`，再调队列构建。
- 新增/改造：`buildAlbumQueue(album, items)`——从已持有 items 构建队列，供 enterAlbumWithRecord / playAlbumFrom 复用。
- `loadAlbumSongsMore` / `songPage` / `_albumSongsEnded` / `songsLoading`：删除（或 _albumSongsEnded 保留恒 true 以兼容 UI，优先删除 UI 引用）。
- `enterAlbumWithRecord(album)`：全量查询改为读 `_albumSongs.value`；队列构建逻辑不变。
- `playAlbumFrom(album, startIndex)`：读 `_albumSongs.value`（不再 allSongs）；空则回退一次全量（防御）。
- `resolveCurrentAlbumCover`：仅 `album.coverUri == null` 时触发，查询改 `LIMIT 1`（新增 `albumStore.firstSong(workId)`）。

## 3. DirectoryScreen

- 删除 `if (!albumSongsEnded)` loading item 块。
- 删除 `LaunchedEffect(songListState, albumSongs.size)` snapshotFlow 滑底加载块。
- `albumSongsEnded` 引用移除；列表渲染不变（itemsIndexed(albumSongs)）。

## 4. SPECS.md

- "专辑内排序改 sortRank 整数下标，SQL 分页下推" → "专辑曲目一次全量查询（sortRank 排序），UI 与播放队列多消费方复用；LazyColumn 惰性渲染，无需分页"。

## 验收

1. 进入专辑只触发 1 次 entry_index 查询（全量），无分页查询、无二次全量。
2. UI 列表完整显示全部曲目，滚动流畅（LazyColumn 惰性渲染），无"加载更多"项。
3. 点击歌曲播放：队列与列表同源（同一份数据），曲目定位正确。
4. 正在播放的专辑重新进入：不重启队列、不打断播放（沿用 shouldRestartForWork）。
5. 封面：coverUri 非空零查询；为空仅 LIMIT 1 提取，不重复全量。
6. 全量 UT 通过（含 AlbumFlowTest 适配）。
