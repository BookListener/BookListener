---
id: t1-base-entry
status: in_progress
---

实施基础与入口（v2.0.14）：
- `MainActivity` 基类 `ComponentActivity → AppCompatActivity`（appcompat 1.7.0 已在依赖）。
- 新建 `res/values/strings.xml`（English，全部键）与 `res/values-zh/strings.xml`（简体中文）；`app_name` 双语言。
- 设置主页新增"语言"列举行（图标+文字+右箭头）→ 语言子页（BackHandler+返回箭头+标题+说明+三 RadioButton：跟随系统/简体中文/English，默认跟随系统）。
- 语言切换：`AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"/"en"))` 或清空（跟随系统）；AppCompat 自动持久化；导航状态用 rememberSaveable 保证重建后停留当前界面。
