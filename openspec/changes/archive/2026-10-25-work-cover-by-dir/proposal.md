# Proposal

## Why

嵌套目录场景目录图片封面不生效（用户持续反馈"封面解析还是不生效"）。已核实根因：`CoverScanner.findCover` 只扫扫描根一层且不递归；`ScanCoordinator.scan` 的封面段（v2.1.15）只做音频内嵌封面提取，从未接入目录图片查找。用户确认规格：**专辑内文件都在同一子文件夹时，在该子文件夹内查找封面**。

## What Changes

- `ScanCoordinator.scan` 封面段改造：按 workKey 分组后计算组内音频的父目录集合（relativePath 目录部分）——
  - **唯一目录**（含扫描根 `""`）：在扫描原始文件列表（已含图片，零额外 IO）中按该目录匹配常见命名图片（`CoverScanner.PREFERRED_NAMES`，与 `findCover` 同优先级：常见命名 → 任意图片），命中即作品封面；
  - **跨目录专辑**（目录集合 > 1）：不查目录图片（防 v2.1.13 串图回归），回退首曲内嵌封面；
  - 无目录图片 → 回退首曲内嵌封面。
- 本地与 WebDAV 统一生效：两者都经 `Source.listFiles` 拿到含图片的完整文件列表 + relativePath。
- `CoverScanner.findCover`（条目封面）不动：条目封面规则保持现状，本次只改作品封面（works.coverUri）。

## Impact

- `core/ScanCoordinator.kt`：封面段改造 + 私有 `findDirCover` 辅助
- `core/CoverScanner.kt`：`isImageName` 移入 companion 公开（供扫描复用），行为不变
- 测试：ScanFlowTest 增加嵌套目录目录图片封面用例；ScanCueFlowTest 不受影响
- 版本：2.1.22 → 2.1.23（patch，未人工确认不动次要）

## Out of Scope

- 条目封面（saved_entries.coverUri）解析逻辑
- WebDAV 无内嵌封面时的降级展示（维持现状）
- 播放器、数据库结构
