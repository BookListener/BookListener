# UT 重构方案：从方法级到业务流程级

## 一、重构原则

| 维度 | 旧方式（废弃） | 新方式（采用） |
|---|---|---|
| **入口** | 直接调用内部方法（`WorkBackfill.buildWorks`、`CueParser.parse`、`VirtualTrackUri.parse`） | 从业务操作入口进入（`ScanCoordinator.scan`、`PlayerViewModel` 用户操作、`EntryIndexStore.save`） |
| **范围** | 单个函数的输入输出 | 完整业务流程：用户操作 → 内部处理 → 最终产物 |
| **断言** | 方法返回值 | 过程产物：数据库状态（works/entry_index/work_progress）、UI 状态、文件产物 |
| **价值** | 验证实现细节，重构即失效 | 验证业务行为，实现可自由替换 |

**核心判断**：一个测试如果把被测方法的内部实现改了就会失败，但用户行为没变，那它就是方法级测试，应废弃或重构。

---

## 二、当前 52 个测试文件分类处置

### A. 废弃（纯函数/方法级，无业务价值）— 10 个

| 文件 | 废弃原因 | 覆盖到哪个业务场景 |
|---|---|---|
| `WorkBackfillTest` | 直接调 `workKey`/`buildWorks`/`mergeWorks` | → 扫描聚合场景（ScanFlowTest） |
| `CueParserTest` | 直接调 `CueParser.parse` | → CUE 扫描场景（ScanCueFlowTest） |
| `CueTextDecoderTest` | 直接调编码探测 | → CUE 中文乱码场景（ScanCueFlowTest） |
| `VirtualTrackUriTest` | 直接调 `parse`/`isVirtual` | → 虚拟轨播放场景（PlaybackFlowTest） |
| `NaturalOrderTest` | 直接调自然排序 | → 曲目排序场景（ScanFlowTest） |
| `SortIndexedFilesTest` | 直接调 `sortIndexedFiles` | → 曲目排序场景（ScanFlowTest） |
| `FolderUriTest` | 直接调 uri 工具 | → 文件夹路径场景（SettingsFlowTest） |
| `LongTextLogicTest` | 直接调文本截断 | → UI 长文本场景（UiFlowTest） |
| `PlaybackSpeedTiersTest` | 直接调速度档位 | → 播放速度场景（PlaybackFlowTest） |
| `IndexedFileRoundTripTest` | 序列化往返 | → 数据持久化场景（ScanFlowTest） |

### B. 重构（入口偏低，需上移到业务操作）— 8 个

| 文件 | 当前入口 | 重构目标入口 |
|---|---|---|
| `ScanCueTest` | `EntryIndexStore.save`（传构造好的 IndexedFile） | `ScanCoordinator.scan`（传 SourceFile + 假标签读取器） |
| `ScanCueMixedTest` | 同上 | 同上，合并入 ScanCueFlowTest |
| `ScanUpsertTest` | 内部 `scan()` 辅助 | 保留，已接近业务入口 |
| `AlbumStoreTest` | 直接 DAO | 通过 `ScanCoordinator.scan` 产生数据后断言 AlbumStore 输出 |
| `SavedEntryStoreTest` | 直接 Store | 通过添加文件夹操作断言 |
| `WorkStoreTest` | 直接 Store | 通过播放操作断言进度读写 |
| `WorkEntryLookupTest` | 直接 DAO | 通过扫描+播放断言查找 |
| `EntryIndexTrackAssignTest` | 直接调 `assignTrackNumbers` | → ScanFlowTest 中断言曲目号 |

### C. 保留（已从业务/UI 入口）— 24 个

- **扫描协调器**：`ScanCoordinatorTest`（已从 `scan` 入口）
- **UI/ViewModel**：`AlbumInteractionTest`、`CoverClickWorkResolutionTest`、`MainScreenNavigationTest`、`NowPlayingBarTest`、`PlayerDetailSheetContentTest`、`PlayerDetailSheetEmptyStateTest`、`DirectoryScreenFolderViewTest`、`AlbumTitleModeTest`、`MarqueeTextTest`、`PlayerEnhancementsTest`、`PlayerViewModelConstructorRegressionTest`、`AppIconsSkipPreviousGeometryTest` 等
- **播放器**：`PlaybackQueueLogicTest`、`DefaultSourceResolverCacheToggleTest`
- **来源**：`WebDavAudioSourceListTest`、`WebDavMultiStatusTest`
- **封面**：`AlbumCoverRuleTest`、`CoverManagerTest`、`CoverScannerTest`
- **其他**：`HomeSectionsTest`、`SearchEntriesTest`、`FolderProgressLogicTest`、`LastSessionLogicTest`、`OpenFileItemFactoryTest`、`FolderScannerTest`

### D. 必须保留（迁移测试，无法用业务入口替代）— 6 个

`Migration23Test`、`Migration34Test`、`Migration45Test`、`Migration56Test`、`StoreMigrationTest`、`I18nResourcesTest`、`LanguageSubpageTest`

> 迁移测试验证数据库 schema 演进，是基础设施测试，不适用业务流程原则。

---

## 三、新测试组织方式：按业务场景组织

废弃方法级测试后，按**用户可感知的业务场景**组织测试文件。每个场景测试一个完整的"操作 → 产物"链路。

### 核心业务场景（新增/合并）

