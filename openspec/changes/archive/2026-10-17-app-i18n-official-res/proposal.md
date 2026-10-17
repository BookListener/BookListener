---
id: app-i18n-official-res
status: proposed
title: 国际化：官方资源路线 + App 内三态切换（v2.0.14）
---

## Why

用户要求为 BookListener 添加国际化功能：①支持 App 内运行时切换语言；②设计更容易适配的结构。经 grill 拷问定稿：走 **Android 官方资源路线**（主流方案），三态语言模型，一次性全量迁移。

## Context

- 现状（勘察实证）：`MainActivity : ComponentActivity()`；`appcompat:1.7.0` 已在依赖；`strings.xml` 仅 `app_name`；全工程约 236 处含中文的行（SettingsScreen 51 / FolderManageScreen 32 / PlayerDetailSheet 31 / PlayerViewModel 31 / CloudConfigScreen 22 / DirectoryScreen 19 / MainScreen 15 / MessageCatalog 14 / core 等）；测试为 Robolectric 4.17 + Compose rule。
- 决策（用户拍板）：①三态语言（跟随系统/简体中文/English），默认跟随系统；②接受系统级 UI 边界（输入法/系统对话框/MediaSession 通知按钮随系统语言，App 内全部跟随 App 语言）；③236 处三类文案（纯 UI 静态/运行态提示/元数据兜底）全量迁移 + 通知渠道名；④日期格式与序数首版本地化；⑤非中英系统语言回退 English；⑥一次性全量进 v2.0.14；⑦路线=官方资源（ComponentActivity→AppCompatActivity + stringResource + AppCompatDelegate.setApplicationLocales）。
- 资源布局决策：`values/` 放 **English**（默认/兜底），`values-zh/` 放简体中文（语言级匹配，zh-Hans/zh-TW 均命中）。手动选 English→setApplicationLocales("en")；选简体中文→"zh"；跟随系统→清空 app 语言（系统 zh→中文、en→英文、其他→回退 English）。AppCompat 自动持久化语言选择。
- 版本：当前 2.0.13/67（已推送）；本改动 v2.0.14/68；只构建 debug（release 需用户确认）。
- 实施后事实（实证）：141 键双语言（146 − 5 死键：common_file_name/common_tag/nav_add/nav_save/settings_title）；修复 2 处显示缺陷（settings_cloud_row_desc 漏引用硬编码 "WebDAV"、cloud_add 被用作 host 输入框初值）；`autoStoreLocales` Manifest 配置补齐持久化；242 单测全绿（I18nResourcesTest×4 + LanguageSubpageTest×5）；Robolectric 不模拟 AppCompat LocaleManager，语言注入生效由真机验收。

## What Changes

1. `MainActivity` 基类 `ComponentActivity → AppCompatActivity`（appcompat 1.7.0 已存在，零新增依赖）。
2. 新建 `res/values/strings.xml`（英文，含全部键）与 `res/values-zh/strings.xml`（中文）；约 236 处硬编码文案迁移为 `stringResource` / `context.getString`。
3. 设置主页新增"语言"列举行 → 语言子页（返回箭头+标题+说明+三个 RadioButton：跟随系统/简体中文/English，默认跟随系统），选中即时生效；切换由 AppCompatDelegate 触发 Activity recreate（ViewModel/导航状态保持，停留当前界面）。
4. 日期格式与序数本地化：日期按有效 locale 输出（zh "07月12日" / en "Jul 12"）；"第X首/共Y首"→"Track X of Y"（getString 参数化）。
5. 用户数据（文件名/ID3 tag 标题/艺术家/专辑名）不翻译，原样显示。

## Requirements

- **REQ-1（三态语言模型）**: App 语言 MUST 支持"跟随系统/简体中文/English"三态，默认跟随系统；跟随系统时系统语言 zh→简体中文、en→English、其他→回退 English；手动选择即时生效并持久化（重启保持）。
- **REQ-2（官方机制）**: 语言切换 MUST 走 `AppCompatDelegate.setApplicationLocales`（AppCompat 内部 recreate，界面闪断一次可接受）；`MainActivity` 改为 `AppCompatActivity`；切换后 MUST 停留当前界面（导航状态 rememberSaveable 恢复、ViewModel 数据不丢）。
- **REQ-3（文案全量迁移）**: 约 236 处三类文案（纯 UI 静态/Toast 与错误提示/元数据兜底"未知艺术家"等）+ 通知渠道名 MUST 全部迁入 `values`（英文）与 `values-zh`（中文）双资源；Compose 用 `stringResource`，非 Composable 层用 `context.getString`。
- **REQ-4（日期与序数本地化）**: 日期显示 MUST 按有效 locale 本地化（zh "07月12日"/en "Jul 12"）；曲目序数 MUST 参数化本地化（zh "第1首/共15首"/en "Track 1 of 15"）。
- **REQ-5（入口设计统一）**: 设置主页 MUST 仅以"语言"列举行（图标+文字+右箭头）列举，点击进入子页；子页含返回箭头+标题+说明+三单选；主页不放控件。
- **REQ-6（数据不翻译）**: 用户音频数据（文件名、ID3 标题/艺术家/专辑名）MUST 原样显示，不进翻译资源。
- **REQ-7（测试与交付）**: 233 单测保持全绿；新增 i18n 完整性单测（遍历 R.string 全部键，zh/en 两种 locale 下均非空且文本不同）；版本 2.0.14；交付 debug APK。

#### Scenario: 跟随系统默认

- **WHEN** 全新安装未改语言，系统语言为中文 / 英文 / 日语
- **THEN** App 界面分别为简体中文 / English / English（回退）

#### Scenario: 手动切换

- **WHEN** 设置→语言→选择"English"或"简体中文"
- **THEN** 界面立即（重建后）切换为对应语言，重启后保持；切换后停留当前页面，播放状态/队列不丢

#### Scenario: 中文界面无残留

- **WHEN** 语言为简体中文，遍历全部界面与提示
- **THEN** 无英文残留；语言为 English 时遍历全部界面与提示（含 Toast/空态/错误/元数据兜底）无中文残留

#### Scenario: 日期与序数

- **WHEN** 播放详情/曲目列表/文件夹列表显示日期，语言为英文
- **THEN** 日期显示为 "Jul 12" 风格；序数显示 "Track 1 of 15"（中文为"第1首/共15首"）

#### Scenario: 元数据不翻译

- **WHEN** 歌曲标题/艺术家/专辑名为用户数据
- **THEN** 原样显示，不被翻译
