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
