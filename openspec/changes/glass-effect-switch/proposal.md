---
id: glass-effect-switch
status: proposed
title: 毛玻璃特效组件级开关（v2.0.10，L2 粒度 + 纯色降级）
---

## Why

用户确认：App 需支持"打开和管理毛玻璃特效"——L2 组件级开关（主页动作条玻璃 / 播放详情面板玻璃各自独立开关），关闭后降级为纯色表面（surfaceContainerLow）。

## Context

- 毛玻璃是绘制指令（`drawBackdrop` + blur/lens/alpha），可用设置状态驱动"应用 or 回退"，无需改合成树结构。
- 现状：主页动作条（MainScreen.kt:165 `drawBackdrop`）与播放详情面板（PlayerSheetGlassPanel.kt:280 `drawBackdrop`，backdrop 由 MainScreen:359 透传）为仅有两处真玻璃；PlayerSheetGlassPanel 已有 `backdrop == null → surfaceContainerLow` 回退分支可复用。
- 持久化模式：PlayerViewModel `prefs`（"player_prefs"）+ MutableStateFlow + putBoolean（fullFileCache 同款）。
- backdrop 层（rememberLayerBackdrop + Scaffold.layerBackdrop）常驻不动，开关只切换消费者分支。
- 版本：当前 2.0.9/63（v2.0.10 玻璃化已回退）；本改动升 patch 2.0.10/64；只构建 debug。

## What Changes

1. PlayerViewModel 新增两个持久化布尔状态（默认 true）：`glassActionBar`（主页动作条玻璃）、`glassPlayerPanel`（播放详情面板玻璃）+ setter。
2. SettingsScreen 新增两个 Switch 行（复用 fullFileCache 的 Row+Switch 模式），固定名称"主页动作条玻璃""播放详情面板玻璃"。
3. MainScreen：collect 两状态；动作条 Row modifier 条件化（开=drawBackdrop 玻璃 / 关=surfaceContainerLow 圆角纯色）；PlayerDetailSheet 调用点 `backdrop = if (glassPlayerPanel) backdrop else null`。
4. 开关实时生效（重组即应用，无需重启）；关闭时跳过 blur/lens 采样（低端机性能收益）。

## Requirements

- **REQ-1（组件级开关）**: App 设置页 MUST 提供两个独立开关"主页动作条玻璃""播放详情面板玻璃"，各自持久化（默认开启），互不影响。
- **REQ-2（降级样式）**: 任一开关关闭时，对应组件 MUST 降级为纯色表面（surfaceContainerLow、保持既有圆角/尺寸/布局），不得残留模糊或半透明采样。
- **REQ-3（实时生效）**: 开关切换 MUST 无需重启，重组后立即生效；不影响其余功能（播放、列表、导航）。
- **REQ-4（回归）**: 开关默认开启时两处玻璃参数与 v2.0.6d 定稿完全一致（blur10+lens40/72+surfaceVariant α0.30）；关闭→开启往返切换无异常。
- **REQ-5（验收）**: 233 单测全绿 + assembleDebug 通过；版本 2.0.10；真机验收两开关独立生效、降级纯色正确、往返切换无异常。

#### Scenario: 关闭主页动作条玻璃

- **WHEN** 设置页关闭"主页动作条玻璃"后返回主页
- **THEN** 底部动作条变为纯色表面（surfaceContainerLow 圆角条），不再模糊/折射背景；播放详情面板玻璃不受影响

#### Scenario: 关闭播放详情面板玻璃

- **WHEN** 设置页关闭"播放详情面板玻璃"后打开播放详情
- **THEN** 面板变为纯色表面（surfaceContainerLow），动作条玻璃不受影响

#### Scenario: 默认与往返

- **WHEN** 全新安装（默认开启）/ 开关来回切换
- **THEN** 默认两处均为定稿玻璃；往返切换实时生效、无异常、无需重启

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.10 操作两开关
- **THEN** 各自独立生效、降级纯色正确、往返无异常、版本 2.0.10
