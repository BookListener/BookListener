## 01-implement
---
id: t1-implement
status: in_progress
---

实施（v2.0.12）：
- 标题规格：`CloudConfigScreen`（WebDAV 账号/编辑页头）、`FolderManageScreen`（文件夹管理/详情/picker 页头）`titleLarge → titleMedium`。
- 空态规格：5 处统一 `fillMaxWidth + padding(top=48, start/end=24) + textAlign=Center + bodyLarge + onSurfaceVariant`；`MessageCatalog` 文案优化（EMPTY_HOME / EMPTY_DIR_AUDIO / EMPTY_MANAGE）+ 目录选择器/网盘配置局部文案；同步更新测试断言。
- 格线规格：文件夹管理列表行、文件夹详情文件行补 `HorizontalDivider`。
- 版本 2.0.12 / versionCode 66。

## 02-verify-deliver
---
id: t2-verify-deliver
status: in_progress
---

验证与交付（v2.0.12）：
- `./gradlew testDebugUnitTest assembleDebug`：233 单测全绿 + 构建通过。
- 交付 debug APK（BookListener-v2.0.12-ui-spec.apk）。
- 真机验收三项规格：标题统一 titleMedium、空态居中+文案引导、列表格线；主页空态同规格。

