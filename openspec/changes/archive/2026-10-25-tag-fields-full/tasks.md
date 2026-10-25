# Tasks

- [x] proposal.md / spec.md 编写（待确认）
- [x] TagFields.kt：TagEntry + 业务语义→键映射表 + 结构化字段提取
- [x] Mp4IlstReader.kt：自研 ilst 解析（moov→udta→meta→ilst→data 子原子，文本/整数/图片）
- [x] MetadataExtractor.kt：Media3 直驱（Mp3/Flac/Ogg 提取器 + format.metadata 收集 + TXXX 双风格兼容 + 去重）
- [x] AppDatabase：version 6→7 + MIGRATION_6_7（entry_index/works 加列 + CREATE entry_tag_extras）
- [x] StoreEntities/WorkEntities：实体加列 + EntryTagExtraEntity
- [x] AppDao：新列写入 + extras 批量 upsert + 查询
- [x] ScanCoordinator/WorkBackfill：tagReader 接入新解析器（按扩展名分流 m4a→ilst，其余→Media3，唯一后端），废弃 TagResolver 白名单解析（保留 displayNameFor 业务回退），extras 写入，works 聚合 series 字段
- [x] Mp4IlstReaderTest（probe_custom2.m4a 全字段 + 无 ilst 空列表）
- [x] MetadataExtractorTest（probe_txxx.mp3 TXXX 双风格 + 标准帧全量）
- [x] 入库场景测试（业务入口：扫描 probe 文件 → 断言 entry_index/extras/works）
- [x] 全量 UT 回归（MediaMetadataProbeTest 保留锁死 1.4.0 行为）
- [x] 升 2.2.0（versionCode 96）+ assembleDebug
- [x] SPECS.md 补条文 + 归档（openspec）+ git 提交 + 交付
