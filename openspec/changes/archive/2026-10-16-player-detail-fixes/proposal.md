---
id: player-detail-fixes
status: proposed
title: 播放详情标题居中 + 横杠对齐 M3 + 毛玻璃开关收子页（v2.0.11）
---

## Why

用户基于截图提出三项修复：① 播放详情曲目名未水平居中；② 播放详情顶部横杠与倍速弹窗（ModalBottomSheet）横杠不一致；③ 毛玻璃特效开关应收进内部菜单（同缓存模式，主页仅列举配置项）。

## Context

- ①根因（代码实证）：`MarqueeText` 内 `Text` 为 wrap-content 宽度，`textAlign` 只对占满容器宽度的 Text 生效 → Center 实际失效偏左。修复为 Text 补 `fillMaxWidth`。
- ②差异（解包 material3 1.4.0 `BottomSheetDefaults.DragHandle`）：倍速弹窗横杠 = Surface(onSurfaceVariant 不透明, shape=extraLarge=圆角28dp) 内 Box(32×4dp)，垂直 padding 上下各 22dp；播放详情 `SheetDragHandle` 原为 onSurfaceVariant α0.4 + 圆角2dp + padding top12/bottom8，三处不一致。
- ③入口层级：v2.0.10 已交付毛玻璃两开关（SettingsScreen 主页内联 Switch），用户要求收进子页，与缓存子页同构；用户偏好固化：设置页新增子项一律"主页列举行 → 子页模板"，主页不放开关。
- 版本：当前 2.0.10/64（已提交未推送）；本改动升 patch 2.0.11/65；只构建 debug。

## What Changes

1. `MarqueeText.kt`：Text 补 `fillMaxWidth`，静止/短名 `textAlign`（Center）真正生效；滚动分支保持 Start 左锚不变。
2. `PlayerDetailSheet.kt`：`SheetDragHandle` 重写为 `Surface(onSurfaceVariant, MaterialTheme.shapes.extraLarge)` + `Box(32×4dp)` + `padding(vertical=22.dp)`，与 M3 1.4.0 `BottomSheetDefaults.DragHandle` 逐参一致。
3. `SettingsScreen.kt`：新增 `showGlass` 状态；主页"毛玻璃特效"块改为列举行（图标+文字+右箭头，点击进入子页），移除内联 Switch；子页（BackHandler + 返回箭头 + 标题"毛玻璃特效" + 说明 + 两个 Switch）与缓存子页同构。
4. 版本 2.0.11 / versionCode 65。

## Requirements

- **REQ-1（标题居中）**: 播放详情曲目名 MUST 在静止/短名时水平居中（`fillMaxWidth` + `textAlign=Center`）；超长名滚动 MUST 保持左锚滚动（既有跑马灯验收行为不变）。
- **REQ-2（横杠对齐）**: 播放详情面板顶部横杠 MUST 与 ModalBottomSheet 默认 DragHandle 逐参一致：Surface 色 onSurfaceVariant（不透明）、形状 MaterialTheme.shapes.extraLarge（圆角28dp）、内容 Box 32×4dp、垂直 padding 上下各 22dp、水平居中。
- **REQ-3（开关收子页）**: 设置主页 MUST 仅以"毛玻璃特效"列举行（图标+文字+右箭头）列举该配置项，点击进入子页；子页 MUST 含返回箭头、标题、说明与两个 Switch（主页动作条玻璃/播放详情面板玻璃）；主页 MUST NOT 放置任何开关。
- **REQ-4（功能不回归）**: 开关默认开启、独立持久化、实时生效行为 MUST 与 v2.0.10 一致；关闭降级纯色（surfaceContainerLow）不变。
- **REQ-5（验收）**: 233 单测全绿 + assembleDebug 通过；版本 2.0.11；真机验收三项修复。

#### Scenario: 曲目名静止居中

- **WHEN** 播放详情面板显示未超宽曲目名
- **THEN** 曲目名在可用宽度内水平居中显示（此前偏左）

#### Scenario: 横杠与倍速弹窗一致

- **WHEN** 对比播放详情面板顶部横杠与倍速弹窗顶部横杠
- **THEN** 位置（垂直 22dp 间距、水平居中）、颜色（onSurfaceVariant 不透明）、形状（圆角 28dp 胶囊）完全一致

#### Scenario: 毛玻璃开关收进子页

- **WHEN** 进入设置主页
- **THEN** 主页只有"毛玻璃特效"列举行（图标+文字+右箭头），无任何开关；点击进入子页，子页有返回箭头、标题、说明与两个 Switch，操作后实时生效

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.11
- **THEN** 标题居中、横杠一致、开关子页可用且功能不回归，版本 2.0.11
