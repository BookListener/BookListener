## 01-rise-animation
---
id: t1-rise-animation
status: done
---

实现双组件上移动画：
- MainScreen 增加 `sheetOpen`（布尔）、`sheetHeightPx`（面板实时高度回调）、`sheetProgress`（animateFloatAsState，250ms FastOutSlowInEasing，0↔1）；封面按钮点击处（showPlayerSheet=true）同步置 sheetOpen=true，onDismiss 处 sheetOpen=false。
- 玻璃动作条 Row 增加 `graphicsLayer { translationY = -(sheetHeightPx + 96.dp.toPx()) * sheetProgress }`。
- PlayerDetailSheet / PlayerSheetGlassPanel 增加 `onPanelHeightChange: (Int) -> Unit`，面板 Column onSizeChanged 上报高度（px）。
- 详情面板 AnimatedVisibility 时长统一 250ms（enter slideInVertically+fadeIn；exit slideOutVertically+fadeOut）。

## 02-verify-tests
---
id: t2-verify-tests
status: done
---

验证：`./gradlew testDebugUnitTest assembleDebug` 全绿（v2.0.7 回归：openAlbum 必弹、返回键 BackHandler、文件夹不联动）；产出 APK 命名 `BookListener-v2.0.8-*.apk`。

## 03-archive-deliver
---
id: t3-archive-deliver
status: done
---

归档 + 交付：
- `openspec change validate player-sheet-rise-animation` 通过后，3 个 task 标记 done，`openspec archive player-sheet-rise-animation` 归档并更新基线 spec。
- build.gradle versionName 2.0.1 → 2.0.8（versionCode 61 → 62），与 APK 命名对齐。
- git 提交（含 v2.0.7 未提交修复：openAlbum 必弹、BackHandler、测试断言更新 + 本 change）。
- present_files 交付 APK。

