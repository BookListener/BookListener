# m4b-audiobook-chapters Specification

## Purpose
m4b 有声书支持：将 m4b 识别为可播放媒体，解析其内嵌章节（iTunes 章节表优先、chpl 兜底）生成章节虚拟轨（复用 CUE 虚拟轨机制），ALAC 音频全设备可播；无章节时退化为普通音频文件。

## Requirements

### Requirement: m4b 识别与标签

App MUST 将 `.m4b` 扩展名纳入可播放媒体判定（`AudioExt.SUPPORTED`），并将其纳入 MP4 ilst 标签解析（`ILST_EXTS`）：标题/作者/专辑/系列/序列号/封面与 m4a 同链路解析。存量 m4b 重扫生效，不做迁移。

#### Scenario: 扫描识别 m4b

- **WHEN** 目录内存在 `book.m4b`（含 `©nam`/`©ART`/`©ser`/`©sno`/`covr` 原子）
- **THEN** 该文件被识别为音频入索引；作品标题/作者/系列/序列号正确入库，主页按系列聚合

#### Scenario: 非 m4b 扩展名不受影响

- **WHEN** 目录内存在 `book.m4a`（同容器但非有声书章节）
- **THEN** 行为与现状完全一致（普通媒体条目，无章节解析）

### Requirement: 章节解析（iTunes 优先 + chpl 兜底）

App MUST 解析 m4b 内嵌章节标记，输出「起始毫秒 + 章节名」列表。解析优先级：**iTunes 章节表优先**（moov 内章节轨，经 tref/chap 引用定位，章节名取章节轨样本/ilst），**chpl 原子兜底**（moov/udta 内「时间点 + 名称」列表，时间轴按 movie timescale 换算毫秒）。两种形态都无 → 判定为无章节。

#### Scenario: iTunes 章节表优先

- **WHEN** m4b 同时含 iTunes 章节表与 chpl 原子
- **THEN** 章节列表取自 iTunes 章节表，chpl 被忽略

#### Scenario: chpl 兜底

- **WHEN** m4b 仅含 chpl 原子（无章节轨引用）
- **THEN** 章节列表取自 chpl（时间点换算为毫秒，名称取原子内文本）

#### Scenario: 无章节

- **WHEN** m4b 无 iTunes 章节表且无 chpl
- **THEN** 判定无章节，整本按普通音频文件入库

### Requirement: 章节虚拟轨与整本吸收

有章节 m4b：整本 MUST 被**吸收**（不入 entry_index），章节虚拟轨 MUST 入库（uri = `<整本uri>#track_<startMs>`，与 CUE 虚拟轨同一形态，displayName = 章节名），归属该书作品（1 作品 = 1 本书）。章节名为空时 MUST 以「第 N 章」兜底。

#### Scenario: 章节虚拟轨生成

- **WHEN** `book.m4b` 解析出 3 章（0ms/3600000ms/7200000ms，名「第一章/第二章/第三章」）
- **THEN** 生成 3 条虚拟轨（uri 分别为 `#track_0`/`#track_3600000`/`#track_7200000`），整本不单独显示为媒体条目，作品归属该书

#### Scenario: 空章节名兜底

- **WHEN** chpl 某章节无名称文本
- **THEN** 该章节名显示为「第 N 章」（N 为章节序号）

### Requirement: 播放与进度

章节虚拟轨播放语义 MUST 与 CUE 虚拟轨完全一致：播放 = 顺序播章（虚拟轨队列连续）；进度按章锚点（虚拟轨 uri 反查作品，work 级）；搜索按章节名可命中；内容文件列表仍显示整本 m4b 文件。

#### Scenario: 顺序播章与进度锚点

- **WHEN** 用户播放某 m4b 作品的第 2 章并退出
- **THEN** 重进恢复至第 2 章对应位置（虚拟轨 uri 定位，work 级进度），不丢失

#### Scenario: 搜索章节名

- **WHEN** 搜索「第三章」
- **THEN** 命中该书第 3 章虚拟轨（曲目结果）

### Requirement: ALAC 编码标记不支持（本版范围调整）

ALAC 编码的 m4b MUST 正常识别/入库/浏览；播放时若解码失败（设备无 audio/alac 解码器），App MUST 明确提示「此 m4b 可能为 ALAC 编码，当前版本暂不支持播放」。AAC m4b 播放 MUST 维持当前 MediaCodec 栈不受影响。范围调整依据（已核实）：评审拟引入的 `media3-decoder-alac` 依赖在 Google Maven / Maven Central 均不存在；Media3 无内置 ALAC 软解；全设备可播需第三方 FFmpeg 扩展，用户拍板本版标记不支持、后续单独排期。

#### Scenario: ALAC m4b 播放提示

- **WHEN** 在无 audio/alac 解码器的设备播放 ALAC 编码的 m4b（整本或章节虚拟轨）
- **THEN** 播放停止（isPlaying=false）且显示事件消息「此 m4b 可能为 ALAC 编码，当前版本暂不支持播放」

#### Scenario: AAC m4b 正常播放

- **WHEN** 播放 AAC 编码的 m4b 章节虚拟轨
- **THEN** 正常出声（当前 MediaCodec 栈），不出现 ALAC 提示

#### Scenario: 切歌/重试清除提示

- **WHEN** ALAC 提示后用户切换曲目或重试播放
- **THEN** 提示状态被清除，不残留

### Requirement: CUE 联动

`CueIndexer.WHOLE_EXTENSIONS` MUST 增 `"m4b"`：cue 引用 .m4b 整轨的同名扩展名探测可定位。

#### Scenario: cue 引用 m4b 整轨

- **WHEN** 目录内 `book.cue` 的 FILE 声明引用 `book.m4b`
- **THEN** 同名探测可定位该整轨，cue 解析成功时正常吸收并生成虚拟轨
