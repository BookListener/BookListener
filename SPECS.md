# BookListener 关键规格记忆

> 本文件记录项目核心业务规则与概念边界，开发前必须先读。任何与本文件冲突的实现都是 bug。
> 最后更新：v2.2.4

---

## 一、核心概念边界（最高优先级，不可混淆）

### 1. 文件夹管理 ≠ 播放
- **文件夹管理**只负责添加/删除/浏览已固化的文件夹和网盘账号，**与播放无任何关系**。
- 文件夹管理页的文件列表**没有点击播放事件**，只显示原始文件（含 .cue），点击 .cue 弹出虚拟轨浏览（不关联播放）。
- 从文件夹管理进入目录后，标题显示目录名，不叫"内容管理"。

### 2. 播放和搜索只和专辑（作品）相关
- 所有播放入口都基于**专辑/作品**：主页专辑列表点击专辑、专辑曲目列表点击歌曲、搜索结果点击专辑或曲目。
- 播放队列的归属维度是 **workId**（作品级），不是文件夹/条目。
- 搜索结果只展示专辑和曲目，不展示文件夹。

### 3. 五个列表的正式命名（不可混用）
| 名称 | 含义 | 入口 |
|---|---|---|
| 专辑 | 作品聚合列表（主页） | 主页 |
| 曲目 | 单个专辑内的歌曲列表 | 点击专辑进入 |
| 文件夹管理 | 已添加文件夹/网盘的管理页 | 设置 → 文件夹管理 |
| 内容文件 | 文件夹管理内某个目录下的原始文件列表 | 文件夹管理进入目录 |
| 虚拟轨 | CUE 整轨解析出的曲目列表（ModalBottomSheet） | 内容文件中点击 .cue |

### 4. 主页专辑列表的系列分组（v2.2.1）
- 专辑聚合列表支持按系列名内联分组（规则见第十三节）；系列分组头不是新列表名称，专辑/曲目等五名称不变。

---

## 二、播放队列与作品定位

### 1. 播放队列 ownerId 两种形态
| 形态 | 产生场景 | 作品定位方式 |
|---|---|---|
| `"work:<workId>"` | 专辑播放（enterAlbumWithRecord / playAlbumFrom / resumePlaybackOfEntry） | 直接解析 workId |
| `null` | 搜索/单曲播放（playItems） | 从当前播放曲目 uri 反查 workId |

v2.1.9 已删除 `enterFolderWithRecord`（条目级队列 ownerId=entryId）——文件夹管理与播放无关，进入目录只展示文件列表不播放。`playItems` 会从起始曲目反查作品并设置 `_currentAlbum`。

### 2. 作品定位统一函数
- `PlayerViewModel.resolveCurrentWorkId(ownerId, currentPath)`：统一两种形态的作品定位。
  - `work:` 前缀 → 直接取 workId
  - 其余 → `workStore.workIdForUri(currentPath)` 反查
- `PlayerViewModel.shouldRestartForWork(workId)`：进入专辑时是否重启队列。
  - 正在播放同一作品且 `PlaybackController.queueReady` → **不重启（保持播放不暂停）**
  - 否则 → 重启（暂停并加载目标作品记录）

### 3. 禁止事项
- **禁止**用 `shouldRestartOnEnter("work:<id>")` 判断作品级重启——该方法内部把 `work:` 反查为 entryId，与 `"work:<id>"` 比较永远不等（v2.1.8 修复的 bug）。
- **禁止**用 `openEntryAlbum`（取条目下第一个作品）——已标记 `@Deprecated`，会跳错专辑。
- **禁止**在文件夹管理页添加播放事件。

### 4. 播放速度档位（v2.0.9 起）
- **7 档**：0.5x / 0.75x / 1.0x / 1.25x / 1.5x / 1.75x / 2.0x（`PlaybackSpeedTiers.TIERS`）。
- 循环：`next` 取"大于当前值的最小档"，末档 2.0 回卷到 0.5；非档位持久化值（脏数据）取最近大于它的档。
- 档位在进度条上**按序号均匀分布**：fraction = index/(size-1)（0.5→0%、2.0→100%），**禁止**按速度数值线性分布。
- Slider 以档位索引为值（valueRange 0..6、steps 5），thumb 位置与档位刻度严格对齐，拖动吸附最近档（索引 roundToInt），松手应用并持久化。
- 档位文字格式 "0.5x"/"1.0x"/"1.25x"/"1.75x"；当前档高亮（primary）+ 勾选，"当前 x.x x" 随拖动实时更新。

