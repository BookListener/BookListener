## 01-recognition-and-tags
# 实施：m4b 识别与标签（扩展名入列）

## 任务

- [ ] 1.1 `core/Constants.kt`：`AudioExt.SUPPORTED` 增 `"m4b"`。
- [ ] 1.2 `core/ScanCoordinatorFactory.kt`：`ILST_EXTS` 增 `"m4b"`（MP4 ilst 标签/系列/封面同 m4a 链路）。
- [ ] 1.3 `core/CueIndexer.kt`：`WHOLE_EXTENSIONS` 增 `"m4b"`（cue 同名探测可定位 m4b 整轨）。

## 验收

- 编译通过；扫描 m4b 文件入索引，标签/系列/封面与 m4a 同链路（flow 测试断言）。

## 02-chapter-parsers
# 实施：章节解析器（iTunes 章节表优先 + chpl 兜底）

## 任务

- [ ] 2.1 新增 `core/ChplChapterReader.kt`（纯函数）：定位 moov/udta/chpl 原子，解析「时间点 + 名称」列表，按 movie timescale（mdhd）换算毫秒；结构异常返回空（不抛）。
- [ ] 2.2 新增 `core/Mp4ChapterReader.kt`（纯函数）：优先 iTunes 章节表——解析 moov 内章节轨（tref/chap 引用定位 track_ID → tkhd/mdhd/stbl 样本时间戳 + 章节名样本/ilst）；章节轨缺失时回退 chpl。
- [ ] 2.3 统一输出 `data class Chapter(startMs: Long, title: String)`；空名由调用方兜底「第 N 章」。

## 验收

- 构造字节单测：chpl 原子最小结构（含 mdhd timescale 换算）与简化 iTunes 章节轨（tref/chap + stbl 时间戳）各至少 1 场景；畸形容错场景。
- 纯函数无 Android 依赖（JVM 单测可覆盖）。

## 03-scan-integration
# 实施：扫描链接入章节虚拟轨（吸收/归属/退化）

## 任务

- [ ] 3.1 `core/ResolvedTags.kt`：增 `chapters: List<Chapter>?` 字段（`fromEntries` 从 chpl 条目产出的章节；测试可直接注入）。
- [ ] 3.2 `core/ScanCoordinator.kt`：m4b 有章节 → 生成章节虚拟轨（IndexedFile，uri=`<整本>#track_<startMs>`，displayName=章节名，归属作品 tag），整本被吸收不入 entry_index；无章节 → 整本普通媒体条目。
- [ ] 3.3 章节名空 → 「第 N 章」兜底；`#track_` 前缀与 CUE 虚拟轨同源（播放裁剪窗口/进度锚点复用）。

## 验收

- flow 测试：注入带章节的标签扫描 → 断言章节虚拟轨产物（吸收、归属作品、退化路径）。
- 全量 UT 无回归。

## 04-alac-unsupported-notice
# 实施：ALAC 编码标记不支持（本版范围调整，经用户拍板）

## 任务

- [ ] 4.1 `PlaybackState` 增 `alacUnsupported`；`PlaybackController.onPlayerError` 对 m4b 解码器错误（DECODER_INIT_FAILED / DECODING_FAILED）置位，`onMediaItemTransition` 切歌/重试清除。
- [ ] 4.2 `PlayerViewModel` collect 消费（上升沿）→ 事件消息；`MessageCatalog` + strings（values/values-zh）增 `MSG_ALAC_UNSUPPORTED`。
- [ ] 4.3 文档同步：SPECS §十五第 4 条与 spec.md ALAC Requirement 标记范围调整及依据。

## 验收

- 编译 + 全量 UT 通过；ALAC m4b 播放失败显示明确提示（真机验证标注）；AAC m4b 播放不受影响。

## 05-tests-delivery
# 实施：全量验证与交付归档

## 任务

- [ ] 5.1 版本：`app/build.gradle` versionCode 100 / versionName `2.2.4`。
- [ ] 5.2 全量 UT（`testDebugUnitTest`）通过；`assembleDebug` 构建。
- [ ] 5.3 交付：`BookListener-v2.2.4-m4b.apk` 置于仓库根，经 present_files 交付。
- [ ] 5.4 SPECS §十五 移除「草案」标注；`openspec archive 2026-10-28-m4b-audiobook-chapters` 归档（spec 合入 `openspec/specs/`）。
- [ ] 5.5 git 提交（`-c user.name=BookListener -c user.email=BookListener@local`）。

## 验收

- 全量 UT 全绿；APK 核验 versionCode 100 / versionName 2.2.4；归档完成；提交完成。