```
test/
├── flow/
│   ├── ScanFlowTest.kt              # 扫描全流程：添加文件夹 → 扫描 → 作品聚合 → 曲目排序
│   ├── ScanCueFlowTest.kt           # CUE 全流程：CUE+整轨 → 虚拟轨生成 → 整轨吸收 → 中文编码
│   ├── PlaybackFlowTest.kt          # 播放全流程：选曲 → 播放 → 进度保存 → 恢复 → 虚拟轨裁剪
│   ├── AlbumFlowTest.kt             # 专辑全流程：专辑列表 → 进入专辑 → 封面 → 多作者合并
│   ├── SettingsFlowTest.kt          # 设置全流程：添加来源 → 文件夹管理 → 路径显示 → 删除
│   ├── SearchFlowTest.kt            # 搜索全流程：搜索 → 结果 → 播放
│   └── CoverFlowTest.kt             # 封面全流程：内嵌封面 → 文件夹封面 → CUE虚拟轨封面
├── migration/                       # 迁移测试（保留不动）
├── ui/                              # UI 测试（保留，已从 ViewModel 入口）
├── player/                          # 播放器测试（保留）
└── source/                          # 来源解析测试（保留）
```

### 每个场景测试的结构规范

```kotlin
class ScanFlowTest {
    // 1. 搭建：假 Source + 假标签读取器 + 内存数据库
    // 2. 操作：调用 ScanCoordinator.scan(entryId)
    // 3. 断言产物：
    //    - entry_index 表内容（哪些文件入库、workId 是否正确）
    //    - works 表内容（聚合出几个专辑、songCount、时长）
    //    - AlbumStore 输出（用户看到的专辑列表）
    //    - 错误字段（tag 解析失败的 error 记录）
}
```

**断言只看产物，不看中间方法返回值。**

---

## 四、关键场景测试用例清单

### ScanFlowTest（扫描全流程）

| 用例 | 操作 | 产物断言 |
|---|---|---|
| 普通音频文件夹 | scan 3 首带 tag 的 flac | works 聚合出 1 个专辑，songCount=3，entry_index 3 行 |
| 无 tag 音频 | scan 无 tag 的 mp3 | works 走 entry: 兜底，title=文件夹名 |
| 非媒体文件混入 | scan flac + jpg + txt | jpg/txt 入库但 workId=null，不产生专辑 |
| 过小/过短音频 | scan 50KB / 5秒 文件 | 被跳过，不入库 |
| 隐藏文件夹 | scan .hidden/secret.mp3 | 隐藏子文件夹内文件不入库 |
| 曲目排序 | scan disc2/track1 + disc1/track2 | sortRank 按 disc→track→名称自然序 |
| 重扫稳定性 | 连续 scan 两次相同内容 | workId 不变，进度不丢 |
| tag 解析失败 | tagReader 返回 null | 文件名兜底显示，error 字段记录 |

### ScanCueFlowTest（CUE 全流程）

| 用例 | 操作 | 产物断言 |
|---|---|---|
| CUE+整轨标准 | scan cue + flac | 整轨不入库，2 条虚拟轨入库，归入 CUE 专辑 |
| CUE 中文 GBK | scan GBK 编码 cue | 中文标题/专辑名正确，不乱码 |
| CUE+普通音频混合 | scan cue+整轨 + 独立flac | 两个作品：CUE专辑（虚拟轨）+ 独立flac专辑 |
| CUE FILE 不匹配 | cue 引用不存在的文件 | 整轨保持普通条目，cue_sheets 记录但无 audioUri |
| 多 CUE 多整轨 | scan 2 cue + 2 整轨 | 各自吸收，互不干扰 |
| CUE 无 TITLE | cue 无专辑名 | 虚拟轨走 entry: 兜底，title=文件夹名 |

### PlaybackFlowTest（播放全流程）

| 用例 | 操作 | 产物断言 |
|---|---|---|
| 播放保存进度 | 播放到 1:23 后切歌 | work_progress 记录 positionMs=83000 |
| 恢复播放 | 重新进入专辑 | 从上次进度继续 |
| 虚拟轨播放 | 播放 CUE 虚拟轨 | ClippingConfiguration 裁剪窗口正确，进度按虚拟轨计 |
| 播放队列切换 | 从专辑A切到专辑B | ownerId 切换，当前播放项正确 |

### AlbumFlowTest（专辑全流程）

| 用例 | 操作 | 产物断言 |
|---|---|---|
| 同专辑多作者 | scan "周杰伦" + "周杰伦 with 阿信" 同专辑 | 合并为 1 个作品，songCount 累加 |
| 跨文件夹同名专辑 | 两个文件夹各有同专辑 | 合并为 1 个作品（local: key） |
| 专辑封面 | scan 带内嵌封面的音频 | works.coverUri 正确，专辑列表显示封面 |

---

## 五、迁移步骤（分阶段，不破坏现有测试）

### 阶段 1：新增场景测试（不删除旧测试）
1. 创建 `flow/` 目录，先写 `ScanFlowTest` 和 `ScanCueFlowTest`
2. 确保新测试覆盖旧方法级测试的核心断言
3. 全量测试通过

### 阶段 2：废弃纯函数测试
1. 删除 A 类 10 个文件
2. 确认删除后覆盖率无关键缺口（新场景测试已覆盖）
3. 全量测试通过

### 阶段 3：重构数据层测试入口
1. 将 B 类 8 个测试的入口上移到业务操作
2. 合并重复场景（如 ScanCueTest + ScanCueMixedTest → ScanCueFlowTest）
3. 全量测试通过

### 阶段 4：清理与文档
1. 更新测试目录结构
2. 在 SPECS.md 中补充"测试规范"章节
3. 最终全量回归

---

## 六、不做的事

- **不追求 100% 覆盖率**：业务流程测试天然覆盖多个方法，覆盖率会自然上升，但不为覆盖率写测试
- **不写 mock -heavy 的测试**：优先用内存数据库 + 假 Source/假读取器，不 mock 内部类
- **不测试 UI 像素**：UI 测试只断言状态和行为，不测具体像素值（几何测试除外）
- **不废弃迁移测试**：schema 演进是基础设施，必须独立验证
