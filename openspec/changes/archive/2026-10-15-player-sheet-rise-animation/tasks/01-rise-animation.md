---
id: t1-rise-animation
status: done
---

实现双组件上移动画：
- MainScreen 增加 `sheetOpen`（布尔）、`sheetHeightPx`（面板实时高度回调）、`sheetProgress`（animateFloatAsState，250ms FastOutSlowInEasing，0↔1）；封面按钮点击处（showPlayerSheet=true）同步置 sheetOpen=true，onDismiss 处 sheetOpen=false。
- 玻璃动作条 Row 增加 `graphicsLayer { translationY = -(sheetHeightPx + 96.dp.toPx()) * sheetProgress }`。
- PlayerDetailSheet / PlayerSheetGlassPanel 增加 `onPanelHeightChange: (Int) -> Unit`，面板 Column onSizeChanged 上报高度（px）。
- 详情面板 AnimatedVisibility 时长统一 250ms（enter slideInVertically+fadeIn；exit slideOutVertically+fadeOut）。
