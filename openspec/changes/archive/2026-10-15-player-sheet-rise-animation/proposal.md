---
id: player-sheet-rise-animation
status: proposed
title: 点击正在播放封面弹出详情——玻璃动作条+详情面板连续上移动画（v2.0.8）
---

## Why

点击主页底部玻璃动作条中间的圆形封面按钮（正在播放图标）弹出播放详情页时，目前详情面板只有自身的滑入动画（slideInVertically 320ms），底部玻璃动作条原地不动——两个毛玻璃组件（动作条玻璃 + 详情玻璃面板）之间没有视觉联动，弹出动作生硬。用户要求：点击封面按钮时做一个"组件向上移动"的动画，且**动作条先上移让位、详情面板从动作条位置继续上滑**，两个毛玻璃组件形成连续上移的接力效果，动画节奏快（~250ms）。

## Context

- 组件与位置：
  - `app/src/main/java/com/booklistener/booklistener/ui/MainScreen.kt`：主页底部玻璃动作条（96dp，drawBackdrop + lens40/72 + surfaceVariant 0.30），中间 84dp 圆形封面按钮（MainScreen.kt:167-188），点击后 `showPlayerSheet = true` 弹出详情；弹窗 `if (showPlayerSheet) PlayerDetailSheet(...)` 位于同 Box 内（MainScreen.kt:262）。
  - `app/src/main/java/com/booklistener/booklistener/ui/PlayerDetailSheet.kt`：`PlayerSheetGlassPanel` 自绘玻璃浮层（遮罩 0.5 + 玻璃面板，`AnimatedVisibility` enter=slideInVertically(320)+fadeIn(220)，exit=slideOutVertically(200)+fadeOut(160)）。
- 动画几何：动作条最终应停在面板顶边正上方（位移 = 面板高度 H + 动作条自身高度 96dp），面板从屏幕外底部（+H）滑入到底部（0）；两者同 250ms 动画时，动作条位移速率略快于面板，动画中后段追上并停在面板上方，视觉为"面板顶起动作条、动作条让位到面板上方"的连续上移。
- 前置约束：v2.0.7 已交付"点击专辑必弹正在播放页"（openAlbum 必弹 + 弹窗 BackHandler 拦截返回键）；本次不改变触发语义与返回行为，仅叠加入场/退场动画。
- 本 change 同时把 build.gradle versionName 2.0.1 → 2.0.8（此前 APK 交付命名与代码库版本长期不同步，借此对齐工作序列）。

## What Changes

1. MainScreen 增加弹窗动画状态：`sheetOpen`（布尔）+ `sheetHeightPx`（面板实时高度，由 PlayerDetailSheet 经 onSizeChanged 回调）+ `sheetProgress`（animateFloatAsState，250ms FastOutSlowInEasing，0↔1）。
2. 玻璃动作条 Row 增加 `graphicsLayer { translationY = -(sheetHeightPx + 96.dp.toPx()) * sheetProgress }`——动画结束时恰好停在面板顶边上方，关闭时回位。
3. PlayerDetailSheet / PlayerSheetGlassPanel 增加 `onPanelHeightChange: (Int) -> Unit` 回调（面板 Column onSizeChanged 上报高度，px），供动作条位移联动。
4. 详情面板 AnimatedVisibility 时长统一为 250ms（enter slideInVertically+ fadeIn；exit slideOutVertically + fadeOut），与动作条同步；遮罩保持静态（点击关闭语义不变）。

## Requirements

- **REQ-1（触发）**: 点击主页底部圆形封面按钮弹出播放详情时，MUST 同时触发玻璃动作条上移与详情面板滑入动画（v2.0.7 的触发路径 showPlayerSheet=true 处同步置 sheetOpen=true）。
- **REQ-2（动作条让位）**: 动作条 SHALL 从底部向上移动，位移 = 详情面板当前高度 + 动作条自身高度（96dp）；动画结束时动作条底边 MUST 位于面板顶边正上方（不重叠、不悬空）。
- **REQ-3（面板滑入）**: 详情面板 SHALL 从屏幕外底部（自身高度位移）滑入到底部最终位置，与动作条位移处于同一 250ms 动画时间轴。
- **REQ-4（时长与缓动）**: 入场动画 MUST 为 250ms 且缓动一致（FastOutSlowInEasing）；退出动画 MUST 与入场对称（面板下滑淡出、动作条同步回位）。
- **REQ-5（高度联动）**: 动作条位移 MUST 由面板实时高度（onSizeChanged 回调）驱动——面板高度变化（如 AB/倍速选项展开）时仍保持"面板顶边上方"的相对位置。
- **REQ-6（回归不破坏）**: v2.0.7 既有行为 MUST 保持——点击专辑必弹详情、弹窗返回键关闭详情（BackHandler）、文件夹场景不联动弹窗。
- **REQ-7（验收）**: `testDebugUnitTest` 全绿后构建 APK；真机验收：点击封面按钮可见动作条上移让位、面板从底部滑入、两毛玻璃组件连续上移、动画结束动作条停在面板顶边上方；关闭时对称回位。

## Out of Scope

- 不改变弹窗触发语义（专辑必弹 / 文件夹不联动 / 返回键行为）。
- 不改变玻璃特效参数（blur/lens/surface/遮罩）。
- 不改变其他组件（列表、搜索、设置）行为。
- 不涉及跑马灯、搜索、进度等其他主线。
