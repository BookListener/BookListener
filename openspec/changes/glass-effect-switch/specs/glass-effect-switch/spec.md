# glass-effect-switch Specification

## ADDED Requirements

### Requirement: 组件级开关

设置页 MUST 提供两个独立开关"主页动作条玻璃""播放详情面板玻璃"，各自持久化（默认开启），互不影响；任一开关关闭时对应组件 MUST 降级为纯色表面（surfaceContainerLow，保持既有圆角/尺寸/布局），不得残留模糊或半透明采样。

#### Scenario: 关闭主页动作条玻璃

- **WHEN** 设置页关闭"主页动作条玻璃"后返回主页
- **THEN** 底部动作条变为纯色表面（surfaceContainerLow 圆角条），不再模糊/折射背景；播放详情面板玻璃不受影响

#### Scenario: 关闭播放详情面板玻璃

- **WHEN** 设置页关闭"播放详情面板玻璃"后打开播放详情
- **THEN** 面板变为纯色表面（surfaceContainerLow），动作条玻璃不受影响

### Requirement: 实时生效

开关切换 MUST 无需重启，重组后立即生效；不影响其余功能（播放、列表、导航）。

#### Scenario: 默认与往返

- **WHEN** 全新安装（默认开启）/ 开关来回切换
- **THEN** 默认两处均为定稿玻璃；往返切换实时生效、无异常、无需重启

### Requirement: 回归

开关默认开启时两处玻璃参数 MUST 与 v2.0.6d 定稿完全一致（blur10 + lens40/72 + surfaceVariant α0.30）。

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.10 操作两开关
- **THEN** 各自独立生效、降级纯色正确、往返无异常、版本 2.0.10
