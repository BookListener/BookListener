# Design

## Context

现状（见 proposal.md - Why）：无损整轨 + .cue 无法选曲/显名/跳转。数据库已作品实体化（v2.1.0：works/work_progress/entry_index，进度内容引用 (workId, fileUri)+orderIndex 近似回退）；列表命名定稿（专辑/曲目/内容管理/内容文件/虚拟轨）；可播放文件列表已废弃（无此入口）。约束：minSdk 26（SQLite 3.18，无 RENAME / 无 ON CONFLICT DO UPDATE）；Media3 `ClippingConfiguration` 在解码-渲染层裁剪；无 instrumented 测试（纯逻辑必须 JVM 可测）。

## Goals / Non-Goals

**Goals**：cue 解析 → 虚拟轨普通媒体条目化入库 → 整轨吸收（专辑/曲目层）→ 虚拟轨播放（裁剪窗口）→ 内容文件 .cue 行特殊点击进虚拟轨列表（纯浏览）→ 进度走 v4 内容引用。

**Non-Goals**：真切割/重编码；增量刷新；APE/DSD seek 精度修复；PREGAP/POSTGAP 精确处理；可播放文件列表回归；cue 文件的媒体条目化。

## Decisions

### D1：cue 文件用独立轻量表 `cue_sheets` 关联，虚拟轨不挂 cue 字段

虚拟轨作为普通媒体条目（无 cue 专属列，符合"媒体数据一张表"）；cue 文件本身非媒体条目，其存在与归属用 `cue_sheets(cueUri PK, entryId, audioUri, workId)` 表达。
**备选**：虚拟轨行加 cuePath 列——污染媒体条目、违反"不加 cue 专属字段"收敛；entry_index 加"cue 标记行"——混淆可播放/不可播放语义。选独立表：cue 与媒体条目生命周期不同（cue 失败退化为整轨普通条目时 cue_sheets 行删除）。

### D2：虚拟轨 uri = `<audioUri>#track_<startMs>`，同时充当 mediaId 与进度 fileUri

整轨共用同一 audioUri，靠 startMs 区分；进度 (workId, 虚拟uri) 精确匹配天然成立；cue 编辑 → startMs 变化 → uri 变 → orderIndex 近似回退（v4 已有，诚实标注）。**备选**：独立递增 id——丢失"uri 即内容引用"的统一性，恢复逻辑要另建映射。

### D3：作品键复用既有规则，cue 专辑 = `local:<performer?:entryName>|<album?:cueBaseName>`

cue 有全局 PERFORMER/TITLE → 按表演者/专辑聚合（可与同名 tag 专辑合并）；缺失 → 条目名/cue 文件名兜底（与无 tag 文件逻辑一致）。**备选**：cue 专属 key 前缀——破坏跨源合并一致性。

### D4：吸收 = 扫描时 cue 定位成功则整轨 uri 不写 entry_index；失败则写

吸收只在**扫描写入路径**判定（saveEntryIndexWithWorks 前置 cue 预处理），不新增运行时逻辑；cue 文件 mtime 变化 → 重扫重判定（现有扫描机制覆盖）。**风险**：整轨曾作为普通条目入过库 → 吸收后该行被清理（cleanupOrphanWorks 既有机制）+ 其进度断链（orderIndex 回退可承受，因整轨整体不再作为播放对象）。

### D5：虚拟轨列表页数据源 = db（按 workId 查虚拟轨行），不重新解析 cue

点击 .cue 行 → cue_sheets 取 workId → 查虚拟轨行渲染；解析只在扫描时发生一次。**备选**：点击时重解析——与"入库和索引"收敛矛盾，且 WebDAV 需再次网络读文本。

### D6：CueParser 纯函数（无 Android 依赖），状态机单遍扫描

多 FILE 段：维护"当前 FILE 上下文"（referencedFile 随 FILE 行切换，轨归属当前上下文）；INDEX 00 记录为上一轨结束备选（有 INDEX 01 时以 01 为准）；MM:SS:FF→ms 用 75 帧/秒。放 `core/`（与 WorkBackfill 同级，JVM 可单测）。

## Risks / Trade-offs

- [cue 编辑导致全轨 startMs 漂移 → 进度近似回退] → v4 matchMode=0 诚实标注，可接受（虚拟轨播放对象不变）
- [整轨曾入库的用户：吸收后整轨进度丢失] → 吸收只发生在"有 cue 且定位成功"的目录，属语义升级；CHANGELOG 行为变更说明
- [WebDAV readText 需网络] → 仅扫描时一次小文本读取；失败按"无 cue"处理（整轨普通条目），不阻塞扫描
- [ape/dsf/dff seek 精度依赖解码器] → 显式 mimeType 派发；精度问题不在本 change 解决（文档已知限制）

## Migration Plan

- MIGRATION_4_5：仅 `CREATE TABLE cue_sheets`（新表，无数据搬移）；新装直达 v5。
- 回滚：保留 MIGRATION_4_5 反向为空；卸载/降级由系统处理（本项目惯例：老表停写保留）。

## Open Questions

无（列表命名、cue 入口、吸收语义均已在 grill 收敛并落 spec）。
