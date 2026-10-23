# Proposal

## Why

设置页当前为单列平铺 + HorizontalDivider 列表，主页 6 个入口与各子页控件样式零散；外观模式/语言三态用 RadioButton、睡眠定时用数值列表，视觉语言不统一。用户选定"方案A·分组卡片"并借鉴豆包侧边栏极简线性图标，要求将设置页整体重构为：主页按语义分组卡片、三元选择器统一为"图标+文字+主题色下划线"、图标统一为极简线性风格。

## What Changes

- **主页分组卡片**：6 个入口按语义归为 3 组卡片——「资料源」（文件夹管理/WebDAV账号）、「播放与外观」（睡眠定时/主题/缓存管理）、「关于」（关于）。每组用 surfaceContainer 圆角卡片承载，卡片间留白。
- **三元选择器组件**：新增可复用组件，外观模式三态、睡眠定时三态统一为「图标+文字」，选中项主题色文字 + 细短下划线（替代 RadioButton 圆点）。新增图标：白天（太阳）、黑夜（月亮）、自动（循环）。
- **图标统一**：各入口行图标沿用 AppIcons 极简线性风格；文件夹管理条目图标、添加按钮统一细线风格。
- **子页标题/说明结构保持**：返回箭头 + titleMedium 标题 + bodySmall 说明不变。

## Capabilities

### New Capabilities

- `settings-card-style`: 设置页分组卡片布局 + 统一三元选择器组件。

### Modified Capabilities

- `theme-settings`: 主题子页外观模式三态由 RadioButton 改为图标化三元选择器。
- `settings-source-refactor`、`app-i18n-official-res`、`glass-effect-switch`：主页入口归入分组卡片，行为不变。

## Impact

- **ui/SettingsScreen.kt**：主页入口重构为分组卡片；三元选择器组件化。
- **ui/icons/AppIcons.kt**：新增 Sun（白天）、Moon（黑夜）、AutoLoop（自动/循环）图标。
- **ui/ThemeTriOption.kt（新）**：可复用三元选择器组件。
- **不涉及**：播放层、扫描、专辑、CUE、数据库。

## Out of Scope

- 播放层、扫描、专辑、CUE 逻辑
- 各子页的既有配置逻辑改动（仅改容器/控件外观）
- 深色主题下的配色调整（沿用 Material 语义色）
