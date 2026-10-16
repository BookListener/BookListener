---
id: t1-glass-switch
status: in_progress
---

实施（v2.0.10）：
- PlayerViewModel：新增 `glassActionBar` / `glassPlayerPanel` 两状态（KEY_GLASS_ACTION_BAR=glass_action_bar、KEY_GLASS_PLAYER_PANEL=glass_player_panel，默认 true）+ setter，prefs 持久化（fullFileCache 同款）。
- SettingsScreen：新增两个 Switch 行（Row+Switch 模式），"主页动作条玻璃""播放详情面板玻璃"。
- MainScreen：collect 两状态；动作条 Row modifier 条件化（开=drawBackdrop 玻璃 / 关=surfaceContainerLow 圆角纯色）；PlayerDetailSheet backdrop = if(glassPlayerPanel) backdrop else null。
- 版本 2.0.10 / versionCode 64。
