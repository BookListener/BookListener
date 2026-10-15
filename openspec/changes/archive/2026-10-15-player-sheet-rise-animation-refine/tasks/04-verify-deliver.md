---
id: t4-verify-deliver
status: done
---

验证 + 交付：
- `testDebugUnitTest` 全绿（~233 用例）+ `assembleDebug` 构建通过。
- git 提交 v2.0.8d（两段式接力）、v2.0.8f（串行对称 600ms）、v2.0.8g（下滑可见+连贯）、v2.0.8h（返回键优先级+上滑展开）四轮；版本号 2.0.8（versionCode 62）不变。
- present_files 交付 APK（用户真机验收"很好"后归档本 change）。
- 归档：`openspec change validate player-sheet-rise-animation-refine` 通过 → `openspec archive player-sheet-rise-animation-refine --yes` 更新基线 spec。