---

## 三、数据库与作品规则

### 1. workKey 规则（v2.1.5 后）
- 有 album tag → `"local:${norm(album)}"`（**不含 artist**，同专辑多表演者合并为一个作品）
- 无 tag / 网盘 → `"entry:${entryId}|${norm(folderName)}"`
- 旧格式 `"local:<artist>|<album>"` 仅存在于存量数据，重扫时由 `migrateOrphanWorkProgress` 迁移进度后清理。

### 2. 进度保存
- 统一用 `saveWorkProgress(path, index, positionMs)` → `workIdForUri(path)` 反查作品后保存。
- 不依赖 `_currentFolderUri` 或 `_currentAlbum` 定位作品。

### 3. 表结构
- `works`：作品聚合（album/artist/songCount/totalDuration/lastPlayedAt）
- `work_progress`：作品级播放进度（lastUri/lastIndex/lastPositionMs/matchMode）
- `entry_index`：条目下的原始文件索引（含虚拟轨行，UI 层需过滤）
- `cue_sheets`：CUE 整轨解析结果
- `saved_entries`：已添加的文件夹/网盘条目

### 4. 数据库版本（v2.1.10 = v6）
- v6 起 `entry_index` 增加 `relativePath`（相对路径，子文件夹文件）与 `error`（文件级错误原因）两列。
- 迁移链：MIGRATION_1_2 → 2_3 → 3_4 → 4_5 → 5_6。

### 5. 专辑曲目加载（v2.1.22 起）
- **进入专辑只查数据库，且一次全量查询、多消费方复用**：`openAlbum` 调用 `allSongs(workId)` 一次（sortRank 排序），同一份数据供 UI 列表（LazyColumn 惰性渲染）与播放队列构建复用。
- **不做分页**：专辑曲目量级（百级）未达分页阈值，分页 + 全量并存会引入重复查询；播放队列必须全量（ExoPlayer 机制），列表全量持有由 LazyColumn 惰性渲染兜底。
- 播放队列构建（`enterAlbumWithRecord`/`playAlbumFrom`）**复用已加载的 `_albumSongs`，禁止二次 `allSongs`**；搜索直达等未加载场景才回退一次查询。
- 封面：优先读 `works.coverUri`（零查询）；缺失时仅 `firstSong(workId)`（`LIMIT 1`）提取，禁止全量。
- 旧分页接口 `AlbumStore.songs(page)` 已废弃，不得用于运行路径。

---

## 四、设置侧链路架构（v2.1.10 重构）

### 1. 来源统一抽象
- `Source` 接口：`type` + `listFiles(rootUri)`，只负责列目录，**不做标签解析/索引写入**。
- `LocalSource`：SAF 递归列目录（maxDepth=10 防符号链接），记录相对路径。
- `WebDavSource`：递归 PROPFIND（Depth:1 逐层），返回所有文件（含非音频），记录相对路径。
- `SourceFile`：来源无关的文件描述（name/path/relativePath/isDirectory/sizeBytes）。

### 2. 扫描协调器
- `ScanCoordinator.scan(entryId, rootUri)`：统一调度"列目录→标签解析→CUE 收集→写索引→作品聚合"。
- `ScanCoordinatorFactory`：`forLocal()` / `forWebDav()`，差异只在 Source 与读取器。
- **禁止**在 ViewModel 中直接调用 FolderScanner 列目录或分散写索引；扫描一律走协调器。

### 3. 降级规则
- 单文件标签解析失败：文件名兜底显示，`error` 字段记录原因，文件行右侧错误图标，归入未知专辑。
- CUE 解析/读取失败：.cue 保持可见，点击显示失败原因，不生成虚拟轨、不吸收整轨。
- 整个目录无法读取：页面顶部显示整体错误（不做文件级标记）。
- 网盘实时列目录失败：回退已索引数据，顶部提示条区分实时/缓存。

