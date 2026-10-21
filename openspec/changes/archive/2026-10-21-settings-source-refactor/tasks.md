# Tasks

## 阶段一：数据层

- [x] 1. entry_index 增加 error 字段（TEXT，可空）
- [x] 2. AppDatabase 升级 v6，MIGRATION_5_6（ALTER TABLE ADD COLUMN error）
- [x] 3. DAO 增加按 entryId 查询文件 Flow（含 error 字段）
- [x] 4. DAO 增加按相对路径筛选查询（网盘子目录导航用）
- [x] 5. 迁移测试 Migration56Test

## 阶段二：来源抽象

- [x] 6. 定义 SourceFile 数据结构（名称/路径/相对路径/是否目录/大小/修改时间）
- [x] 7. 定义 Source 接口（suspend fun listFiles(rootUri): List<SourceFile>）
- [x] 8. LocalSource 实现（SAF 递归遍历，maxDepth=10）
- [x] 9. WebDavSource 实现（PROPFIND 递归，返回相对路径）
- [x] 10. 单测：LocalSource/WebDavSource 文件收集与相对路径

## 阶段三：扫描协调器

- [x] 11. ScanCoordinator 实现：列目录 → 标签解析 → CUE 收集 → 写索引 → 作品聚合
- [x] 12. 单文件失败降级：写入 error 字段，文件名兜底
- [x] 13. CUE 降级：失败时 error 字段，不吸收整轨
- [x] 14. 全量替换索引（事务内先删后写）
- [x] 15. 封面按目录分组提取（不跨子文件夹）
- [x] 16. 子文件夹递归扫描（相对路径标注）
- [x] 17. 单测：ScanCoordinator 各降级路径

## 阶段四：UI 层

- [x] 18. FolderManageScreen 改为观察 DAO Flow，条目行去掉封面
- [x] 19. DirectoryScreen 改为观察 DAO Flow（按 entryId）
- [x] 20. 文件级错误图标显示与点击查看原因
- [x] 21. rawName 配置项子页（文件夹管理子页内）
- [x] 22. 目录视图保留 rawName 快捷切换
- [x] 23. 网盘子目录导航改为从索引筛选
- [x] 24. 刷新按钮统一行为（实时列目录 → 全量替换）
- [x] 25. 删除条目级联清理
- [x] 26. UI 测试更新

## 阶段五：清理与回归

- [x] 27. 删除旧 scanFolder/openEntryView 分散逻辑
- [x] 28. 删除内存文件列表副本
- [x] 29. 全量单测回归
- [x] 30. 升版（patch）+ 构建 debug
- [x] 31. SPECS.md 更新
- [x] 32. 归档 openspec
