---
id: playback-speed-tiers-uniform
status: proposed
title: 播放速度档位：新增 1.75x + 进度条档位均匀分布（v2.0.9）
---

## Why

播放速度调节弹窗（SpeedSheet）的进度条档位按**速度数值线性**分布：0.5/0.75/1.0/1.25/1.5/2.0 六档中，1.5→2.0 数值差 0.5 是其他段的 2 倍，视觉上 2.0x 距 1.5x 特别远，观感不均。用户确认设计目标：**进度条是离散档位选择器，档位应按序号均匀分布**；并追加需求新增 **1.75x** 档（共 7 档）。

## Context

- `core/PlaybackSpeedTiers.kt`：档位数组 + 纯逻辑（next 循环 / nearest 吸附 / label 文字 / fraction 位置比例）。
- `ui/PlayerDetailSheet.kt` SpeedSheet：Slider（拖动吸附最近档、松手应用）+ BoxWithConstraints 绝对定位档位标签（文字中心与 thumb 行程刻度对齐）。
- 关键实现点：Slider 组件内部按 valueRange 线性定位 thumb，均匀分布必须把 Slider 改为**档位索引语义**（valueRange = 0..size-1），否则 thumb 与下方均匀标签错位。
- 用户偏好：新改动只升 patch（2.0.8 → 2.0.9，versionCode 63）；只构建 debug 包。

## What Changes

1. `PlaybackSpeedTiers.TIERS` 加 1.75x → 7 档：0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0。
2. `fraction(tier)` 由数值线性改为**按档位序号均匀**：index/(size-1)（0→0%、1/6、2/6、3/6、4/6、5/6、1→100%）；非档位值回退线性（防御性）。
3. SpeedSheet Slider 改喂档位索引：value = 当前档索引，valueRange = 0f..6f，steps = 5（7 个停靠位）；onValueChange 吸附索引 → dragValue = TIERS[index]；松手应用不变。
4. 测试升级：PlaybackSpeedTiersTest 7 用例（循环顺序含 1.75 / 吸附含 1.75 / label 1.75x / 均匀分布断言 / 不同屏宽下标签刻度对齐），233 全绿。

## Requirements

- **REQ-1（档位）**: 播放速度档位 MUST 为 0.5/0.75/1.0/1.25/1.5/1.75/2.0 共 7 档（v2.0.9 起含 1.75x）；`next` 循环取大于当前值的最小档、末档回 0.5；不在档位内的持久化值取最近大于它的档。
- **REQ-2（均匀分布）**: 档位在进度条上 MUST 按档位序号均匀分布（fraction = index/(size-1)：0%/16.7%/33.3%/50%/66.7%/83.3%/100%），不得按速度数值线性分布。
- **REQ-3（Slider 索引语义）**: Slider MUST 以档位索引为值（valueRange = 0..size-1、steps = size-2），thumb 位置 = index/(size-1)，与下方档位标签刻度严格对齐；拖动 MUST 吸附最近档、松手应用。
- **REQ-4（标签对齐）**: 档位文字中心 MUST 落在 Slider thumb 行程刻度上（trackStart + trackLen × fraction），并在不同屏宽下自适应（BoxWithConstraints + onSizeChanged 测宽）。
- **REQ-5（选中态）**: 当前档位文字高亮（primary 色）+ 勾选图标；"当前 x.x x" 文案随拖动实时更新。
- **REQ-6（验收）**: `testDebugUnitTest` 全绿（233 用例，含 PlaybackSpeedTiersTest 7 用例）+ assembleDebug 构建通过；真机验收：7 档等间距、1.75x 可用、拖动 thumb 落在档位刻度、2.0.9 版本号正确。

#### Scenario: 档位循环与脏数据防御

- **WHEN** 当前速度 1.5x 点击循环 / 持久化值为 1.1x（非档位）
- **THEN** 下一档为 1.75x / 取最近大于它的档 1.25x

#### Scenario: 均匀分布与刻度对齐

- **WHEN** 打开倍速弹窗，7 档标签渲染于 360dp/480dp 屏宽
- **THEN** 每档间距 = 行程 1/6，文字中心落在 thumb 刻度上，无重叠无错位

#### Scenario: 拖动吸附

- **WHEN** 拖动 Slider 至非档位位置（如 1.62x 对应索引 4.48）
- **THEN** 吸附到最近档（1.5x），thumb 停在对应均匀刻度，松手应用并持久化

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.9 APK，打开倍速弹窗
- **THEN** 7 档等间距、1.75x 可选、拖动吸附正确、选中勾标正确、版本号 2.0.9
