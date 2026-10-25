# Spec: tag-fields-full

## 背景

详见 proposal.md。核心事实（已实测）：Media3 1.4.0 直驱可达 ID3 全文本帧（含 TXXX desc/value）；MP4 ilst 仅映射 iTunes 标准原子，`©ser`/`©sno`/`stik` 不可达——自研 ilst 补齐。

## Requirements

### Requirement: MP4 ilst 自研解析器（Mp4IlstReader）

新增 `core/Mp4IlstReader.kt`，输入完整文件字节（本地 SAF 扫描读全），沿 `moov → udta → meta → ilst` 遍历 box（大端 size+type），解析每个子原子及其 `data` 子原子（4B typeFlags + 4B locale + payload），输出键值条目：

- 文本原子（typeFlags=1，UTF-8）：`©ser`/`©sno`/`©nam`/`©alb`/`©ART`/`aART`/`©gen`/`©day`/`©wrt`/`©cmt`/`©cpy`/`©des`/`ldes`/`©lyr`/`catg`/`keyw`/`purl`/`egid`/`cpil`/`©grp`/`soal`/`soar`/`sonm`/`soco`/`tvsh`/`tvsn`/`tven`/`©too`/`----` 等 → `key=原子名, value=文本`
- 整数/二进制原子（typeFlags=0）：`stik`（2B）→ 数值字符串；`trkn`（8B `[flags][trk][tot]`）→ `"trk/tot"`；`disk` 同理 → `"n/total"`
- 图片原子 `covr`（typeFlags=13/14）：不提取字节，仅记录 `存在+mime 推断` 条目
- 解析失败/无 ilst → 返回空列表（不抛、不影响扫描）

#### Scenario: 标准 + 扩展原子 m4a

- **GIVEN** `probe_custom2.m4a`（含 `©nam`/`©ART`/`©alb`/`aART`/`trkn`/`disk`/`©gen`/`©ser`/`©sno`/`stik`）
- **WHEN** Mp4IlstReader 解析
- **THEN** 输出含 `©ser=哈利·波特系列`、`©sno=1`、`stik=2`、`trkn=1/17`、`disk=1/7`，全部为原始键名

#### Scenario: 无 meta/ilst 的文件

- **GIVEN** 无标签 m4a
- **THEN** 返回空列表，无异常

### Requirement: Media3 直驱元数据读取器（MetadataExtractor）

新增 `core/MetadataExtractor.kt`：纯提取器直驱（复用探针已验证链路），按容器选择：
- MP3 → `Mp3Extractor`；FLAC → `FlacExtractor`；OGG → `OggExtractor`；其余非 MP4 容器同探针策略（不支持→空）
- 收集 `TrackOutput.format().metadata` 条目：
  - `TextInformationFrame`：标准帧按 id 取（TIT2/TALB/TPE1/TPE2/TCON/TRCK/TPOS/TDRC/TYER/TCOM/TEXT/TCOP/TIT1/TIT3/TCMP/GRP1/TSOx/MVNM/MVIN/TSSE/TVSH/TVSN/TVES/PCST/TDES/TKWD/TCAT/TGID/WFED…）；`TXXX` 用 `description` 作键、`values` 首个作值
  - `CommentFrame`（COMM）、`ApicFrame`（APIC 存在性）、`ChapterFrame`/`ChapterTocFrame`（CHAP/CTOC 数量）
  - 其他帧（USLT/PRIV/GeobFrame 等）→ 按存在性/文本化入 extras
- **ffmpeg 冒号风格 TXXX 兼容**：`desc` 为空白/TXXX 且 value 形如 `KEY:VALUE` 时按首个冒号拆键值
- 去重：同键多次出现（ID3v1+v2、重复帧）结构化字段取首次，extras 保留全部

#### Scenario: 规范 TXXX mp3

- **GIVEN** `probe_txxx.mp3`（含规范 `\0` 分隔 TXXX(SERIES=哈利·波特系列) 与 ffmpeg 风格 TXXX(SERIESNUMBER:1)）
- **WHEN** MetadataExtractor 解析
- **THEN** 输出含 `SERIES=哈利·波特系列`（desc/value 拆分）与 `SERIESNUMBER=1`（冒号兼容拆分）

### Requirement: 统一键值模型与字段映射

新增 `core/TagFields.kt`：
- `TagEntry(key, value, source)`（source=ID3/MP4）
- 业务语义→键 映射表（文档 §5 对照总表）：系列名=`©ser`/`TXXX:SERIES`/`TIT1`；系列序号=`©sno`/`TXXX:SERIESNUMBER`；媒体类型=`stik`/`TCON`/`TXXX:MEDIATYPE`；其余按 §3/§4 全表
- 结构化字段提取函数：title/artist/album/albumArtist/track/disc/genre/year/composer/series/seriesNumber/mediaType/encoder

### Requirement: 数据库迁移 v6→v7（全字段入库）

`AppDatabase` version 6→7，新增 `MIGRATION_6_7`：
- `entry_index` ADD：`genre TEXT`、`year INTEGER`、`composer TEXT`、`series TEXT`、`seriesNumber TEXT`、`mediaType TEXT`、`encoder TEXT`
- `works` ADD：`series TEXT`、`seriesNumber TEXT`、`mediaType TEXT`
- CREATE TABLE `entry_tag_extras`：`entryId TEXT`、`uri TEXT`、`key TEXT`、`value TEXT`、`source TEXT`，PK `(entryId, uri, key)`

#### Scenario: 迁移完整性

- **GIVEN** 旧库（version 6）
- **WHEN** 打开新库
- **THEN** 表结构含新列与新表，既有数据保留（ADD 列默认 NULL 不回填）

### Requirement: 扫描写入路径接入

本地 SAF 扫描时：解析 → `entry_index` 结构化列 upsert + `entry_tag_extras` 批量写入（文档全部字段，原始键值不丢）+ `works` 聚合（series/seriesNumber/mediaType 取该作品首条非空）。
- `ScanCoordinator` 的 `tagReader` 注入点替换为 MetadataExtractor + Mp4IlstReader 组合（按扩展名分流：`.m4a/.mp4` → ilst；其余 → Media3），**唯一解析后端**
- `TagResolver` 的 `MediaMetadataRetriever` 白名单解析路径废弃（不再作为解析后端）；仅保留业务层显示名回退语义（`displayNameFor`：title 空白 → 去扩展名文件名），与解析器解耦
- 解析失败 → 返回空字段/空列表，文件行走现有 `error` 字段显示失败原因，与"直接显示文件"降级一致
- WebDAV 不解析（维持）

#### Scenario: 扫描入库全字段

- **GIVEN** `probe_custom2.m4a` / `probe_txxx.mp3` 经扫描入库
- **THEN** `entry_index` 行 series/seriesNumber/mediaType/genre 等非空；`entry_tag_extras` 含 `©ser`/`©sno`/`stik`/`SERIES`/`SERIESNUMBER` 等键；`works` 聚合出 series

### Requirement: 测试

- `Mp4IlstReaderTest`：probe_custom2.m4a 全字段断言（含 `©ser`/`©sno`/`stik`/`trkn`/`disk`）；无 ilst 文件空列表
- `MetadataExtractorTest`：probe_txxx.mp3 TXXX 双风格拆分；标准帧全量
- 入库场景测试（业务入口）：扫描 probe 文件 → 断言 entry_index/extras/works
- `MediaMetadataProbeTest` 保留（锁死 Media3 1.4.0 MP4 行为）
- 全量 UT 回归 + 编译 + debug APK

## 版本

已人工确认 → 实施完成后升 minor `2.2.0`（versionCode 96）。
