# BookListener 关键规格记忆

> 本文件记录项目核心业务规则与概念边界，开发前必须先读。任何与本文件冲突的实现都是 bug。
> 最后更新：v2.1.10

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

### 5. 内容文件列表
- 平铺显示所有文件（含子文件夹，通过 relativePath 标注），不做子文件夹导航。
- 非音频文件也入库（忠实显示）。
- 刷新按钮统一"实时列目录→更新索引→刷新显示"，本地/网盘一致。

---

## 四、CUE 虚拟分轨规则

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

## 五、UI 设计规格

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

### 6. 液态玻璃
- backdrop 应用于主页底部动作条、搜索框、弹窗。
- 搜索框不接入 backdrop（渲染自引用闪退），用半透明圆角毛玻璃风格。

---

## 六、国际化（i18n）

- 三态语言切换：跟随系统 / 中文 / 英文。
- `MessageCatalog` 集中管理文案，UI 层通过 `stringResource(MessageCatalog.XXX)` 引用。
- 基类 `BaseActivity` 应用语言配置，不绑定主题。

---

## 七、版本与构建规则

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

## 八、解码能力

- 当前栈：MediaCodec（系统/厂商注册实例接口）→ Media3 1.4.0。
- MediaCodec 不支持 APE（专有格式，生态选择非技术做不到）。
- FFmpeg 集成路线已定（源码裁剪 `ENABLED_DECODERS` 仅音频 + `EXTENSION_RENDERER_MODE_ON`，每 ABI 约 1–3MB），未落代码，待用户拍板。
- 无损格式下 FFmpeg 与系统解码输出 PCM 逐位一致，无质量差异。

---

## 九、已知遗留问题（待清理）

1. `playItems`（搜索/单曲播放）ownerId=null，进度靠 uri 反查作品，功能正常但队列归属语义不统一。搜索单曲播放时用户就是想播这一首，ownerId=null 合理，暂不改造。
2. FFmpeg 集成（APE/DSF/DFF 解码）：架构路线已定，未落代码，待用户拍板。
