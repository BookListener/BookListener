# Proposal

## Why

v2.1.20 已完成设置主页分组卡片 + 主题子页外观模式图标化三元选择器。但其余设置子页（文件夹管理、WebDAV 账号列表、睡眠定时、缓存管理、关于、主题的玻璃/语言区块）仍是平铺列表 + HorizontalDivider 的旧样式，与主页卡片语言不一致。用户要求将全部设置子页统一为「方案A·分组卡片」语言，保持整个设置体系视觉一致。

## What Changes

- **文件夹管理页**：管理条目列表与文件列表套 surfaceContainer 圆角卡片；行图标统一极简线性。
- **WebDAV 账号列表**：账号列表套卡片，行图标极简。
- **睡眠定时子页**：分钟选项列表套卡片。
- **缓存管理子页**：两个区块（缓存开关/存储清除）各套一张卡片。
- **关于子页**：信息区块套卡片。
- **主题子页**：玻璃效果、语言两个区块套卡片（外观模式已是图标化选择器，其容器一并统一）。

统一规则：内容放入 surfaceContainer 圆角卡片（shapes.medium），卡片内行间保留 HorizontalDivider，卡片外留白；仅改容器与图标，业务逻辑零改动。

## Capabilities

### Modified Capabilities

- `settings-source-refactor`（文件夹管理/WebDAV）、`settings-timer`、`settings-cache`、`settings-about`、`theme-settings`（玻璃/语言区块）：子页内容套卡片容器，外观统一为方案A。

## Impact

- `ui/SettingsScreen.kt`：睡眠定时/缓存/关于/主题子页内容卡片化
- `ui/FolderManageScreen.kt`：管理列表/文件列表卡片化
- `ui/CloudConfigScreen.kt`：账号列表卡片化
- `ui/icons/AppIcons.kt`：如缺图标则补充（无新增则不动）

## Out of Scope

- 所有业务逻辑、数据、扫描、播放、CUE
- 主页分组卡片（v2.1.20 已完成，本轮不动）
- 外观模式三元选择器（v2.1.20 已完成）
