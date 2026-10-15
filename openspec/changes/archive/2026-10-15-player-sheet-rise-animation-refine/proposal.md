---
id: player-sheet-rise-animation-refine
status: proposed
title: 弹窗动画语义对齐（三大件=动作条三组件）+ 返回键优先级 + 动作条上滑展开（v2.0.8d~h）
---

## Why

v2.0.8 初版把"两个毛玻璃组件连续上移"误实现为**整条玻璃动作条**上移（graphicsLayer 位移）+ 面板滑入。用户澄清：**"三大件"是动作条上的三个组件（曲目 / 封面 / 设置）**，正确语义为——打开：三组件先快速上移消失 → 面板接着出现并继续上移；关闭（相反）：面板下滑 → 三组件下移归位；两段动作在**时间、空间上连贯**（像"面板由组件上滑得到、收回时收到组件处"）。

随后真机验收又暴露三个问题并修复：
1. 关闭时**面板下滑动画不可见**——exit 的 fadeOut(160ms) 先于 slideOut(400ms) 完成，面板 160ms 内淡成透明，下滑位移在透明态不可见（表现为"下滑动作没有播放"）。
2. **返回键先生效于专辑列表**——专辑列表页的 BackHandler 在点击专辑后才组合注册，LIFO 晚于面板、压过面板的返回键。
3. 需要**动作条整体上滑展开面板**（网易云风格），动画与点击封面按钮同一套。

## Context

- `app/src/main/java/com/booklistener/booklistener/ui/MainScreen.kt`：
  - 弹窗动画状态 `sheetOpen` / `sheetPanelVisible` / `barItemsHidden`（v2.0.8d 起）；`barItemsProgress`（animateFloatAsState，200ms FastOutSlowInEasing，0↔1）驱动三组件 graphicsLayer（translationY = -96dp×p、alpha = 1-p）。
  - `LaunchedEffect(sheetOpen)` 编排两段时序（v2.0.8g 定稿）：打开 = 组件上移消失(200ms) → 消失瞬间面板滑入(400ms)，无空档；关闭 = 面板下滑(400ms) → delay(250ms) 面板滑到组件位置 → 组件下移归位(200ms)，与面板尾部重叠连贯。
  - 动作条玻璃条本身留在底部不动（v2.0.8d 移除整条 graphicsLayer 位移）。
  - v2.0.8h：动作条 Row 增加 `pointerInput` + `detectVerticalDragGestures`——向上拖拽累计超 48dp 触发 `showPlayerSheet=true; sheetOpen=true`（与点击封面按钮同套动画，不附带切文件夹/恢复播放）。
  - 专辑路径（requestPlayerSheet → LaunchedEffect）同步置 `sheetOpen=true`（v2.0.8c）。
- `app/src/main/java/com/booklistener/booklistener/ui/PlayerDetailSheet.kt`：
  - 恒组合 + `visible` 状态驱动（v2.0.8c）：遮罩 AnimatedVisibility fadeIn/fadeOut(400ms)；面板 AnimatedVisibility slideIn/slideOut(400ms)+fade；**exit fadeOut 与 slideOut 同步 400ms**（v2.0.8g，保证下滑可见）。
  - 返回键（v2.0.8h）：`DisposableEffect + OnBackPressedCallback`，visible 变化时 remove 再 add 重新注册到 LIFO 栈顶，面板打开时优先于专辑列表 BackHandler；BackHandler(enabled=visible) 不再使用。
- 用户偏好：先复述需求再动手；排斥投机调参；动画验收看真实节奏（速度/衔接），不只关注时长档位。

## What Changes

1. **两段式接力动画**（v2.0.8d/f/g）：三组件各自 graphicsLayer 上移消失(200ms)/下移归位(200ms)；面板滑入 400ms（与组件消失衔接无空档）、下滑 400ms（exit fadeOut 同步 400ms 保证可见）；关闭时 delay(250ms) 让组件在面板滑到组件位置时开始归位，与面板尾部重叠连贯；动作条玻璃条不动。
2. **返回键优先级**（v2.0.8h）：DisposableEffect + OnBackPressedCallback 在 visible 变化时重新注册到 LIFO 栈顶——面板打开时返回键先关面板，关闭后返回键回到专辑列表/文件夹。
3. **动作条上滑展开**（v2.0.8h）：整条动作条向上拖拽累计超 48dp 触发展开面板，动画与点击封面按钮完全同一套。
4. 版本号保持 2.0.8（versionCode 62），未再升版；git 提交 v2.0.8d/f/g/h 四轮。

## Requirements

- **REQ-1（触发）**: 点击封面按钮 / 专辑路径 requestPlayerSheet / 动作条上滑（48dp 阈值）MUST 触发同一套展开动画；动画 MUST 为"三组件上移消失(200ms) → 面板滑入(400ms)"，无空档。
- **REQ-2（关闭）**: 关闭 MUST 为"面板下滑(400ms，下滑全程可见) → 面板滑到组件位置（delay 250ms）→ 三组件下移归位(200ms)"，与面板尾部重叠连贯；动作条玻璃条本身不动。
- **REQ-3（返回键）**: 面板显示时系统返回键 MUST 先关闭面板（callback 重新注册 LIFO 栈顶，优先于专辑列表 BackHandler）；面板关闭后返回键行为回到背后页面。
- **REQ-4（上滑）**: 底部玻璃动作条整体向上拖拽累计超 48dp MUST 展开面板，动画与点击同套；上滑不改变播放状态、不切换页面。
- **REQ-5（回归）**: v2.0.7 既有行为 MUST 保持：点击专辑必弹详情、文件夹场景不联动弹窗、暂停状态点击封面恢复播放。
- **REQ-6（验收）**: `testDebugUnitTest` 全绿 + assembleDebug 构建；真机验收：打开三组件上移消失→面板滑入、关闭面板下滑可见→组件归位、专辑列表页内返回键先关面板、动作条上滑展开。
