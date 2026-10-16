---
id: t1-implement
status: in_progress
---

实施（v2.0.11）：
- `MarqueeText.kt`：Text 补 `fillMaxWidth`（静止/短名 textAlign=Center 生效；滚动保持 Start 左锚）。
- `PlayerDetailSheet.kt`：`SheetDragHandle` 重写为 `Surface(onSurfaceVariant, MaterialTheme.shapes.extraLarge)` + `Box(32×4dp)` + `padding(vertical=22.dp)`，对齐 M3 1.4.0 `BottomSheetDefaults.DragHandle`。
- `SettingsScreen.kt`：新增 `showGlass` 状态；主页毛玻璃块改列举行（点击进子页），移除内联 Switch；新增毛玻璃子页（BackHandler + 返回箭头 + 标题 + 说明 + 两 Switch，同缓存子页结构）。
- 版本 2.0.11 / versionCode 65。
