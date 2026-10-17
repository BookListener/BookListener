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