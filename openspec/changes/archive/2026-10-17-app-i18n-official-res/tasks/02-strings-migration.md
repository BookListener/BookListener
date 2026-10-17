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
