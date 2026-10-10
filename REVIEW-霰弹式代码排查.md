# 霰弹式代码排查报告

> 日期：2026-10-07 ｜ 范围：`app/src/main/java` 全部 4,711 行 Kotlin
> 结论：A 级 5 项为典型霰弹（①已有漏改实证），B 级 3 项观察，C 级 2 项样板/清理。
> **收敛完成状态：A 级（v1.5.12）+ B 级/C 级（v1.5.13）已全部处置，102 UT 全绿，双包构建交付。**

## A 级：同一逻辑多份复制，改动必须多点同步（✅ 已收敛，v1.5.12）

### A1 进入条目视图+加载列表模板双份 — PlayerViewModel
- 位置：`scanFolder`（本地 SAF 扫描）与 `openSavedEntry` 网盘分支（目录浏览）逐行同构：
  `saveCurrentPlaybackProgress → markOpened → _currentFolderUri → launch { _inFolderView=true → IO 取 items → _folderItems → shouldRestartOnEnter 判定 → enterFolderWithRecord → showSheet → _eventMsg }`
- 实证：v1.5.7 加"同文件夹不暂停"判定时本地分支已改、网盘分支漏改（当时 openSavedEntry 无条件 enterFolderWithRecord）。
- 收敛：抽私有 `openEntryView(entryId, entryUri, loadItems, emptyMessage, showSheet, afterLoad)` 统一模板。

### A2 条目行渲染三份 — UI
- 位置：`SavedEntryRow` / `CurrentEntryCard`（DirectoryScreen）/ `FolderManageRow`（FolderManageScreen），各自复制"CoverImage 56dp + 作者(可选) + 目录名 + 副标题 + 来源标识"。
- 实证：v1.5.2 美化（封面 56dp/条目加高/作者行）需三处同步改。
- 收敛：抽共用条目头（封面+作者+名称+副标题槽+尾部槽），场景差异通过槽注入。

### A3 空态/提示文案 6 点分散 — UI + ViewModel
- 位置：
  - `DirectoryScreen`："目录内未发现可播放音频"、"尚未添加内容\n请在设置中添加播放文件夹或网盘内容"、"未找到匹配内容"
  - `FolderManageScreen`："尚未添加任何内容\n点击右上角 + 添加播放文件夹或网盘内容"
  - `PlayerDetailSheet`："还没有正在播放的内容" + 引导
  - `PlayerViewModel`："文件夹内未发现可播放音频"/"该目录未发现可播放音频"/"找到 N 个可播放音频"（eventMsg 两处）
- 收敛：`MessageCatalog` 常量集中；PlayerViewModel eventMsg 统一模板。

### A4 缓存目录管理两套 + 硬编码
- 位置：`Utils.getCacheDir`（audio_cache）与 `CacheManager`/`Constants.CACHE_DIR_NAME`（audio_cache）重复定义目录名；`CoverManager` 自建 `cacheDir/covers`（硬编码 "covers"）。
- 收敛：`CacheDirs` 唯一提供 `audioCacheDir(ctx)` / `coverCacheDir(ctx)`，三处引用同一来源。

### A5 AudioItem 构造散落 6 文件
- 位置：`FolderScanner` / `OpenFileItemFactory` / `LocalAudioSource` / `WebDavAudioSource` / `AliyunAudioSource` / `DirectoryScreen.fileItem`，字段缺省不一致（size=0 等）。
- 收敛：`AudioItemFactory.fromFolderFile/fromOpenFile/fromCloud/fromSearch` 统一缺省。

## B 级：模式相似、职责边界尚可（✅ 已处置，v1.5.13）

### B1 prefs-JSON 序列化模板三份 —— ✅ 已收敛
- 抽 `core/JsonPrefsListStore<T>` 基类（readAllOrNull / readAll / writeAll），三个 Store 全部套用：
  - `FolderProgressStore`：完整继承，parse/persist 模板消除
  - `SavedEntryStore`：列表序列化走基类；**legacy 迁移语义保留**（readAllOrNull 区分"新格式缺失/损坏"与"合法空数组"）
  - `EntryIndexStore`：Map 嵌套结构以 `Bucket(entryId, files)` 适配基类，行为等价

### B2 播放进度状态双份 —— ✅ 已评估，维持原状
- 结论：`ViewModel.lastSavedIndex/Uri` 是**增量持久化的去重游标**（位置或所属文件夹变化才写 prefs），与 `PlaybackController.queueIndex/currentItem`（播放层真实状态）职责不同——属标准 last-persisted-cursor 模式，**非霰弹重复**。强行合并会把持久化职责错注入播放层，或引入每 tick 读 prefs 的开销。科学结论：维持现状，不收敛。

### B3 网盘源 "Unknown" 兜底三处 —— ✅ 已收敛
- `core/Models.kt` 新增 `AudioMetaDefaults.UNKNOWN_ARTIST`，LocalAudioSource / WebDavAudioSource / AliyunAudioSource 三处统一引用，字符串零散清零。

## C 级：已收敛样板 / 收尾残留（✅ 已处置，v1.5.13）

### C1 封面管理（样板，已完成）
- `core/CoverManager` 单类 + `CoverImage`/`AlbumCover` 三处统一引用（详情/条目/底部按钮）。

### C2 凭证存储（样板，已完成）
- `CredentialStore` 单例封装 EncryptedSharedPreferences。

### C3 残留清理 —— ✅ 已收敛
- `DirectoryScreen.kt` 删除未使用 `import android.graphics.BitmapFactory`。

## 验收标准（收敛全部完成）
- 全部行为不变：102 个 UT 全绿（含既有回归）。
- 无重复模板残留：A1 双分支调同一方法；A2 三处渲染共用条目头；A3 文案单点定义；A4 目录单点定义；A5 构造单点定义；B1 三 Store 共用序列化基类；B3 元数据缺省单点定义。
- release/debug 双包构建成功交付：v1.5.12 / versionCode 25（A 级）、v1.5.13 / versionCode 26（B/C 级）。
