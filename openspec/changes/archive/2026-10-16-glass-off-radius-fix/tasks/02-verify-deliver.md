---
id: t2-verify-deliver
status: in_progress
---

验证与交付（v2.0.13）：
- `./gradlew testDebugUnitTest assembleDebug`：233 单测全绿 + 构建通过。
- 交付 debug APK（BookListener-v2.0.13-glass-off-radius.apk）。
- 真机验收：开/关播放详情面板玻璃，顶部圆角一致（28dp）；动作条不回归。
