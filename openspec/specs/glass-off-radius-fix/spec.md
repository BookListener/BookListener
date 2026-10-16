# glass-off-radius-fix Specification

## Purpose
TBD - created by archiving change glass-off-radius-fix. Update Purpose after archive.

## Requirements

### Requirement: 圆角一致

播放详情面板关闭毛玻璃后 MUST 保持顶部 28dp 圆角（与开启时完全一致），仅表面从玻璃变为 surfaceContainerLow 纯色。

#### Scenario: 关闭毛玻璃后面板圆角

- **WHEN** 设置页关闭"播放详情面板玻璃"后打开播放详情
- **THEN** 面板为 surfaceContainerLow 纯色表面，顶部保持 28dp 圆角，与开启时一致（此前为直角）

### Requirement: 回归

主页动作条关闭毛玻璃行为 MUST 不变（28dp 圆角 + surfaceContainerLow）；233 单测全绿；版本 2.0.13。

#### Scenario: 动作条不回归

- **WHEN** 关闭"主页动作条玻璃"后查看主页底部动作条
- **THEN** 动作条仍为 28dp 圆角 surfaceContainerLow 纯色表面（既有行为不变）
