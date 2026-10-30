# 标签解析字段对照表（TAG-FIELDS）

> 与 README「专辑化曲目 / 系列分组」配套的解析字段定义。
> 数据落库：`entry_index` 结构化列 + `entry_tag_extras` 原始键值表；解析器行为见 SPECS §九。
> 本文档全篇不出现任何特定商业品牌及其自有命名（统一以「MP4 标准原子 / 自由格式原子 / 命名空间」表述）。

## 1. 常规标签字段（结构化列）

| 字段 | ID3（mp3/flac/ogg） | MP4 原子（m4a/m4b/mp4/alac） |
|---|---|---|
| 标题 | TIT2 | ©nam |
| 作者 | TPE1 | ©ART |
| 专辑 | TALB | ©alb |
| 专辑作者 | TPE2 | aART |
| 音轨号 | TRCK | trkn |
| 碟号 | TPOS | disk |
| 流派 | TCON | ©gen |
| 年份 | TDRC / TYER | ©day |
| 作曲 | TCOM | ©wrt |
| 媒体类型 | TXXX:MEDIATYPE / TCON | stik（数值→语义见 §3） |
| 编码工具 | TSSE | ©too |

## 2. 系列分组标签（专项）

系列字段约定与有声书生态（ABS/Plex）对齐，以 MP4 自由格式原子承载，按**命名空间**（issuer）区分来源：

- **系列名**：自由格式原子 `----`，name=`SERIES`（解析键 `----:<issuer>:SERIES`）；常附小写镜像 name=`series`（解析键 `----:<issuer>:series`），命名空间内大小写归一
- **系列序号**：自由格式原子 `----`，name=`SERIES-PART`（解析键 `----:<issuer>:SERIES-PART`）；常附小写镜像 name=`series-part`（解析键 `----:<issuer>:series-part`）

issuer 为空时保持旧键 `----:<name>`；空 issuer 视为独立命名空间。

### 同源配对规则（多命名空间）

系列名与系列序号**必须同源**（同一命名空间）：

1. **优先**：同一命名空间同时提供系列名与序号 → 两者同取该命名空间；
2. **字段级回退**：该命名空间只提供其一 → 另一字段回退字段级标准降级链（序号→`©sno`、名→`©ser`）；
3. **禁止**：系列名取命名空间 A、系列序号取命名空间 B 的跨命名空间拼凑。

### 解析优先级（m4a/m4b 同一链路；mp3 不变）

**系列名**

1. **首选**：`----:<issuer>:SERIES` / `----:<issuer>:series`（命名空间内大小写不敏感）
2. **降级**：MP4 标准原子 `©ser`
3. **mp3 不变**：ID3 `TXXX:SERIES`，次选 `TIT1`

**系列序号**

1. **首选**：`----:<issuer>:SERIES-PART` / `----:<issuer>:series-part`（命名空间内大小写不敏感）
2. **降级**：MP4 标准原子 `©sno`
3. **mp3 不变**：ID3 `TXXX:SERIESNUMBER`

### 语义

- 系列名 = 主页分组键（归一化后聚合）；系列序号 = 组内自然排序（数字感知，缺失/非数字排末）。
- 取值原样入库、不规范化（"Part 1" / "Vol 2" / 纯数字均可）。

## 3. 媒体类型

- MP4 `stik` 数值 → 语义：`2`/`6`/`10`=有声书（Audiobook）、`9`/`11`=播客（Podcast）、`1`=音乐（Music）。
- ID3 `TXXX:MEDIATYPE` 原样保留；缺失时回退 `TCON`。

## 4. 原始键值保留（entry_tag_extras）

ID3 全部帧 / `TXXX` 自定义键 / MP4 原子（含 `----` 自由格式与封面原子），按原始键名全量保留，不随结构化字段裁剪；随扫描整体替换，与 `entry_index` 同生命周期。

## 5. 落库方式

- `entry_index` 结构化列：title / artist / album / albumArtist / genre / year / composer / track / disc / series / seriesNumber / mediaType / encoder。
- `entry_tag_extras`：(entryId, uri, key, value, source)，source = `ID3` / `MP4`。
- `works` 聚合列：series / seriesNumber / mediaType（作品层第一曲目值）。