### 4. 封面规则
- **文件夹不设封面**：文件夹管理条目行不显示封面（`EntrySummaryRow(showCover=false)`）。
- 专辑封面只从该专辑所在**当前目录**提取，**不跨子文件夹**找封面。
- 多专辑文件夹中每个专辑独立从本目录首曲提取内嵌封面。
- **v2.1.23 作品封面入库（works.coverUri）**：扫描时为每个作品解析封面并写入 works 表（专辑列表零 IO 显示）——专辑全部文件在同一目录（含扫描根）时，优先用该目录内封面图片（常见命名 cover/folder/front/AlbumArt\* → 任意图片，复用 `CoverScanner.PREFERRED_NAMES`）；无目录图片或**专辑跨子文件夹**（不查目录图片，防串图）→ 回退首曲内嵌封面。目录定位基于 `relativePath`，复用扫描文件列表，零额外 IO，本地与 WebDAV 统一生效。
- **v2.1.24 播放队列封面同源**：通知栏/锁屏封面（MediaItem artworkUri）与专辑列表同源——播放队列构建时**作品封面优先**（`album.coverUri`/`work.coverUri`），缺失才回退条目封面（`resolveAlbumCover`）。禁止只走条目封面（嵌套场景条目封面为 null 会导致通知无封面）。
- **v2.1.25 封面显示统一取源**：动作条/播放详情/封面点击入口一律 `currentPlaybackCover()`——**播放队列封面（_queueCoverUri，生命周期=播放队列）→ 当前专辑封面（_currentAlbum.coverUri）→ 条目封面（entry.coverUri）**。`openCurrentWorkAlbum`（动作条封面点击进入专辑列表）必须用 `work.coverUri` 优先，禁止绕回条目封面解析导致嵌套场景丢失；`exitAlbumView` 清空当前专辑后动作条封面仍取队列封面，不丢。
- **v2.1.26 封面存储目录持久化**：内嵌封面提取产物 MUST 写入 `filesDir/covers`（`CacheDirs.coverFilesDir`，与数据库同清理边界：清缓存不清、清数据/卸载连库一起删，消除"缓存清了库还在"的悬空）。`cacheDir/covers` 仅供 `CoverDirMigration` 读取旧产物：复制→改库（`migrateCoverCachePrefix` REPLACE 前缀改写）→删旧目录，prefs 幂等标记，任一步失败不删旧目录不悬空；`audio_cache` 留在 cacheDir 不动。

### 5. 内容文件列表
- 平铺显示所有文件（含子文件夹，通过 relativePath 标注），不做子文件夹导航。
- 非音频文件也入库（忠实显示）。
- 刷新按钮统一"实时列目录→更新索引→刷新显示"，本地/网盘一致。

---

## 五、CUE 虚拟分轨规则

### 1. 解析
- `CueParser`：多 FILE 段、INDEX 00/01、MM:SS:FF→ms、PREGAP 忽略、无 INDEX 跳过、末轨播到文件尾。
- `CueTextDecoder`：BOM 剥离 → 严格 UTF-8 → GB18030 回退 → UTF-8 replace 兜底（解决中文乱码）。
- `CueIndexer`：整轨吸收 + 退化（FILE 声明优先 + 同名探测 flac/wav/mp3/m4a）。

### 2. 虚拟轨标识
- `VirtualTrackUri`：`<整轨uri>#track_<起始ms>` 唯一 uri。
- 播放时用 Media3 `ClippingConfiguration` 裁剪窗口，零切割。

### 3. UI 行为
- 内容文件列表用 `VirtualTrackUri.parse(it.uri) != null` 过滤虚拟轨行，只显示原始文件。
- 点击 .cue → ModalBottomSheet 显示虚拟轨列表（浏览，不关联播放）。
- 专辑/曲目列表中虚拟轨正常显示和播放。

---

## 六、UI 设计规格

### 1. 二级页面标题
- 统一用 `titleMedium`（与定时关闭标题一致）。

### 2. 空态提示
- 统一 `bodyLarge` + `onSurfaceVariant` + 顶部 48dp + 水平居中 + 左右 24dp。
- 文案："尚未添加内容 / 请在设置中添加播放文件夹或网盘内容"。

### 3. 列表格线
- 所有列表行统一添加 `HorizontalDivider`（`modifier = Modifier.padding(horizontal = 10.dp)`）。
- 专辑聚合/专辑曲目/搜索结果/内容文件/虚拟轨列表均需格线。

### 4. 列表底部留白
- 所有可滚动列表加 `contentPadding = PaddingValues(bottom = 88.dp)`，防止被底部播放控制栏遮挡。

