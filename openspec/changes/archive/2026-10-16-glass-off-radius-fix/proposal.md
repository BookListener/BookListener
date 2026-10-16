---
id: glass-off-radius-fix
status: proposed
title: 播放详情面板关闭毛玻璃后保持顶部圆角（v2.0.13）
---

## Why

用户真机反馈：关闭毛玻璃特效后，播放详情面板（正在播放详情）顶部圆角消失（变直角），与开启时不一致。要求保持一样的圆角。

## Context

- 根因（代码实证）：`PlayerDetailSheet.kt` 玻璃开启分支 `drawBackdrop(shape = RoundedCornerShape(topStart/topEnd = 28.dp))`；关闭分支 `background(MaterialTheme.colorScheme.surfaceContainerLow)` **未传 shape** → 直角矩形。
- 主页动作条降级分支（MainScreen.kt）已带 `RoundedCornerShape(topStart/topEnd = 28.dp)`，无此问题。
- 违反已归档基线 `openspec/specs/glass-effect-switch/spec.md` REQ-2：降级 surfaceContainerLow MUST 保持既有圆角/尺寸/布局。
- 版本：当前 2.0.12/66（已推送）；本改动升 patch 2.0.13/67；只构建 debug。

## What Changes

`PlayerDetailSheet.kt` 关闭分支 `background` 补 `shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)`，开/关毛玻璃圆角一致。

## Requirements

- **REQ-1（圆角一致）**: 播放详情面板关闭毛玻璃后 MUST 保持顶部 28dp 圆角（与开启时完全一致），仅表面从玻璃变为 surfaceContainerLow 纯色。
- **REQ-2（回归）**: 主页动作条关闭毛玻璃行为 MUST 不变（28dp 圆角 + surfaceContainerLow）；233 单测全绿；版本 2.0.13。

#### Scenario: 关闭毛玻璃后面板圆角

- **WHEN** 设置页关闭"播放详情面板玻璃"后打开播放详情
- **THEN** 面板为 surfaceContainerLow 纯色表面，顶部保持 28dp 圆角，与开启时一致（此前为直角）

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.13，开/关播放详情面板玻璃各查看一次
- **THEN** 开/关两种状态下顶部圆角一致（28dp），版本 2.0.13
