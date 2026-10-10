# 设计：标签解析 + 自然排序 + Room 持久化迁移

> 状态：**已批准**（用户拍板 Q1/Q2/Q3/版本，2026-10-07）
> 日期：2026-10-07 ｜ 参照：文档2《文件名自然排序实现分析》（Folder Player/Auxio）、文档7《Voice 标签解析与名称回退》
> 原则：**参照设计不照抄**——结合本项目（音乐播放器、三数据源、Compose+Media3）做差异化取舍。
> **用户确认结论**：Q1 音轨号优先、专辑号参与排序 ✅ ｜ Q2 去扩展名 ✅ ｜ Q3 网盘不做 tag、仅文件名自然序 ✅ ｜ 版本升 minor → **1.6.0 / versionCode 27** ✅

---

## 1. 目标

1. **标签解析**：本地文件解析内嵌标签（标题/艺术家/专辑/音轨号），**显示名与排序优先用标签标题，无标签回退文件名**。
2. **自然排序**：排序键（标签标题 ?: 文件名）全部走自然序（数字段按数值比），保证**列表显示顺序 == 播放顺序**。
3. **持久化切 Room**：三个业务 Store（SavedEntryStore / EntryIndexStore / FolderProgressStore）从 prefs-JSON 迁到 Room，**调用方 API 不变**。

## 2. 现状盘点（已核实）

| 项 | 现状 |
|---|---|
| AudioItem | `id/displayName/path/sourceType/size`；displayName = 文件名（含扩展名） |
| FolderScanner.scan | 递归 walk，**无排序**（顺序 = SAF 返回顺序） |
| 播放队列 | `PlaybackController.prepareFolder(items…)`，队列顺序 = 传入列表顺序 ⇒ **扫描顺序即播放顺序** |
| 已有标签能力 | `CoverManager` 用 MediaMetadataRetriever 提取首曲 ARTIST（仅作者，用于 SavedEntry.artist） |
| 持久化 | SavedEntryStore / EntryIndexStore / FolderProgressStore = prefs-JSON（JsonPrefsListStore 基类）；CacheIndex 已在 Room（music_db v1，表 play_record/favorite/cache_index） |
| 搜索 | `IndexedFile(displayName, uri)` 索引，displayName 现在存文件名 |

## 3. 设计方案

### 3.1 标签解析层：`core/TagResolver.kt`（参照文档7，差异化）

- **技术选型**：Media3 `MetadataRetriever`（项目已有 media3 1.4.0）。参照 Voice 用 `DefaultMediaSourceFactory(context, DefaultExtractorsFactory())` 且**不抽封面**（封面继续由 CoverManager 独立负责，职责不合并）。
- **字段映射**（本项目只需 3+1 个）：

| 标签 | ID3 (MP3) | Vorbis (FLAC/OGG) | Apple MDTA (M4A) | 本项目用途 |
|---|---|---|---|---|
| title | TIT2 | TITLE | com.apple.quicktime.title | **显示名 + 排序主键** |
| artist | TPE1 | ARTIST | com.apple.quicktime.artist | SavedEntry.artist（沿用） |
| album | TALB | ALBUM | com.apple.quicktime.album | 预留（暂不展示） |
| track | TRCK | TRACKNUMBER | com.apple.quicktime.tracknumber | 排序辅助键（可选，见 §6-Q1） |

- **名称回退**（参照文档7 §3.1）：**只有 title 强制回退**——`displayName = tagTitle ?: 文件名去掉扩展名`；artist/album 缺失保持 null。
- **差异化**：Voice 是有声书、忽略 TRCK；本项目是音乐播放器，**TRCK 作为可选的排序辅助键**（默认不用，见 §6）。
- **作用域**：**仅本地 SAF 文件**解析。WebDAV / 阿里云盘文件保持"文件名显示 + 自然排序"，不做逐文件网络 tag 解析（大目录会逐文件拉流，开销与失败率高，收益低）——明确的设计边界。

### 3.2 自然排序：`core/NaturalOrder.kt`（参照文档2算法，自研实现）

