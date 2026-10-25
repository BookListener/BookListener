# Proposal

## Why

用户实测：动作条"正在播放"点击封面进入专辑列表，或上滑动作条，会丢失专辑封面（变占位图）。深层排查（证据等级：已核实，代码路径逐一确认）：

1. **直接污染源**：`openCurrentWorkAlbum()`（动作条封面点击 → 进入专辑列表的唯一入口）手工构造 `AlbumSummary` 时封面走**条目封面解析**（`resolveAlbumCover(entry.coverUri, albumCount, null)`），绕过了作品封面 `works.coverUri`（v2.1.23 后专辑封面的权威源），且首曲参数传 `null` —— 嵌套/多专辑场景 `entry.coverUri` 为 null，表达式直接短路返回 null。`openAlbum` 随后同步覆盖 `_currentAlbum.value`，动作条与详情弹层封面立即变占位图；异步补救只走首曲内嵌（错图）或永远 null。
2. **放大因素**：`_currentAlbum` 内存态是动作条/详情封面唯一来源，`exitAlbumView` 清空后动作条回退条目封面（嵌套场景 null → 丢）。
3. 其余播放入口（playItems/enterAlbumWithRecord/playAlbumFrom/resumePlaybackOfEntry）均已同源作品封面，唯独动作条入口未收敛。

## What Changes

- `openCurrentWorkAlbum`：封面改 `work.coverUri`（works 表权威值）优先，缺失才回退条目封面解析。
- 新增播放队列封面状态 `_queueCoverUri`（生命周期=播放队列，跟随四个播放入口的 cover 表达式同步设置），动作条/详情封面改为统一取源：队列封面 → 当前专辑 → 条目封面。
- 修复"退出专辑视图后动作条丢封面"：`exitAlbumView` 清空 `_currentAlbum` 后，动作条/详情仍能从队列封面显示。
- SPECS.md 封面规则补充统一取源条文。

## Impact

- `ui/PlayerViewModel.kt`：openCurrentWorkAlbum 封面来源 + `_queueCoverUri` 状态 + 统一取源函数 + 四处队列构建同步
- `ui/MainScreen.kt`：动作条封面取源
- `ui/PlayerDetailSheet.kt`：详情封面取源（经 MainScreen 传参）
- 测试：AlbumInteractionTest 新增 openCurrentWorkAlbum 作品封面 + exitAlbumView 后动作条封面保留
- 版本：2.1.24 → 2.1.25（patch）

## 不纳入本次（结构性建议，另排）

内嵌封面入库走 `file://cacheDir/covers/*.jpg` 缓存路径，App 数据清除/卸载后失效。建议后续改为引用原文件读取或独立持久目录。
