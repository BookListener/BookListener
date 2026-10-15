---
id: t3-swipe-up-open
status: done
---

动作条整体上滑展开面板（v2.0.8h）：
- 用户确认触发区域为 B：整个底部玻璃动作条（曲目/封面/设置三组件所在区域整体）。
- MainScreen 动作条 Row 增加 `pointerInput(Unit)` + `detectVerticalDragGestures`：向上拖拽累计超过 48dp 阈值触发 `showPlayerSheet=true; sheetOpen=true`（`triggered` 标志避免重复触发）。
- 动画与点击封面按钮完全同一套（三组件上移消失 → 面板滑入；关闭反向）；上滑只展开面板，不切文件夹、不恢复播放、不切换页面。
- imports：`androidx.compose.foundation.gestures.detectVerticalDragGestures`、`androidx.compose.ui.input.pointer.pointerInput`。
