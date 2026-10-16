# player-detail-fixes Specification

## ADDED Requirements

### Requirement: 标题居中

播放详情曲目名 MUST 在静止/短名时水平居中（`fillMaxWidth` + `textAlign=Center`）；超长名滚动 MUST 保持左锚滚动（既有跑马灯验收行为不变）。

#### Scenario: 曲目名静止居中

- **WHEN** 播放详情面板显示未超宽曲目名
- **THEN** 曲目名在可用宽度内水平居中显示（此前偏左）

#### Scenario: 超长名滚动不变

- **WHEN** 播放详情面板显示超宽曲目名
- **THEN** 保持从左锚滚动、循环无缝、无叠字（v2.0.2 官方 basicMarquee 行为不变）

### Requirement: 横杠对齐

播放详情面板顶部横杠 MUST 与 ModalBottomSheet 默认 DragHandle 逐参一致：Surface 色 onSurfaceVariant（不透明）、形状 MaterialTheme.shapes.extraLarge（圆角28dp）、内容 Box 32×4dp、垂直 padding 上下各 22dp、水平居中。

#### Scenario: 横杠与倍速弹窗一致

- **WHEN** 对比播放详情面板顶部横杠与倍速弹窗顶部横杠
- **THEN** 位置（垂直 22dp 间距、水平居中）、颜色（onSurfaceVariant 不透明）、形状（圆角 28dp 胶囊）完全一致

### Requirement: 开关收子页

设置主页 MUST 仅以"毛玻璃特效"列举行（图标+文字+右箭头）列举该配置项，点击进入子页；子页 MUST 含返回箭头、标题、说明与两个 Switch（主页动作条玻璃/播放详情面板玻璃）；主页 MUST NOT 放置任何开关。

#### Scenario: 毛玻璃开关收进子页

- **WHEN** 进入设置主页
- **THEN** 主页只有"毛玻璃特效"列举行（图标+文字+右箭头），无任何开关；点击进入子页，子页有返回箭头、标题、说明与两个 Switch，操作后实时生效

### Requirement: 功能不回归

开关默认开启、独立持久化、实时生效行为 MUST 与 v2.0.10 一致；关闭降级纯色（surfaceContainerLow）不变。

#### Scenario: 默认与往返

- **WHEN** 全新安装（默认开启）/ 开关来回切换 / 关闭后查看对应区域
- **THEN** 默认两处均为定稿玻璃；往返切换实时生效；关闭时对应区域为纯色表面（surfaceContainerLow）