- **算法**：字符串切分为数字段/文本段交替 token；数字段**去前导零后比位数再比字面量**（避免 `toLong()` 20 位溢出）；文本段忽略大小写（`ignoreCase=true`，不做 Collator 依赖）；数字段恒在文本段前（与资源管理器一致）；前缀相同短者在前；数值相等时前导零少者在前。
- **自研差异**：
  1. 提供 `compare(a,b)` + 预分词 `sortKeyFor`（Chunk 序列）双入口；排序用预分词版（大目录性能，文档2 §4 同样结论）。
  2. 默认**不用 Collator**（文档2 里 useCollator=true 会造成"阿/张"按拼音排序，但也会引入 Locale 依赖与非确定性；本项目文件名多为曲目序号+标题，纯 ignoreCase 字典序即可，**保持确定性**，Collator 作为关闭项不实现）。
  3. **数字段限定 ASCII**（`c in '0'..'9'`），不用 `Character.isDigit`（全角数字 `１２３` 按文本段处理，避免混排歧义；文档2 的 Unicode 数字属可选增强，本项目不启用）。
- **排序键**：`displayName`（已含 tag→文件名回退，两者天然一致）。

### 3.2a 标签排序键层级（用户确认：音轨号优先 + 专辑号参与）

本地 SAF 文件排序采用 **disc → track → 名称自然序** 三级复合比较（专辑号存在时参与排序）：

| 层级 | 键 | 规则 |
|---|---|---|
| ① | 专辑号 disc（TRCK 的碟位 / Vorbis DISCNUMBER / MDTA tracknumber 碟位） | 双方都有 → 数值比；**一方缺 → 缺的排后**（标签完整的在前） |
| ② | 音轨号 track | 双方都有 → 数值比；一方缺 → 缺的排后 |
| ③ | displayName（tag title ?: 文件名去扩展名） | NaturalOrder 自然序 |

- 典型效果：`disc1/track1..10` 按 disc→track 排；无 tag 文件排在有 tag 文件之后，无 tag 之间按文件名自然序。
- 网盘 / MediaStore 曲库：无内嵌 tag 参与（网盘按 Q3 不解析），**仅 ③ 名称自然序**（MediaStore 曲库若取到 track 字段则同规则，见实施时定夺）。
- 排序出口统一在 scan / listDirectory 出口 ⇒ 列表顺序 == 播放顺序，结构性消除"两处排序不一致"。

### 3.3 数据模型变更（最小化）

- **AudioItem**：**不新增字段**。`displayName` 语义升级为"解析后显示名"（tag title ?: 去扩展名文件名）。所有使用点（列表行/详情/通知/搜索索引）自动受益。
- **SavedEntry**：不变（artist/coverUri 机制沿用；fileCount 不变）。
- **IndexedFile**：不变，但 displayName 存解析后名称 ⇒ 搜索可按歌曲标题命中。
- **FolderEntry**：不变。

### 3.4 排序与解析的集成（关键：显示顺序 == 播放顺序）

- 解析时机：`FolderScanner.scan` 扫描过程中**逐文件解析 tag**（IO 线程已由调用方保证）→ `AudioItemFactory.fromFolderFile` 之后设置解析后 displayName。
- 排序时机：**scan 完成后统一自然排序**（`NaturalOrder`），随后才进列表/队列 ⇒ 单点排序，`enterFolderWithRecord` 直接继承顺序，**不存在"两处排序不一致"问题**（文档2 强调的坑在本设计里结构性消除：排序只发生在 scan 出口）。
- 搜索索引 `indexStore.save(entry.id, items.map { IndexedFile(it.displayName, it.path) })` 已用 items 顺序 ⇒ 自动存解析后名称。
- 网盘源：`listDirectory` 返回后同样自然排序（文件名），统一排序出口。

### 3.5 Room 迁移（参照现有 music_db，差异化：API 兼容切换）

- **三张新表**（AppDatabase v1 → v2，Migration 建表）：

| 表 | 列 | 对应旧 Store |
|---|---|---|
| `saved_entry` | id PK, displayName, uri, sourceType, coverUri, fileCount, lastPlayedAt, artist, lastOpenedAt, **sortOrder**（保列表序） | SavedEntryStore |
| `folder_progress` | folderUri PK, trackIndex, positionMs, updatedAt | FolderProgressStore |
| `entry_index` | entryId, displayName, uri（entryId+uri 联合主键） | EntryIndexStore |

