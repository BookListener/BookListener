## 01-base-entry
---
id: t1-base-entry
status: in_progress
---

实施基础与入口（v2.0.14）：
- `MainActivity` 基类 `ComponentActivity → AppCompatActivity`（appcompat 1.7.0 已在依赖）。
- 新建 `res/values/strings.xml`（English，全部键）与 `res/values-zh/strings.xml`（简体中文）；`app_name` 双语言。
- 设置主页新增"语言"列举行（图标+文字+右箭头）→ 语言子页（BackHandler+返回箭头+标题+说明+三 RadioButton：跟随系统/简体中文/English，默认跟随系统）。
- 语言切换：`AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"/"en"))` 或清空（跟随系统）；AppCompat 自动持久化；导航状态用 rememberSaveable 保证重建后停留当前界面。

## 02-strings-migration
---
id: t2-strings-migration
status: in_progress
---

文案迁移（v2.0.14，全量约 236 处）：
- 提取全部硬编码中文（按文件：SettingsScreen 51 / FolderManageScreen 32 / PlayerDetailSheet 31 / PlayerViewModel 31 / CloudConfigScreen 22 / DirectoryScreen 19 / MainScreen 15 / MessageCatalog 14 / CommonUi / core 兜底文案等）。
- 新建 `res/values/strings.xml`（**English**，默认/兜底，全部键）与 `res/values-zh/strings.xml`（简体中文，键一一对应）；`app_name` 双语言。
- key 命名：按语义域组织（如 `settings_language`、`folder_empty_title`、`player_track_of`、`toast_added_queue`、`meta_unknown_artist` 等）。
- 代码替换：Composable 层 `stringResource(R.string.x)`；非 Composable（PlayerViewModel Toast/通知）用 `context.getString(R.string.x)`（context 注入已有渠道则复用，否则补）。
- 元数据（文件名/ID3 tag）不翻译，原样。
- 通知渠道名本地化（渠道创建时用当前 locale 文案；已知限制：已创建渠道系统固化）。

## 03-date-locale
---
id: t3-date-locale
status: in_progress
---

日期与序数本地化（v2.0.14）：
- 定位全部日期显示点：播放详情（"07月12日"）、曲目列表（"2026年07月12日 09点08分"）、文件夹列表、网盘目录等。
- 日期 pattern 入资源：`values`（en）"MMM d" / "MMM d, yyyy HH:mm" 等，`values-zh`（zh）"MM月dd日" / "yyyy年MM月dd日 HH点mm分"；用 `SimpleDateFormat(pattern, locale)` 按有效 locale 输出（getString 取 pattern）。
- 序数参数化：`player_track_of` = zh "第%1$d首/共%2$d首" / en "Track %1$d of %2$d"；替换现有硬编码拼接。
- 数字格式（千分位）首版不做。

## 04-test-deliver
---
id: t4-test-deliver
status: completed
---

测试与交付（v2.0.14）：
- [x] i18n 完整性单测 `I18nResourcesTest`（纯文件解析：zh/en 键集一致、全值非空、en 无 CJK、专名白名单外文本不同）——4 项全过。
- [x] 语言子页单测 `LanguageSubpageTest`（三选项齐备、默认跟随系统、三种选项点击路径连续切换无异常）——5 项全过；注：Robolectric 不模拟 AppCompat LocaleManager（getApplicationLocales 恒空），切换注入生效列为真机验收项。
- [x] 既有单测适配（UI 文案断言改英文资源文案、SleepTimer 断言改 getString 解析值）+ 修复 PlayerDetailSheet 空态 2 处漏包 stringResource、MessageCatalog const val→val（Kotlin IR 常量求值崩溃）。
- [x] 显示可达性核查：5 个零引用死键双语言删除；settings_cloud_row_desc 漏引用修复；cloud_add 表单初值修复。
- [x] 版本 2.0.14 / versionCode 68；`testDebugUnitTest assembleDebug` 242 全绿；debug APK 交付（19.4MB）。
