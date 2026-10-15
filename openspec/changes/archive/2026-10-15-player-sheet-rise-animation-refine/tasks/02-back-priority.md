---
id: t2-back-priority
status: done
---

返回键优先级修复（v2.0.8h）：
- 根因：专辑列表页 BackHandler（DirectoryScreen.kt exitAlbumView）在点击专辑后才组合注册，LIFO 晚于面板、压过面板。
- 修复：PlayerSheetGlassPanel 的 `BackHandler(enabled=visible)` 替换为 `DisposableEffect + OnBackPressedCallback`，在 visible 变化时 remove 再 add 重新注册到 LIFO 栈顶——面板打开时返回键先关面板，关闭后返回键回到背后页面。
- imports：`androidx.activity.OnBackPressedCallback`、`androidx.activity.compose.LocalOnBackPressedDispatcherOwner`、`androidx.compose.runtime.DisposableEffect`（移除 `androidx.activity.compose.BackHandler`）。
