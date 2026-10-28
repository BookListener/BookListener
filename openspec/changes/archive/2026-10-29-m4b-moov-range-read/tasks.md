## 01-moov-locator
# 实施：moov 区间定位器

## 任务

- [ ] 1.1 新增 `core/MoovLocator.kt`：box 遍历定位逻辑（纯函数，复用 `KNOWN_BOX_TYPES` 白名单；输入 ByteArray/Reader，输出 moov box 的 [start, endExclusive) 区间）——支持 moov 在头部与尾部两种形态。
- [ ] 1.2 新增 SAF 读取壳（`ScanCoordinatorFactory` 内或独立）：`openAssetFileDescriptor(uri, "r")` 取 length → 头 1MB 定位 → 无则尾 16MB 反向定位 → 返回「ftyp 起 + moov 结束」连续区间字节。
- [ ] 1.3 定位失败/异常 → 回退整读（`readSafBytes` 现有实现兜底）。

## 验收

- 单测覆盖：moov 头部 / moov 尾部 / 非 box 前导 三类构造字节流，定位区间正确；失败路径回退整读（flow 或单测断言）。

## 02-scan-integration
# 实施：扫描接入区间字节

## 任务

- [ ] 2.1 `resolveLocalTags`：`ILST_EXTS` 分支改用区间字节（替换 `readSafBytes` 全读），三个解析器输入改为同一份区间字节。
- [ ] 2.2 非 `ILST_EXTS` 分支（mp3/flac/ogg）保持整读现状。

## 验收

- `Mp4IlstReader`/`Mp4ChapterReader` 对区间字节输出与整读一致（等价性单测）；`M4bFlowTest` 回归通过。

## 03-version-deliver
# 实施：版本升级与交付

## 任务

- [ ] 3.1 `app/build.gradle`：versionCode 101 / versionName '2.2.5'（patch 位，含 43eb029 健壮性修复一并纳入）。
- [ ] 3.2 全量 UT 0 失败；`assembleDebug`；aapt 核验 101/2.2.5；复制为仓库根 `BookListener-v2.2.5-moov-range.apk`。
- [ ] 3.3 git 提交（固定 `-c user.name=BookListener` 参数）；openspec 归档（`openspec change validate` → `openspec change archive --yes`）；SPECS 头部升 v2.2.5 并追加变更记录。

## 验收

- APK 交付；用户真机重扫 1GB m4b 不 OOM、系列聚合 + 章节虚拟轨正常。