### 5. 设置页结构
- 主页仅列举配置项（图标+文字+右箭头，点击进入子页），**不放置任何开关**。
- 子页用返回箭头+标题+说明+配置项结构（同缓存子页）。
- 新增子项必须保持设计统一。
- **主题子页（v2.1.19）**：设置主页仅保留"主题"一个入口（含外观/玻璃/语言三区块），移除原"玻璃效果""语言"独立入口；子页自上而下为 外观模式 / 玻璃效果 / 语言 三区块，区块间用 HorizontalDivider 分隔。
- **外观模式**：白天/黑夜/自动 三选一，**图标化三元选择器 `SettingsTriSelector`（图标+文字+主题色下划线，v2.1.20 起，替代 RadioButton）**，默认自动；切档走 `AppCompatDelegate.setDefaultNightMode`（DayNight 重建换肤），持久化到 prefs，冷启动恢复；手动明暗下 Material You 动态色仍生效。
- **玻璃效果**：沿用 `glassActionBar`/`glassPlayerPanel` 两开关，逻辑零改动。
- **语言**：沿用三态（跟随系统/中文/英文），逻辑零改动。

### 6. 液态玻璃
- backdrop 应用于主页底部动作条、搜索框、弹窗。
- 搜索框不接入 backdrop（渲染自引用闪退），用半透明圆角毛玻璃风格。

---

## 七、国际化（i18n）

- 三态语言切换：跟随系统 / 中文 / 英文。
- `MessageCatalog` 集中管理文案，UI 层通过 `stringResource(MessageCatalog.XXX)` 引用。
- 基类 `BaseActivity` 应用语言配置，不绑定主题。

---

## 八、版本与构建规则

### 1. 版本号
- 未经人工确认的新改动**只递增第三位（patch）**，人工确认后才升第二位（minor）。
- versionCode 每次递增 1。

### 2. 构建
- **默认只构建 debug APK**，release（R8）构建需用户人工确认后再执行。
- 编译命令：`JAVA_HOME=/home/user/tools/jdk-21.0.12.1+1 ./gradlew assembleDebug -q`
- 测试命令：`JAVA_HOME=/home/user/tools/jdk-21.0.12.1+1 ./gradlew testDebugUnitTest`

### 3. Git
- 提交必须带身份：`git -c user.name=BookListener -c user.email=BookListener@local commit`
- 远程：`origin git@github.com:BookListener/BookListener.git`，分支 main。

---

## 九、标签解析与入库（v2.2.0 确立）

### 1. 解析器唯一后端（无降级）
- 本地扫描标签解析只走两条路径，**MediaMetadataRetriever 彻底退出扫描链路**：
  - `MetadataExtractor`：Media3 提取器直驱（MP3/FLAC/OGG + MP4 标准原子映射帧），同时产出时长（SeekMap）。
  - `Mp4IlstReader`：自研 MP4 ilst 原子树解析（moov→udta→meta→ilst→data），补齐 Media3 不可达的扩展原子 `©ser`/`©sno`/`stik`（m4a/mp4/alac 与 Media3 结果合并，原始键名全保留）。
- 解析失败不降级回退旧白名单：字段置空 + `error` 标记，显示名回退文件名（业务层语义）。

### 2. 字段入库范围（文档全字段）
- `entry_index` 结构化列：title/artist/album/albumArtist/genre/year/composer/track/disc/series/seriesNumber/mediaType/encoder。
- `entry_tag_extras` 新表：原始键值全量保留（ID3 帧 id / TXXX 键名 / MP4 原子名 + 值 + 来源），随索引整体替换，生命周期与 entry_index 一致。
- `works` 聚合列：series/seriesNumber/mediaType（作品层第一曲目值）。

### 3. 键名兼容
- TXXX 双风格：desc 非空（且非占位 `TXXX`）→ 键=desc；desc 为占位且 value 形如 `KEY:VALUE` → 按首冒号拆键值。
- stik 数值→语义名：2→Audiobook、9/10/11→Music 等（文档 §3 映射表）。

---

## 十、解码能力

