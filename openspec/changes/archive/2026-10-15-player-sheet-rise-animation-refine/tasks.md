## 01-two-stage-animation
---
id: t1-two-stage-animation
status: done
---

两段式接力动画（v2.0.8d/f/g）：
- 澄清"三大件"= 动作条上的三个组件（曲目/封面/设置），移除 v2.0.8 整条动作条 graphicsLayer 位移，玻璃条本身留在底部。
- MainScreen：`sheetOpen` / `sheetPanelVisible` / `barItemsHidden` 状态 + `barItemsProgress`（animateFloatAsState 200ms FastOutSlowInEasing）；三组件各自 graphicsLayer（translationY=-96dp×p、alpha=1-p）。
- `LaunchedEffect(sheetOpen)` 编排：打开 = 组件上移消失(200ms) → 消失瞬间面板滑入(400ms)；关闭 = 面板下滑(400ms) → delay(250ms) → 组件下移归位(200ms) 与面板尾部重叠。
- 专辑路径（requestPlayerSheet）同步置 sheetOpen=true；恒组合 PlayerDetailSheet 传 visible=sheetPanelVisible。
- PlayerDetailSheet：面板/遮罩 AnimatedVisibility 状态驱动；exit fadeOut 160→400ms（与 slideOut 同步，下滑全程可见）。

## 02-back-priority
---
id: t2-back-priority
status: done
---

返回键优先级修复（v2.0.8h）：
- 根因：专辑列表页 BackHandler（DirectoryScreen.kt exitAlbumView）在点击专辑后才组合注册，LIFO 晚于面板、压过面板。
- 修复：PlayerSheetGlassPanel 的 `BackHandler(enabled=visible)` 替换为 `DisposableEffect + OnBackPressedCallback`，在 visible 变化时 remove 再 add 重新注册到 LIFO 栈顶——面板打开时返回键先关面板，关闭后返回键回到背后页面。
- imports：`androidx.activity.OnBackPressedCallback`、`androidx.activity.compose.LocalOnBackPressedDispatcherOwner`、`androidx.compose.runtime.DisposableEffect`（移除 `androidx.activity.compose.BackHandler`）。

## 03-swipe-up-open
---
id: t3-swipe-up-open
status: done
---

动作条整体上滑展开面板（v2.0.8h）：
- 用户确认触发区域为 B：整个底部玻璃动作条（曲目/封面/设置三组件所在区域整体）。
- MainScreen 动作条 Row 增加 `pointerInput(Unit)` + `detectVerticalDragGestures`：向上拖拽累计超过 48dp 阈值触发 `showPlayerSheet=true; sheetOpen=true`（`triggered` 标志避免重复触发）。
- 动画与点击封面按钮完全同一套（三组件上移消失 → 面板滑入；关闭反向）；上滑只展开面板，不切文件夹、不恢复播放、不切换页面。
- imports：`androidx.compose.foundation.gestures.detectVerticalDragGestures`、`androidx.compose.ui.input.pointer.pointerInput`。

## 04-verify-deliver
---
id: t4-verify-deliver
status: done
---

验证 + 交付：
- `testDebugUnitTest` 全绿（~233 用例）+ `assembleDebug` 构建通过。
- git 提交 v2.0.8d（两段式接力）、v2.0.8f（串行对称 600ms）、v2.0.8g（下滑可见+连贯）、v2.0.8h（返回键优先级+上滑展开）四轮；版本号 2.0.8（versionCode 62）不变。
- present_files 交付 APK（用户真机验收"很好"后归档本 change）。
- 归档：`openspec change validate player-sheet-rise-animation-refine` 通过 → `openspec archive player-sheet-rise-animation-refine --yes` 更新基线 spec。

