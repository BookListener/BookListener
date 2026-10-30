# m4a-m4b-series-freeform-priority Specification

## Purpose

系列分组标签解析优先级调整：MP4 链路（m4a/m4b/mp4/alac）系列字段首选自由格式原子（`----`，name=`SERIES`/`SERIES-PART` 及其小写镜像），`©ser`/`©sno` 降级；多命名空间下系列名与系列序号必须同源；mp3（ID3）链路不变。

## ADDED Requirements

### Requirement: 自由格式键保留命名空间

App MUST 使 `Mp4IlstReader.parseFreeForm` 输出键保留命名空间（issuer）：键 = `----:<issuer>:<name>`（issuer 非空）；issuer 为空时保持旧格式 `----:<name>`（空 issuer 视为独立命名空间）。

#### Scenario: 命名空间保留

- **WHEN** 自由格式原子 payload = `\0nsA\0SERIES\0三体`
- **THEN** 输出键 `----:nsA:SERIES`（value=三体）

#### Scenario: issuer 为空

- **WHEN** 自由格式原子 payload = `\0\0SERIES\0三体`
- **THEN** 输出键 `----:SERIES`（视为独立命名空间）

### Requirement: 系列名解析优先级（MP4 自由格式首选）

App MUST 在 MP4 链路解析系列名时按以下优先级取值：

1. **首选**：自由格式系列名键（name=`SERIES` 或 `series`，命名空间内大小写不敏感归一）；
2. **降级**：键 `©ser`；
3. 均缺失 → 系列名为空（聚合归无系列专辑）。

#### Scenario: 自由格式首选

- **WHEN** m4b 同时含 `----:nsA:SERIES`（值「三体」）与 `©ser`（值「旧值」）
- **THEN** 结构化 `series` 列取「三体」，主页按「三体」分组

#### Scenario: 小写镜像

- **WHEN** m4b 仅含 `----:nsA:series`（值「三体」）
- **THEN** 结构化 `series` 列取「三体」（小写镜像命中）

#### Scenario: 同命名空间大小写双键并存

- **WHEN** 同一命名空间同时含 `----:nsA:SERIES` 与 `----:nsA:series`
- **THEN** 视为同一字段，取值一致（优先大写键，双键值不同时以 `----:nsA:SERIES` 为准）

### Requirement: 系列序号解析优先级（SERIES-PART 首选）

App MUST 在 MP4 链路解析系列序号时按以下优先级取值：

1. **首选**：自由格式序号键（name=`SERIES-PART` 或 `series-part`，命名空间内大小写不敏感归一）；
2. **降级**：键 `©sno`；
3. 均缺失 → 系列序号为空。

#### Scenario: SERIES-PART 首选

- **WHEN** m4b 同时含 `----:nsA:SERIES-PART`（值「2」）与 `©sno`（值「1」）
- **THEN** 结构化 `seriesNumber` 列取「2」，组内按「2」参与自然排序

#### Scenario: 小写镜像

- **WHEN** m4b 仅含 `----:nsA:series-part`（值「Vol 2」）
- **THEN** 结构化 `seriesNumber` 列取「Vol 2」（原样入库，不提取数字）

### Requirement: 系列名与系列序号同源配对（多命名空间）

App MUST 保证系列名与系列序号来自同一命名空间，禁止跨命名空间拼凑：

1. 同一命名空间同时提供系列名与序号 → 两者同取该命名空间；
2. 该命名空间只提供其一 → 另一字段回退字段级标准降级链（序号→`©sno`、名→`©ser`）；
3. 若自由格式缺失 → 全走标准降级链（`©ser`/`©sno`）。

#### Scenario: 同命名空间成对

- **WHEN** `nsA` 同时含 `----:nsA:SERIES`（「三体」）与 `----:nsA:SERIES-PART`（「2」）
- **THEN** `series`=「三体」、`seriesNumber`=「2」，同取 `nsA`

#### Scenario: 跨命名空间禁止

- **WHEN** `nsA` 含 `----:nsA:SERIES`（「三体」）、`nsB` 含 `----:nsB:SERIES-PART`（「2」）、且存在 `©sno`（「1」）
- **THEN** `series`=「三体」（取 `nsA`）；`seriesNumber` **不取** `nsB`，回退 `©sno`=「1」

#### Scenario: 跨命名空间禁止且无标准序号

- **WHEN** `nsA` 含 `----:nsA:SERIES`（「三体」）、`nsB` 含 `----:nsB:SERIES-PART`（「2」）、无 `©sno`
- **THEN** `series`=「三体」；`seriesNumber`=空（不跨命名空间拼凑）

#### Scenario: 自由格式仅序号

- **WHEN** `nsA` 仅含 `----:nsA:SERIES-PART`（「2」）、存在 `©ser`（「三体」）
- **THEN** `seriesNumber`=「2」（取 `nsA` 自由格式）、`series`=「三体」（字段级回退 `©ser`）

#### Scenario: 自由格式仅名

- **WHEN** `nsA` 仅含 `----:nsA:SERIES`（「三体」）、存在 `©sno`（「2」）
- **THEN** `series`=「三体」（取 `nsA`）、`seriesNumber`=「2」（字段级回退 `©sno`）

### Requirement: mp3（ID3）链路不变

App MUST 保持 mp3/flac/ogg 的 ID3 系列解析不变：系列名 `TXXX:SERIES` → 次选 `TIT1`；系列序号 `TXXX:SERIESNUMBER`。

#### Scenario: mp3 行为回归

- **WHEN** mp3 含 `TXXX:SERIES` 与 `TIT1`
- **THEN** 系列名取 `TXXX:SERIES`；仅有 `TIT1` 时取 `TIT1`

### Requirement: 存量重扫生效

系列解析优先级与键格式调整 MUST 对存量 m4a/m4b 文件重扫生效，不做数据库迁移回填（extras 随扫描整体替换）。

#### Scenario: 重扫生效

- **WHEN** 用户对含 `----:nsA:SERIES` 的存量 m4b 目录执行重扫
- **THEN** 该作品 `series` 列更新为自由格式值，主页系列分组随之生效；extras 键更新为 `----:nsA:SERIES`

### Requirement: 原始键值保留不受影响

自由格式键（含命名空间与大小写镜像）MUST 继续全量保留在 `entry_tag_extras`，不因结构化映射变化而裁剪。

#### Scenario: extras 全量保留

- **WHEN** m4b 含 `----:nsA:SERIES` 与 `----:nsA:series`
- **THEN** `entry_tag_extras` 两条均保留（key=`----:nsA:SERIES`、`----:nsA:series`，source=`MP4`）
