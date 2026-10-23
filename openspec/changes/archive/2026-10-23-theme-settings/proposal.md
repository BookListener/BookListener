# Proposal

## Why

当前"外观"相关设置分散：明暗模式完全跟随系统无法手动切换；玻璃效果（`glassActionBar`/`glassPlayerPanel`）、语言（三态）各自独立占设置主页入口。用户希望新增统一的"主题"设置项，将玻璃效果与语言收集进去，并为明暗模式提供白天/黑夜/自动三档手动切换。

## What Changes

- **新增"主题"设置入口**：设置主页新增"主题"行（图标+文字+右箭头），点击进入主题子页；移除主页原有的"玻璃效果""语言"两个独立入口。
- **主题子页三区块**：单个子页内用分隔线分三块：
  1. **外观模式**：白天 / 黑夜 / 自动 三选一（RadioButton），默认自动；切档用 `AppCompatDelegate.setDefaultNightMode`（DayNight 框架，与语言同机制），系统重建 Activity 换肤。
  2. **玻璃效果**：沿用 `glassActionBar` / `glassPlayerPanel` 两个开关，逻辑零改动。
  3. **语言**：沿用三态（跟随系统 / 中文 / 英文），逻辑零改动，经 `AppCompatDelegate.setApplicationLocales`。
- **动态色保持**：手动选白天/黑夜时，Material You 动态色（`dynamicColor`）仍生效，明暗与色相正交。
- **外观模式持久化**：新增 prefs key 存储三态档位，冷启动恢复。

## Capabilities

### New Capabilities

- `theme-settings`: 主题统一设置容器（外观模式三态 + 玻璃效果 + 语言），明暗手动切换。

### Modified Capabilities

- `app-i18n-official-res`: 语言设置入口从主页独立行移入主题子页，逻辑不变。
- `glass-off-radius-fix`: 玻璃效果入口从主页独立行移入主题子页，逻辑不变。

## Impact

- **MainActivity / AppTheme**：外观模式接入 `AppCompatDelegate.setDefaultNightMode`；`AppTheme` 的 `darkTheme` 由系统状态决定（DayNight 框架重建后自然生效）。
- **PlayerViewModel**：新增外观模式档位 prefs 状态与 setter。
- **SettingsScreen**：主页删除玻璃、语言两行，新增主题行；新增主题子页（外观模式 RadioButton + 玻璃开关 + 语言三态）。
- **不涉及**：播放层、扫描、专辑、CUE。

## Out of Scope

- 播放层、扫描、专辑、CUE 逻辑
- 玻璃效果的视觉样式改动（仅搬容器）
- 动态色开关（保持固定开启）
- 主题色自定义（非明暗切换）
