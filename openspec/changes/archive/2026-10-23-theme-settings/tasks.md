# Tasks

## 1. PlayerViewModel 新增外观模式状态

- 新增 prefs key（如 `KEY_APPEARANCE_MODE`，默认 `follow_system`）
- 暴露 `appearanceMode: StateFlow<AppearanceMode>` 与 `setAppearanceMode(mode)`
- `AppearanceMode` 枚举：`FOLLOW_SYSTEM` / `LIGHT` / `DARK`
- setter 写入 prefs 并调用 `AppCompatDelegate.setDefaultNightMode`

## 2. MainActivity / AppTheme 接入明暗

- MainActivity onCreate 读取 prefs 外观模式，调用对应 `setDefaultNightMode`
- 确认 `AppTheme.darkTheme` 由系统状态决定（DayNight 重建后 `isSystemInDarkTheme()` 自然反映所选明暗），无需改 AppTheme 主体

## 3. SettingsScreen 主页入口收敛

- 删除"玻璃效果""语言"两个主页入口 Row
- 新增"主题"入口 Row（图标+文字+右箭头），`showTheme` 状态

## 4. SettingsScreen 主题子页

- 新增 `showTheme` 子页，返回箭头 + 标题"主题" + 说明
- 区块一·外观模式：三 RadioButton（白天/黑夜/自动），读 `appearanceMode`，点击调 `setAppearanceMode`
- 区块二·玻璃效果：沿用 `glassActionBar`/`glassPlayerPanel` 两开关（从旧玻璃子页搬入）
- 区块三·语言：沿用三态（从旧语言子页搬入）
- 删除旧 `showGlass`、`showLanguage` 子页代码

## 5. 文案资源

- 新增"主题"相关字符串（`settings_theme_row`、`settings_theme_row_desc`、`settings_theme_title`、`settings_theme_desc`、`appearance_mode_title`、`appearance_light/dark/auto`）
- values（英文）+ values-zh（中文）双资源
- 移除或保留旧玻璃/语言行文案（入口移除但子页标题文案仍被主题子页复用则保留）

## 6. 测试与构建

- 回归：设置主页入口、主题子页三区块、明暗切换、玻璃/语言行为不变
- 全量 `testDebugUnitTest` 通过
- 升 patch，构建 debug APK
