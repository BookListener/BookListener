---
id: t1-implement
status: in_progress
---

实施（v2.0.12）：
- 标题规格：`CloudConfigScreen`（WebDAV 账号/编辑页头）、`FolderManageScreen`（文件夹管理/详情/picker 页头）`titleLarge → titleMedium`。
- 空态规格：5 处统一 `fillMaxWidth + padding(top=48, start/end=24) + textAlign=Center + bodyLarge + onSurfaceVariant`；`MessageCatalog` 文案优化（EMPTY_HOME / EMPTY_DIR_AUDIO / EMPTY_MANAGE）+ 目录选择器/网盘配置局部文案；同步更新测试断言。
- 格线规格：文件夹管理列表行、文件夹详情文件行补 `HorizontalDivider`。
- 版本 2.0.12 / versionCode 66。
