---
id: t2-verify-deliver
status: in_progress
---

验证与交付（v2.0.11）：
- `./gradlew testDebugUnitTest assembleDebug`：233 单测全绿 + 构建通过。
- 交付 debug APK（BookListener-v2.0.11-settings-refine.apk）。
- 真机验收三点：曲目名静止居中（滚动不变）、横杠与倍速弹窗完全一致、毛玻璃开关子页可用且功能不回归。
