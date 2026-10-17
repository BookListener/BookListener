# app-i18n-official-res Specification

## ADDED Requirements

### Requirement: 三态语言模型

App 语言 MUST 支持"跟随系统/简体中文/English"三态，默认跟随系统；跟随系统时系统语言 zh→简体中文、en→English、其他→回退 English；手动选择即时生效并持久化（重启保持）。

#### Scenario: 跟随系统默认

- **WHEN** 全新安装未改语言，系统语言为中文 / 英文 / 日语
- **THEN** App 界面分别为简体中文 / English / English（回退）

#### Scenario: 手动切换持久化

- **WHEN** 设置→语言→选择"English"或"简体中文"，重启 App
- **THEN** 界面为所选语言且重启后保持

### Requirement: 官方机制

语言切换 MUST 走 `AppCompatDelegate.setApplicationLocales`（AppCompat 内部 recreate，界面闪断一次可接受）；`MainActivity` MUST 改为 `AppCompatActivity`，主题 MUST 为 AppCompat 主题（`Theme.AppCompat.DayNight.NoActionBar`，平台 Material 主题缺少 AppCompat `windowActionBar` 属性会导致基类启动崩溃）；`AndroidManifest` MUST 声明 `AppLocalesMetadataHolderService` + `autoStoreLocales=true`（否则语言选择不持久化、重启丢失）；切换后 MUST 停留当前界面（导航状态 rememberSaveable 恢复、ViewModel 数据不丢）。

#### Scenario: 切换停留当前界面

- **WHEN** 在设置→语言子页切换语言
- **THEN** 界面重建后仍停留设置页（或切换前的同层级界面），播放状态/队列/进度不丢

### Requirement: 文案全量迁移

约 236 处三类文案（纯 UI 静态/Toast 与错误提示/元数据兜底"未知艺术家"等）+ 通知渠道名 MUST 全部迁入 `values`（英文）与 `values-zh`（中文）双资源；Compose 用 `stringResource`，非 Composable 层用 `context.getString`。

#### Scenario: 中文界面无残留

- **WHEN** 语言为简体中文，遍历全部界面与提示
- **THEN** 无英文残留

#### Scenario: 英文界面无残留

- **WHEN** 语言为 English，遍历全部界面与提示（含 Toast/空态/错误/元数据兜底）
- **THEN** 无中文残留

### Requirement: 日期与序数本地化

曲目序数 MUST 参数化本地化（zh "第1首/共15首"/en "Track 1 of 15"）。勘察实证：App 自身无独立日期生成点（CrashLogger 日志时间戳固定 `Locale.US` 格式，与 UI 无关；UI 所见"07月12日"式文本为用户音频文件名/tag 数据，属 REQ 数据不翻译范围），故本版不引入日期本地化。

#### Scenario: 曲目序数本地化

- **WHEN** 播放详情显示"第X首/共Y首"，语言为 English
- **THEN** 显示为 "Track X of Y"（中文为"第X首/共Y首"）

### Requirement: 入口设计统一

设置主页 MUST 仅以"语言"列举行（图标+文字+右箭头）列举，点击进入子页；子页含返回箭头+标题+说明+三单选；主页不放控件。

#### Scenario: 语言入口

- **WHEN** 进入设置主页
- **THEN** 主页只有"语言"列举行（图标+文字+右箭头），无任何控件；点击进入子页，子页有返回箭头、标题、说明与三个单选项（跟随系统/简体中文/English），选中即时生效

### Requirement: 数据不翻译

用户音频数据（文件名、ID3 标题/艺术家/专辑名）MUST 原样显示，不进翻译资源。

#### Scenario: 元数据不翻译

- **WHEN** 歌曲标题/艺术家/专辑名为用户数据
- **THEN** 原样显示，不被翻译

### Requirement: 测试与交付

既有单测 MUST 保持全绿；MUST 新增 i18n 完整性单测（zh/en 键集一致、全值非空、en 无 CJK 漏译、除专名外两语言文本不同）；MUST 新增语言子页 UI 测试（三选项齐备、默认跟随系统、点击路径无异常）；版本 MUST 为 2.0.14 / versionCode 68；MUST 交付 debug APK。

#### Scenario: i18n 完整性单测

- **WHEN** 运行测试
- **THEN** `I18nResourcesTest` 验证 zh/en 键集完全一致、全值非空、en 无中文残留、除专名外文本不同；`LanguageSubpageTest` 验证语言子页三选项、默认跟随系统、连续切换不崩溃；全部 242 单测全绿

#### Scenario: 真机验收语言注入

- **WHEN** 真机安装 debug 包，设置→语言选择 English/简体中文并重启 App
- **THEN** 界面语言切换生效且重启保持（Robolectric 不模拟 AppCompat LocaleManager，`getApplicationLocales` 恒空，注入生效仅由真机验收）
### Requirement: 文案显示可达性

每个字符串键 MUST 具备明确的显示归属：可见 Text（可达分支）、无障碍描述、事件消息/通知三者之一；禁止硬编码文案顶替资源键（如副标题硬编码 "WebDAV"）；禁止将入口语义键用作表单数据初值；无任何引用的死键 MUST 从双语言文件同步删除。

#### Scenario: 无死键与无硬编码

- **WHEN** 静态扫描全部 R.string 引用并审读渲染可达性
- **THEN** 141 键均有归属（108 可见 / 21 无障碍 / 12 事件通知）；5 个零引用死键已删除；`settings_cloud_row_desc` 正常显示于云盘行副标题；新建表单 host 初值为空