- 当前栈：MediaCodec（系统/厂商注册实例接口）→ Media3 1.4.0。
- MediaCodec 不支持 APE（专有格式，生态选择非技术做不到）。
- FFmpeg 集成路线已定（源码裁剪 `ENABLED_DECODERS` 仅音频 + `EXTENSION_RENDERER_MODE_ON`，每 ABI 约 1–3MB），未落代码，待用户拍板。
- 无损格式下 FFmpeg 与系统解码输出 PCM 逐位一致，无质量差异。

---

## 十一、已知遗留问题（待清理）

1. `playItems`（搜索/单曲播放）ownerId=null，进度靠 uri 反查作品，功能正常但队列归属语义不统一。搜索单曲播放时用户就是想播这一首，ownerId=null 合理，暂不改造。
2. FFmpeg 集成（APE/DSF/DFF 解码）：架构路线已定，未落代码，待用户拍板。

---

## 十二、测试规范（v2.1.17 确立）

### 1. 测试分层原则
- **业务流程测试（flow/）**：从业务操作入口进入（`ScanCoordinator.scan`、`PlayerViewModel`），覆盖完整链路，断言数据库/UI 产物。这是测试的主体。
- **契约测试（core/、ui/、player/、source/）**：验证数据层 Store、UI 交互、播放器、来源解析的契约。入口可偏低，但必须验证可观察产物，不测纯函数。
- **迁移测试（migration/）**：数据库 schema 演进验证，必须保留，不适用业务流程原则。

### 2. 禁止事项
- 不写纯函数单测（如直接调 `workKey()`、`CueParser.parse()`、`VirtualTrackUri.parse()`）。这些方法的行为由业务流程测试间接覆盖。
- 不断言方法返回值，只断言过程产物（数据库行、UI 状态、文件）。
- 不为覆盖率写测试。

### 3. flow/ 目录场景清单
- `ScanFlowTest`：普通音频/无tag/非媒体/过小过短/隐藏文件夹/排序/重扫/tag失败
- `ScanCueFlowTest`：CUE整轨吸收/中文编码/混合目录/FILE不匹配/多CUE/无TITLE/读取失败/重扫稳定
- `PlaybackFlowTest`：进度保存/多专辑进度独立/重扫workId稳定
- `AlbumFlowTest`：同专辑多作者合并/跨文件夹合并/无tag兜底/多专辑独立/专辑名归一化

---

## 十三、主页专辑列表的系列分组（v2.2.1）

> 已实现（v2.2.1，2026-10-25）：经 grill-with-docs 评审定稿；实现与测试完成，草案标注移除。

### 1. 呈现
- 专辑列表（主页两分区）**内联按系列分组**：有系列的专辑聚合为「系列分组头 + 组内专辑行」，无系列专辑保持普通行。**不设系列页/独立系列视图**。
- 系列分组头不是新列表名称，五名称（专辑/曲目/文件夹管理/内容文件/虚拟轨）不变。

### 2. 分区语义（系列不跨分区）
- 系列内**任一**专辑播放过 → 整系列归「最近播放」分区；否则归「尚未播放」分区。
- 同一系列不会按专辑粒度在两分区各出现一次（保持系列原子性）。

### 3. 排序
- **最近播放分区**：统一按 `lastPlayedAt` 时间线交错（系列组位置＝组内最近播放时间，新→旧）；组内专辑按系列序号。
- **尚未播放分区**：统一键自然序交错（系列按系列名、无系列专辑按专辑名，混合字母序）；组内专辑按系列序号。
- **系列序号自然排序**：数字感知（"2"<"10"、"Vol 2"<"Vol 10"）；缺失/非数字排系列末，末内按专辑名自然序；同系列同序号按专辑名自然序破平。
- **分组键**＝归一化系列名（同专辑 workKey norm 规则），显示用原始首值。

### 4. 单专辑系列
- 只含 1 张专辑的系列**折叠**为普通专辑行：不显示分组头，行内副标题显示系列名，按专辑名参与分区排序。

### 5. 分组头交互
- 分组头含**系列封面**（v2.2.2）：取组内**最近播放专辑**的封面（封面规则同专辑行：作品封面优先、回退来源条目封面）；系列从未播放时回退组内排序第一张专辑封面。
- 分组头文字：「系列名 + 共 N 张」+ 收展箭头，**默认收起**，点击展开/收起。
- 收展状态**会话级**：ViewModel 持有，旋转/重建不丢，进程重启回默认收起；不落盘。

