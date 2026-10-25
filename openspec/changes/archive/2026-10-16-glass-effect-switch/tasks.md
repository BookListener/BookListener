## 01-glass-switch
---
id: t1-glass-switch
status: in_progress
---

实施（v2.0.10）：
- PlayerViewModel：新增 `glassActionBar` / `glassPlayerPanel` 两状态（KEY_GLASS_ACTION_BAR=glass_action_bar、KEY_GLASS_PLAYER_PANEL=glass_player_panel，默认 true）+ setter，prefs 持久化（fullFileCache 同款）。
- SettingsScreen：新增两个 Switch 行（Row+Switch 模式），"主页动作条玻璃""播放详情面板玻璃"。
- MainScreen：collect 两状态；动作条 Row modifier 条件化（开=drawBackdrop 玻璃 / 关=surfaceContainerLow 圆角纯色）；PlayerDetailSheet backdrop = if(glassPlayerPanel) backdrop else null。
- 版本 2.0.10 / versionCode 64。

## 02-verify-deliver
---
id: t2-verify-deliver
status: pending
---

验证 + 交付：
- 233 单测全绿 + assembleDebug 通过；版本 2.0.10（构建前确认 build.gradle 已改 64/2.0.10）。
- git 提交；APK `BookListener-v2.0.10-glass-switch.apk` 交付；用户真机验收两开关独立生效/降级纯色/往返切换后推送 + 归档。
- 归档：`openspec archive glass-effect-switch --yes` 更新基线 spec。

