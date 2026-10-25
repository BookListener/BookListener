# Proposal: tag-fields-full（标签字段全量解析与入库）

## 背景

当前 `TagResolver` 用平台 `MediaMetadataRetriever` 解析标签，能力边界：
- 白名单六键（title/artist/album/track/disc/duration），文档（`tag_fields_reference.md`）中的系列字段（`©ser`/`©sno`/`stik` / `TXXX:SERIES` 等）与其余字段（年份/作曲/播客/影视/乐章/排序名…）**平台接口不暴露**；
- 仅本地 SAF 文件解析（WebDAV 按 Q3 边界不解析）。

已跑通的最小验证（MediaMetadataProbeTest，真实文件直驱 dump）证明：
- **ID3v2（MP3）**：Media3 1.4.0 提取器直驱可达全部文本帧，`TXXX`（规范 `\0` 分隔）desc/value 完整拆分；ffmpeg 冒号风格 TXXX 整体进 value（需兼容层）；
- **MP4 ilst（M4A）**：Media3 1.4.0 只映射 iTunes 标准原子（`©nam`→`TIT2` 等），`©ser`/`©sno`/`stik` **不可达**（白名单过滤，已实测锁死为断言）。

## 目标

1. **解析层**：ID3/FLAC/OGG 走 Media3 1.4.0 提取器直驱；MP4 ilst 自研解析器补齐（标准 + 扩展原子原始键名）；输出统一键值条目，覆盖文档 §3/§4 字段全集。
2. **入库层**：文档全部字段入库——常用字段结构化列（entry_index/works 扩展）+ 其余字段原始键值全量保留（新表 entry_tag_extras），不丢信息。
3. **接入层**：扫描链路（本地 SAF）替换 `TagResolver` 白名单解析；WebDAV 保持不解析。

## 决策（已确认）

- MP4 ilst 自研（60～100 行），不引入 jaudiotagger（为 3 个扩展字段不值得新依赖/脱糖风险；唯一不可替代场景=写标签，当前无需求）。
- Media3 官方新 API（inspector.MetadataRetriever）确认仍白名单，排除。
- 字段全集入库，含播客/影视/乐章/排序名等低频字段（原始键值保留，不逐列结构化）。

## 边界

- WebDAV 不解析标签（Q3 已确认，维持现状）。
- 封面（APIC/covr）不随扫描提取图片字节，仅记录存在性/元信息，图片仍走现有 CoverManager 路径按需取。
- 章节（CHAP/CTOC/chpl）作为副产品记录存在性/数量。
- 同一文件多标签（ID3v1+v2、重复帧）去重：结构化字段取首次/优先级，extras 保留全部。