### 6. 查询
- 主页改**一次性全量加载 + 内存分组排序**，移除滑底翻页（同 v2.1.22「专辑曲目一次全量查询」先例，量级百级，LazyColumn 惰性渲染兜底）。

### 7. 数据源
- `works.series` / `works.seriesNumber`（v2.2.0 起入库，作品层首曲值）为唯一数据源，不新增表。
- v2.2.0 前存量作品 series 为空 → 归无系列专辑；**重扫生效**，不做迁移回填。

### 8. 边界
- 搜索**不新增**系列结果（只出专辑/曲目，见第一节）。
- 播放队列仍 work 级，系列仅为浏览维度，无系列级播放入口。

---

## 十四、删除条目联动与音频输出断开（v2.2.3）

### 1. 删除条目（文件夹）联动清理
- 删除文件夹管理中的条目时（clearSavedEntry）按序联动：
  1. **正在播放清理**：当前播放队列所属作品（resolveCurrentWorkId，队列归属维度 workId）属于被删条目 → PlaybackController.clearQueue()（停止播放、清空媒体项、MediaSession 通知移除、队列归属复位）。
  2. **数据清理**：删除条目内容索引；无文件引用的作品连同其 work_progress 进度由 cleanupOrphanWorks 收敛；本地封面缓存文件删除。
  3. **界面刷新**：主页专辑列表全量重查（refreshAlbums，专辑即时消失）；搜索框非空时结果同步刷新（索引已删）。
- 播放记录（work_progress）随其作品一起删除：作品仍被其他条目引用时保留（作品还在，仅收敛统计）。

### 2. 音频输出断开自动暂停
- 耳机拔出/蓝牙断开（音频路由切回扬声器，系统 ACTION_AUDIO_BECOMING_NOISY）→ **自动暂停**，不继续外放。
- 实现：Media3 ExoPlayer `setHandleAudioBecomingNoisy(true)`（v2.2.3）；恢复播放由用户手动点击播放按钮。

---

## 十五、m4b 有声书支持（v2.2.4）

> 已实现（v2.2.4，openspec: m4b-audiobook-chapters；ALAC 范围经用户拍板调整）。

### 1. 识别与标签
- `AudioExt.SUPPORTED` 增 `"m4b"`；`ILST_EXTS` 增 `"m4b"`（MP4 容器，Mp4IlstReader 现成解析 `©nam`/`©ART`/`©alb`/`©ser`/`©sno`/`covr`）。
- 存量 m4b **重扫生效**，不做迁移。

### 2. 章节虚拟轨（1 作品 = 1 本书）
- 有章节 m4b：整本被**吸收**（不入 entry_index），章节虚拟轨入库（uri = `<整本>#track_<startMs>`，与 CUE 虚拟轨同一形态），归属该书作品（标题/作者/系列/序列号取 m4b 标签）。
- 章节解析优先级：**iTunes 章节表优先**（moov 内章节轨 + tref/chap 引用 + 章节名样本），**chpl 原子兜底**；两者都无 → 整本退化为普通音频文件（同 CUE 退化语义）。
- 章节名空 → 以「第 N 章」兜底。

### 3. 播放与进度
- 播放 = **顺序播章**（虚拟轨队列连续）；进度按章锚点（虚拟轨 uri 反查作品，work 级），与 CUE 虚拟轨完全一致。
- 搜索：章节名可搜（虚拟轨是索引行）；内容文件列表仍显示整本 m4b 文件。

### 4. 解码（v2.2.4 实施范围调整）
- AAC m4b：当前 MediaCodec 栈直接支持。
- ALAC m4b：本版**标记为不支持**——正常识别/入库/浏览；播放时设备无 audio/alac 解码器则解码失败，提示「此 m4b 可能为 ALAC 编码，当前版本暂不支持播放」。
- 范围调整依据（已核实）：评审拟引入的 `androidx.media3:media3-decoder-alac` 在 Google Maven / Maven Central 均不存在（404），Media3 无内置 ALAC 软解；全设备可播需第三方 FFmpeg 扩展（APK 体积大幅增加），用户 2026-10-28 拍板：本版 ALAC 标记不支持，解码支持后续单独排期。

### 5. CUE 联动
- `CueIndexer.WHOLE_EXTENSIONS` 增 `"m4b"`：cue 引用 .m4b 整轨的同名扩展名探测可定位。