- **Store API 完全不变**（load/upsert/remove/replace/touchLastPlayed/touchOpened/save/all 签名照旧），**内部实现从 prefs-JSON 换 Room DAO** ⇒ ViewModel 调用点零改动（构造参数从 `SharedPreferences` 改为 `AppDatabase`）。
- **保序语义**：SavedEntryStore 写入沿用"整表替换"（DELETE+INSERT，sortOrder=列表索引），与 prefs 整写语义 100% 等价（replace 原位 / upsert 同 id 保持位置、新 id 追加末尾）。
- **首启迁移**：`core/StoreMigration.run(prefs, db)`（幂等，检测 prefs 有数据且对应表空 → 搬运 → 写迁移标记；**不删 prefs**，回滚安全）。在 MainActivity.onCreate 同步执行（数据量小、毫秒级），ViewModel 首次 load 即读到完整数据。
- **现有表不动**：play_record / favorite / cache_index 原样保留，仅加表（Migration(1,2) 建表 DDL）。

## 4. 改动文件清单

```
新增  core/TagResolver.kt            （Media3 MetadataRetriever 封装）
新增  core/NaturalOrder.kt          （自然排序：compare + sortKeyFor + byName）
新增  db/SavedEntryEntity.kt / FolderProgressEntity.kt / EntryIndexEntity.kt
新增  db/AppDao 扩展（saved_entry / folder_progress / entry_index 的 CRUD）
改    db/AppDatabase.kt             （entities + version=2 + Migration(1,2)）
改    core/FolderScanner.kt         （scan 内解析 tag + 出口统一自然排序）
改    source/WebDavAudioSource.kt / AliyunAudioSource.kt（listDirectory 出口自然排序）
改    core/SavedEntry.kt            （SavedEntryStore/EntryIndexStore 内部切 Room）
改    core/FolderProgressStore.kt   （内部切 Room）
改    ui/PlayerViewModel.kt         （三个 Store 构造参数换 AppDatabase；buildLocalEntry 用解析后 artist 沿用）
改    source/LocalAudioSource.kt    （MediaStore 曲库出口自然排序）
```

## 5. 测试计划（设计 UT 覆盖，参照文档2 §6 与文档7 回退语义）

| 测试 | 覆盖 |
|---|---|
| `NaturalOrderTest` | 文档2 14 项用例（1..11 数字跨度、多数字段、前导零、中英混排、超长数字不溢出）+ 本项目边界（全角数字按文本段、空串、.mp3 形式） |
| `TagResolverTest` | 有 title 用 title / 无 title 回退去扩展名文件名 / artist null / 解析失败不崩溃回退文件名 |
| `FolderScannerSortTest` | scan 后顺序 == 自然序；播放队列构建顺序 == 列表顺序（回归现有 DirectoryScreenFolderViewTest 语义） |
| `RoomStoreCompatibilityTest` | 三个新 Store 与原 prefs 语义等价：SavedEntry legacy 迁移语义保留、touch/remove/replace、进度"重启后仍在"（in-memory Room） |
| 既有回归 | 102 UT 全绿（MainScreenNavigation / DirectoryScreenFolderView / SavedEntryStoreTest 改造为 Room 版 / FolderProgressStoreTest 改造） |

## 6. 需人工确认项（已全部拍板）

| 项 | 决策 |
|---|---|
| Q1 音轨号/专辑号排序 | ✅ 有音轨号优先按音轨号排，专辑号存在时先按专辑号排（disc → track → 名称自然序）；缺 tag 的文件排后 |
| Q2 文件名回退 | ✅ 去扩展名显示（"01" 而非 "01.mp3"），与排序一致 |
| Q3 网盘 tag | ✅ 网盘不做标签解析，仅文件名自然排序 |
| Q4 版本 | ✅ 升级 minor → **1.6.0 / versionCode 27** |

## 7. 验收标准

- 本地文件夹：有 title tag 显示标签名、无 tag 显示去扩展名文件名；列表按 disc→track→名称自然序，播放顺序一致。
- 网盘源：文件名去扩展名显示 + 自然排序，行为稳定。
- 三个 Store 切 Room：全部既有功能与测试等价；首次升级数据自动迁移（旧数据不丢、可回滚）。
- 102 UT 全绿 + 新增约 20+ 用例；release/debug 双包交付（**1.6.0 / versionCode 27**）。
