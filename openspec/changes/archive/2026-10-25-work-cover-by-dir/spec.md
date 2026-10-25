# Spec

## 背景

用户场景：扫描根 A 下嵌套 A/B/C1，C1 内有 01.mp3/02.mp3（无内嵌封面）+ folder.jpg。当前封面全空。用户确认规格：**专辑内文件都在同一子文件夹时，在该子文件夹内查找封面**。

已核实事实：
- `ScanCoordinator.scan` 的 `files` = `Source.listFiles(rootUri)` 过滤目录与隐藏文件后的**完整文件列表（含图片/cue）**，本地与 WebDAV 同构；
- `SourceFile.relativePath`：根目录 = `""`，A/B/C1 下文件 = `"B/C1"`；
- `IndexedFile.relativePath` 已入库（v6 列）；
- 现状封面段只做"每组首曲 → `coverExtractor` 内嵌封面"。

## 规则（v2.1.23）

对每个 workKey（`WorkBackfill.workKey(entryId, folderName, album, albumArtist, artist)`）分组：

1. **收集目录集合**：组内所有音频行（含虚拟轨行，与整轨同目录）的 `relativePath ?: ""` 去重。
2. **目录集合大小为 1**（专辑全部文件在同一目录，含扫描根）：
   - 在该目录内查找封面图片：`files` 中 `relativePath == 该目录` 的文件；
   - 优先级与 `CoverScanner.findCover` 一致：常见命名（`PREFERRED_NAMES`）→ 任意图片；
   - 命中 → 该文件 path 作为作品封面。
3. **目录集合大小 > 1**（专辑跨子文件夹）：不查目录图片，防止跨目录串图。
4. **回退**：无目录图片 → 组内首曲内嵌封面（`coverExtractor`，现状逻辑）。
5. 结果合并写 `indexStore.updateWorkCovers(covers)`（目录图片优先，内嵌兜底）。

## 实现

- `ScanCoordinator`：
  - 封面段改为：先按 key 聚合目录集合 → 唯一目录时 `findDirCover(files, dir)` → 与内嵌封面合并；
  - 新增私有 `findDirCover(files: List<SourceFile>, dir: String): String?`（同目录常见命名 → 同目录任意图片）。
- `CoverScanner`：`isImageName` 从实例方法移入 companion 公开（行为不变，供扫描复用）；保留实例内调用。
- 版本号：versionCode 92 / versionName '2.1.23'。

## 验收

1. 扫描根 A/B/C1（mp3 无内嵌 + folder.jpg）→ 该作品 `works.coverUri == folder.jpg 的 uri`（ScanFlowTest 新用例断言）。
2. 专辑文件同根目录（A 根下有 folder.jpg）→ 封面命中（现状回归，已有行为保持）。
3. 专辑跨子文件夹（A/B、A/C 各一部分）→ 不取任何目录图片，走内嵌（新用例或现状断言）。
4. 无目录图片 → 首曲内嵌封面（现状回归）。
5. 全量 UT 通过；编译通过；debug 包交付。
