# theme-settings Specification

## Purpose

将 App 的外观相关设置收敛为统一的"主题"设置容器：新增明暗三态（白天/黑夜/自动）手动切换，并把原有的玻璃效果、语言入口收集到主题子页内，主页只保留一个"主题"入口。

## Requirements

### Requirement: 主题入口收敛

设置主页 MUST 仅保留一个"主题"行（图标+文字+右箭头），点击进入主题子页；MUST 移除主页原有的"玻璃效果""语言"两个独立入口。主题子页 MUST 为单个页面，内部用 HorizontalDivider 分隔三个区块：外观模式 / 玻璃效果 / 语言。

#### Scenario: 主页入口

- **WHEN** 进入设置主页
- **THEN** 可见"主题"行（图标+文字+右箭头），无"玻璃效果""语言"独立行

#### Scenario: 进入主题子页

- **WHEN** 点击"主题"行
- **THEN** 进入主题子页，自上而下为外观模式区块、玻璃效果区块、语言区块，各区块间有分隔线

### Requirement: 明暗三态切换

外观模式 MUST 提供"白天/黑夜/自动"三选一（RadioButton），默认"自动"；切档 MUST 走 `AppCompatDelegate.setDefaultNightMode`（`MODE_NIGHT_YES`/`MODE_NIGHT_NO`/`MODE_NIGHT_FOLLOW_SYSTEM`），系统重建 Activity 换肤；所选档位 MUST 持久化（prefs），冷启动恢复。手动选白天/黑夜时 Material You 动态色 MUST 仍生效（明暗与动态色正交）。

#### Scenario: 默认自动

- **WHEN** 全新安装，未改外观模式
- **THEN** 明暗跟随系统（与现有行为一致）

#### Scenario: 手动切黑夜

- **WHEN** 外观模式选"黑夜"
- **THEN** 界面立即切为深色，重启后保持；动态色取色仍生效

#### Scenario: 手动切白天

- **WHEN** 外观模式选"白天"
- **THEN** 界面立即切为浅色，重启后保持；动态色取色仍生效

#### Scenario: 切回自动

- **WHEN** 外观模式选"自动"
- **THEN** 明暗回到跟随系统

### Requirement: 玻璃效果区块

主题子页的玻璃效果区块 MUST 沿用现有 `glassActionBar` / `glassPlayerPanel` 两个开关，逻辑零改动（持久化、默认值、降级行为与 `glass-effect-switch` spec 一致）。

#### Scenario: 玻璃开关

- **WHEN** 在主题子页切换两个玻璃开关
- **THEN** 与旧"玻璃效果"子页行为完全一致，实时生效

### Requirement: 语言区块

主题子页的语言区块 MUST 沿用现有三态（跟随系统/简体中文/English），逻辑零改动，经 `AppCompatDelegate.setApplicationLocales`；行为与 `app-i18n-official-res` spec 一致。

#### Scenario: 语言切换

- **WHEN** 在主题子页切换语言三态
- **THEN** 与旧"语言"子页行为完全一致，界面重建后停留当前层级

### Requirement: 回归

移除旧入口、合并容器后，玻璃效果两开关与语言三态的持久化 key、默认值 MUST 保持不变；未开启明暗手动切换前（默认自动）的界面行为 MUST 与改造前完全一致。

#### Scenario: 默认行为不变

- **WHEN** 升级安装（保持默认"自动"），进入主页与播放
- **THEN** 明暗跟随系统，玻璃默认开启，界面与旧版一致

#### Scenario: 玻璃与语言持久化

- **WHEN** 旧版本已设置玻璃开关、语言为 English，升级到本版
- **THEN** 玻璃开关状态与语言选择保持，进入主题子页可见已选状态
