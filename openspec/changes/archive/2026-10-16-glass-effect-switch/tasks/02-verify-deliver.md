---
id: t2-verify-deliver
status: pending
---

验证 + 交付：
- 233 单测全绿 + assembleDebug 通过；版本 2.0.10（构建前确认 build.gradle 已改 64/2.0.10）。
- git 提交；APK `BookListener-v2.0.10-glass-switch.apk` 交付；用户真机验收两开关独立生效/降级纯色/往返切换后推送 + 归档。
- 归档：`openspec archive glass-effect-switch --yes` 更新基线 spec。
