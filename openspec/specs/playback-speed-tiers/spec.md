# playback-speed-tiers Specification

## Purpose
TBD - created by archiving change playback-speed-tiers-uniform. Update Purpose after archive.

## Requirements

### Requirement: 播放速度档位（7 档）

播放速度档位 MUST 为 0.5x / 0.75x / 1.0x / 1.25x / 1.5x / 1.75x / 2.0x 共 7 档（v2.0.9 起含 1.75x）。`next` 循环 MUST 取"大于当前值的最小档"，末档（2.0）回卷到 0.5；不在档位内的持久化值（脏数据防御）MUST 取最近大于它的档。档位文字 MUST 为 "0.5x"/"1.0x"/"1.25x"/"1.75x" 格式。

#### Scenario: 档位循环顺序

- **WHEN** 当前速度 0.5x，循环切换到下一档
- **THEN** 依次为 0.75x → 1.0x → 1.25x → 1.5x → 1.75x → 2.0x → 回卷 0.5x

#### Scenario: 非档位值防御

- **WHEN** 持久化速度为 1.1x（非档位值）
- **THEN** 取最近大于它的档 1.25x

### Requirement: 档位均匀分布

档位在进度条（Slider 行程）上 MUST 按档位序号**均匀**分布：fraction = index/(size-1)，即 0.5→0%、0.75→16.7%、1.0→33.3%、1.25→50%、1.5→66.7%、1.75→83.3%、2.0→100%。禁止按速度数值线性分布（否则 1.5→2.0 视觉间距是其他段 2 倍）。

#### Scenario: 均匀位置

- **WHEN** 打开倍速弹窗，计算各档位置比例
- **THEN** 每档间距固定为行程的 1/6

### Requirement: Slider 索引语义与吸附

Slider MUST 以档位索引为值：valueRange = 0..(size-1)、steps = size-2，thumb 位置 = index/(size-1) 与档位标签刻度严格对齐。拖动 MUST 吸附最近档（索引 roundToInt 取整），松手（onValueChangeFinished）MUST 应用并持久化。

#### Scenario: 拖动吸附

- **WHEN** 拖动 Slider 至非档位位置（对应索引 4.48）
- **THEN** 吸附到最近档索引 4（1.5x），thumb 停在对应均匀刻度，松手应用

### Requirement: 标签刻度对齐与自适应

档位文字中心 MUST 落在 Slider thumb 行程刻度上（trackStart + trackLen × fraction − textWidth/2），在不同屏宽下 MUST 自适应（BoxWithConstraints 取 maxWidth 换算 trackLen + onSizeChanged 测量文字宽），无重叠、无错位。

#### Scenario: 多屏宽对齐

- **WHEN** 360dp / 480dp / 568dp 屏宽渲染 7 档标签
- **THEN** 每个文字中心均落在对应 thumb 刻度上

### Requirement: 选中态与实时反馈

当前档位文字 MUST 高亮（primary 色）+ 勾选图标；"当前 x.x x" 文案 MUST 随拖动实时更新。

#### Scenario: 选中高亮

- **WHEN** 当前速度 1.75x 打开弹窗
- **THEN** "当前 1.75x" 文案显示，1.75x 标签高亮并带勾

### Requirement: 验收

`testDebugUnitTest` MUST 全绿（233 用例，含 PlaybackSpeedTiersTest 7 用例：循环顺序/吸附/文字/均匀分布/多屏宽对齐）+ assembleDebug 构建通过；版本号 MUST 为 2.0.9（versionCode 63）；真机验收 MUST 覆盖：7 档等间距、1.75x 可选、拖动 thumb 落在档位刻度、选中勾标正确。

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.9 APK 操作倍速弹窗
- **THEN** 7 档等间距排列、1.75x 可用、拖动吸附正确、勾标正确、版本号 2.0.9
