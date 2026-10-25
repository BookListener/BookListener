# Spec

## 背景

专辑封面存在三套来源（works.coverUri / entry.coverUri / 播放队列 coverUri），显示端各自取用无收敛。动作条封面点击进入专辑列表的入口（openCurrentWorkAlbum）绕过了作品封面权威源，导致嵌套/多专辑场景封面丢失。

## 权威源规则（统一取源，全 App 生效）

**专辑封面显示优先级 = 播放队列封面（_queueCoverUri） → 当前专辑封面（_currentAlbum.coverUri） → 条目封面（entry.coverUri）**

- `_queueCoverUri`：播放队列构建时的 cover（作品封面优先，v2.1.24 语义），生命周期=播放队列，不随 `_currentAlbum` 清空而丢失。
- `_currentAlbum.coverUri`：openAlbum/playAlbumFrom 设置的当前专辑摘要封面（来自 works.coverUri 或异步补救）。
- 回退 `entry.coverUri`：仅限以上均为 null。

## 实现

1. `openCurrentWorkAlbum()`：`coverUri = work.coverUri ?: coverManager.resolveAlbumCover(entry.coverUri, albumCount, null)`（回退逻辑保留；resolveCurrentAlbumCover 异步补救不变）。
2. PlayerViewModel 新增 `private val _queueCoverUri = MutableStateFlow<String?>(null)` + 公开只读 `queueCoverUri`。
3. 四个播放入口同步 `_queueCoverUri`：
   - `enterAlbumWithRecord`：cover 表达式结果
   - `playAlbumFrom`：cover 表达式结果
   - `resumePlaybackOfEntry`：cover 表达式结果
   - `playItems`：反查到的 `work.coverUri`
4. 新增统一取源函数 `fun currentPlaybackCover(): String? = queueCoverUri ?: currentAlbum.value?.coverUri ?: queueEntryForDisplay()?.coverUri`。
5. UI：
   - MainScreen 动作条封面 L284 → `playerViewModel.currentPlaybackCover()`
   - MainScreen 传详情弹层 albumCoverUri L367 → `playerViewModel.currentPlaybackCover()`
   - PlayerDetailSheet 内部 `albumCoverUri ?: entry?.coverUri` 不变（albumCoverUri 已含队列优先级）
6. SPECS.md 封面规则补"显示统一取源"条文。

## 验收

1. 新用例通过：
   - `openCurrentWorkAlbum`（动作条封面点击）→ currentAlbum.coverUri == 作品封面（works 值），嵌套场景不再 null
   - `exitAlbumView` 后动作条取源仍为队列封面（不丢）
2. 既有 271 用例全绿。
3. 编译通过，debug 包交付（2.1.25）。
