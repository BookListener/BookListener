# player-sheet-rise-animation Specification

## Purpose
TBD - created by archiving change player-sheet-rise-animation. Update Purpose after archive.

## Requirements

### Requirement: 点击封面按钮触发双组件上移动画

点击主页底部玻璃动作条中间的圆形封面按钮弹出播放详情时，MUST 同时触发玻璃动作条上移与详情面板滑入动画（v2.0.7 触发路径 `showPlayerSheet = true` 处同步置 `sheetOpen = true`）。

#### Scenario: 点击封面按钮弹出详情

- **WHEN** 用户点击底部圆形封面按钮（正在播放图标）
- **THEN** 详情面板从底部滑入，同时玻璃动作条向上移动让位，两个毛玻璃组件呈现连续上移

### Requirement: 动作条让位到面板顶边上方

动作条 SHALL 从底部向上移动，位移 = 详情面板当前高度 + 动作条自身高度（96dp）；动画结束时动作条底边 MUST 位于面板顶边正上方（不重叠、不悬空）。

#### Scenario: 动画结束位置

- **WHEN** 入场动画完成（sheetProgress = 1）
- **THEN** 动作条底边 = 面板顶边上方 0 间隙（translationY = -(H + 96dp)），两玻璃组件上下紧贴排列

### Requirement: 面板滑入与动作条同一时间轴

详情面板 SHALL 从屏幕外底部（自身高度位移）滑入到底部最终位置，与动作条位移处于同一 250ms 动画时间轴；动画中后段动作条追上并停在面板上方（视觉"面板顶起动作条"）。

#### Scenario: 同步滑入

- **WHEN** 入场动画进行中
- **THEN** 面板以 slideInVertically（自身高度）上滑，动作条以略快速率上移，动画结束两者相对位置固定

### Requirement: 250ms 快节奏与对称退出

入场动画 MUST 为 250ms（FastOutSlowInEasing）；退出动画 MUST 与入场对称——面板下滑淡出、动作条同步回位到底部。

#### Scenario: 关闭详情

- **WHEN** 用户点击遮罩或按返回键关闭详情
- **THEN** 面板下滑淡出（250ms），动作条从面板顶边上方回到底部（250ms）

### Requirement: 面板高度实时联动

动作条位移 MUST 由面板实时高度（onSizeChanged 回调）驱动——面板高度变化（如 AB/倍速选项展开）时仍保持"面板顶边上方"相对位置。

#### Scenario: 面板高度变化

- **WHEN** 详情面板内选项展开导致面板高度增加
- **THEN** 动作条位移随新高度联动，仍停在面板顶边上方

### Requirement: 回归不破坏既有行为

v2.0.7 既有行为 MUST 保持：点击专辑必弹详情、弹窗返回键关闭详情（BackHandler）、文件夹场景不联动弹窗。

#### Scenario: 既有行为回归

- **WHEN** 点击专辑 / 按返回键 / 进入文件夹
- **THEN** 专辑点击必弹详情、返回键先关弹窗、文件夹场景不弹窗不联动

### Requirement: 验收

`testDebugUnitTest` 全绿后构建 APK；真机验收：点击封面按钮可见动作条上移让位、面板从底部滑入、两毛玻璃组件连续上移、动画结束动作条停在面板顶边上方；关闭时对称回位。

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.8 APK 真机操作
- **THEN** 动画效果符合上述全部场景，测试全绿
