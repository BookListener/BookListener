# Proposal

## Why

无损整轨资源（flac/wav + .cue 索引）在专辑/曲目视图中无法选曲、显示曲目名与按曲跳转。利用 Media3 `ClippingConfiguration` 实现虚拟分轨：不切割文件、零额外存储、位完美保真；虚拟轨作为普通媒体条目入库，与本地/网盘文件统一消费（媒体数据一张表），补齐无损整轨的选曲体验。

## What Changes

- 新增 `CueParser` 纯函数（JVM 可测）：多 FILE 段上下文、INDEX 00/01、MM:SS:FF→ms（75 帧/秒 CD 标准）、末轨 end=null（播到文件尾）、PREGAP/POSTGAP 忽略、无 INDEX 轨跳过。
- 新增 `cue_sheets` 关联表：cue 文件 → (entryId, audioUri, workId)；cue 文件本身**不是**媒体条目，单独索引供「内容文件」列表展示与入口。
- 扫描集成（本地 SAF / WebDAV）：目录存在 .cue → 解析 → 整轨定位（FILE 声明优先 + 同名扩展名探测 flac/ape/wav/mp3/m4a/dsf/dff）→ 虚拟轨行入库（uri = `<audioUri>#track_<startMs>`，album/artist 取自 cue，作品 key = `local:<performer?:entryName>|<album?:cueBaseName>`）→ **吸收整轨**（cue 定位成功则整轨不写媒体条目）；解析失败/未定位 → 整轨按普通条目入库。
- 播放：虚拟轨 MediaItem + ClippingConfiguration + 显式 mimeType（ape/dsf/dff 无标准 MIME）；mediaId = 虚拟 uri；进度 (workId, 虚拟uri) 走既有内容引用机制。
- UI：**内容文件**列表（内容管理 → 条目）常规行无点击；**.cue 行特殊点击 → 虚拟轨列表**（纯浏览，不关联播放/入队）；列表命名定稿：**专辑 → 曲目 → 内容管理 / 内容文件 → 虚拟轨**（GLOSSARY 同步）。
- MIGRATION_4_5：cue_sheets 建表（存量库 +1 表，新装直达 v5）。

## Capabilities

### New Capabilities

- `cue-virtual-tracks`: CUE 整轨虚拟分轨能力——cue 解析、整轨吸收、虚拟轨入库与播放、内容文件 cue 入口与虚拟轨浏览。

### Modified Capabilities

（无——database-redesign-v4 的作品/进度模型被复用，其需求不变；cue_sheets 为新增表，不改变既有 spec 需求。）

## Impact

- **db**：AppDatabase v5 + `cue_sheets`；AppDao 新增 cue 查询（cueSheet 按 cueUri 查、虚拟轨按 workId 查、按 entryId 列 cue 行）。
- **扫描**：SavedEntry 扫描路径（本地 `scanFolder` / WebDAV `openEntryView`）挂 cue 解析与吸收。
- **播放**：PlayerViewModel 虚拟轨队列构造（ClippingConfiguration）、进度按虚拟 uri 反查 workId。
- **UI**：FolderManageScreen 内容文件列表 .cue 行点击 → 虚拟轨列表页（复用曲目行渲染）；新 i18n 文案（虚拟轨/内容文件等）。
- **测试**：CueParserTest / ScanCueTest（吸收与退化）/ Migration45Test / 内容文件入口 UI 回归；全量回归 + debug 包。
