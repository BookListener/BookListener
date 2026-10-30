# m4b 三段式标签模型

本文说明 MP4/M4A/M4B 容器的标签 atom、有声书「合集」的正确定义、以及与 ID3v2 的字段对照。结论来自实测（mutagen 1.48.1 + ffmpeg，Windows；2026-10-30 复核系列层写法）。

## 1. 容器事实

- `m4b` 与 `m4a` 是**同一个容器**（ISO BMFF / MP4），差别只在扩展名。iTunes/Apple Books 约定用 `.m4b` 表示「有声书」，靠 `stik=2` 识别。
- 有声书相对普通音频的**唯一实质增量是 `chpl` atom（章节）**。没有 `chpl` 的 m4b 在播放器里就是一整条音轨，不能跳集。
- 因此「每集一章」是把 m4a 集群升级成有声书的核心动作，比标签更关键。

## 2. 三层结构（核心）

有声书的「合集/系列」**不是**把所有卷并进同一个 `©alb`。`©alb` 是**部（卷）自己的名字**。真正的系列关系由三层字段表达：

```
作品层（整部作品共享）
  aART  专辑艺术家  = 作者（文学作品的创作者）
  ©ART  表演者      = 演播者（朗读者）
  ©gen  流派        = 有声书
  stik  媒体类型    = 2（有声书）

系列层（跨卷共享，标明「属于哪个系列、第几部」）—— 正解是 freeform ---- 原子
  ----:com.apple.iTunes:SERIES       系列名    = 凡人修仙传
  ----:com.apple.iTunes:SERIES-PART  系列序号  = 1 / 2 / … / 11
  ----:com.apple.iTunes:series       系列名（小写镜像，兼容 ABS/Plex/Apple Books）
  ----:com.apple.iTunes:series-part  系列序号（小写镜像）
  ----:com.booklistener:SERIES       系列名（BookListener 命名空间）
  ----:com.booklistener:SERIES-PART  系列序号（BookListener 命名空间）
  ©ser / ©sno                        冗余备份（非标准，见下）

部层（每卷独立）
  ©alb  专辑        = 该卷卷名
  disk  碟号        = (部号, 总部数)  → (3, 11)

集层（每个音轨/每个文件）
  ©nam  标题        = 卷名（整卷单文件时）
  trkn  曲目号      = 【单文件 m4b 不写】见下「trkn 陷阱」
```

系列层的两个命名空间、大小写两种键，共 6 个 freeform atom 全部写入——atom 成本可忽略（实测 6 个合计约 1.5KB）。各消费方的识别情况：

| 消费方 | 读什么 |
|---|---|
| ABS / Plex / Apple Books | `----:com.apple.iTunes:SERIES` / `SERIES-PART`（含小写镜像） |
| BookListener（安卓有声书播放器） | `----:com.booklistener:SERIES` / `SERIES-PART` |
| Apple 老生态（部分阅读器） | `©ser` / `©sno` |
| Media3 / ExoPlayer | **读不到 `©ser`/`©sno`/`stik`**（源码 else 分支直接跳过）；freeform `----` 以 `InternalFrame(domain/name/value)` 暴露 |
| ffmpeg | **不写也不认** freeform `----` 与 `©ser`/`©sno`（实测拷贝即丢） |

`©ser`/`©sno` 是**非标准** atom（不在 Apple 公开 atom 清单内），ABS/Plex/Media3 均不识别；本模型保留写入仅作 Apple 老生态冗余，**系列语义的正解是上面的 freeform `----`**。

关键判据：

- 如果 `©alb` 被所有卷共用，播放器会把 11 个文件显示为「同一张专辑的 11 张碟」，而**丢掉每卷的名字**——这正是用户会立刻指出的错误（"album 不是每本的名字吗"）。
- 卷号放在 `disk`、`SERIES-PART` 与 `©sno`，**不要塞进 trkn**。

### trkn 陷阱（重要）

**单文件 m4b 不要写 `trkn`。** foobar2000 等播放器把多章节 m4b 的每一章当作子曲目(subsong)，默认按章节序号 1..N 显示「音轨号」；可一旦文件带了文件级 `trkn`（如 `(1,1)`），播放器会用它覆盖**每一章**的序号，导致所有章节都显示成 1。实测：写 `trkn=(1,1)` 后 foobar2000 每章音轨号全是 `1.01`；删掉 `trkn` 即恢复 1..N。

- 需要「每集一个独立编号」且要跨播放器稳定：用同名 `.cue` 分轨表，或拆成每集一个文件（各自 `trkn=集号`）。
- 只在 Apple Books/iTunes 这类按专辑分组、不展开章节的生态里，`trkn=(1,1)` 才「看起来对」；但即便那里不写 `trkn` 也没坏处。
- `SERIES-PART`、`©sno` 与 `disk` 都表示部号，是**冗余**的：Apple 生态/ABS/Plex 读 freeform 或 `©sno`，通用播放器读 `disk`。都写最稳。

## 3. Atom 名与 Python 写法

MP4 里 `©` 实际是字节 `0xA9`。mutagen 的 key 必须写成 `"\xa9alb"`（Python 字符串转义）；写成 `"\\xa9alb"` 会变成字面反斜杠 + 字母，读不到东西——这是排障时最常见的假阴性。

| 语义 | mutagen key | 写法 |
|---|---|---|
| 标题 | `©nam` | `"\xa9nam"` |
| 专辑 | `©alb` | `"\xa9alb"` |
| 艺术家（表演者） | `©ART` | `"\xa9ART"` |
| 专辑艺术家 | `aART` | `"aART"` |
| 作者 | `©aut` | `"\xa9aut"` |
| 流派 | `©gen` | `"\xa9gen"` |
| 系列名（冗余） | `©ser` | `"\xa9ser"` |
| 系列序号（冗余） | `©sno` | `"\xa9sno"` |
| 系列名（正解） | `----` freeform | `"----:com.apple.iTunes:SERIES"`，值 `[MP4FreeForm("x".encode())]` |
| 系列序号（正解） | `----` freeform | `"----:com.apple.iTunes:SERIES-PART"`，同上 |
| 碟号 | `disk` | `"disk"`，值 `[(n, total)]` |
| 曲目号 | `trkn` | `"trkn"`，值 `[(n, total)]` |
| 媒体类型 | `stik` | `"stik"`，值 `[2]`（int） |
| 备注 | `©cmt` | `"\xa9cmt"` |
| 发行年 | `©day` | `"\xa9day"` |

### freeform `----` 的写法（实测坑）

mutagen 的 freeform key 格式是 `----:mean:name`（三段冒号连接），mean 即命名空间：

```python
from mutagen.mp4 import MP4, MP4FreeForm

m = MP4(path)
m["----:com.apple.iTunes:SERIES"] = [MP4FreeForm("凡人修仙传".encode("utf-8"))]
m["----:com.apple.iTunes:SERIES-PART"] = [MP4FreeForm("3".encode("utf-8"))]
m.save()
```

⚠️ **值必须是 `MP4FreeForm(bytes)`，不能直接赋 str**：mutagen 1.48.1 实测直接赋 `["凡人修仙传"]` 报 `TypeError: can't concat str to bytes`，且不报在赋值行、报在 `save()`，极易误判。读回时值是 `MP4FreeForm`（bytes 子类），展示前要 `.decode("utf-8")`。

⚠️ **大写与小写是两个不同的 key**：`----:com.apple.iTunes:SERIES` 与 `----:com.apple.iTunes:series` 互不覆盖，必须分别赋值。

## 4. 与 ID3v2 的对照

| 语义 | MP4 atom | ID3v2.4 frame |
|---|---|---|
| 标题 | `©nam` | TIT2 |
| 专辑 | `©alb` | TALB |
| 表演者 | `©ART` | TPE1 |
| 专辑艺术家 | `aART` | TPE2 |
| 作者 | `©aut` | TCOM / TPE1 |
| 系列名 | `----:…:SERIES`（或 `©ser` 冗余） | TXXX:Series / TSOA(部分) |
| 系列序号 | `----:…:SERIES-PART`（或 `©sno` 冗余） | TXXX:Series-part / TXXX:SERIESNUMBER |
| 碟号 | `disk` | TPOS |
| 曲目号 | `trkn` | TRCK |
| 媒体类型 | `stik` | TXXX:stik / TMED |

注意：ID3v2 里 `TPE2` 就是「专辑艺术家」，语义和 `aART` 对齐；而 `TPE1` 在有声书里通常是演播者。跨格式迁移时不要机械地把「艺术家」映射成作者。

## 5. ffmpeg 与 mutagen 的分工

| 字段 | ffmpeg 能写 | mutagen 能写 | 结论 |
|---|---|---|---|
| `©nam` `©alb` `©ART` `aART` `©gen` | ✅ | ✅ | 均可 |
| `disk` `trkn` | ✅（`-metadata` 语义受限） | ✅ | 用 mutagen 更可控 |
| `chpl`（章节） | ✅（`-map_chapters`） | ✅（≤255 章） | 构建时用 ffmpeg |
| freeform `----`、`©ser`、`©sno` | ❌ **实测拷贝即丢** | ✅ | **必须 mutagen 补写** |
| `stik` | 各版本行为不一（2016 版拷贝幸存，新版构建路径不保证） | ✅ | 一律 mutagen 写最稳 |
| 封面 `covr` | ✅（`attached_pic`） | ✅ | 构建时用 ffmpeg |

**铁律：ffmpeg 只负责音频+章节+封面；系列层 8 atom、`stik`、`disk` 一律 ffmpeg 构建完成后用 mutagen 打开写一遍。** 实测（ffmpeg 2016 完整版，`-c copy`）：6 个 freeform 与 `©ser`/`©sno` 全部被丢弃，`stik`/`disk`/`©alb`/`aART` 幸存——幸存字段各版本行为不一致，不要依赖。

## 6. 读取上限（重要）

- **mutagen 的 `MP4.chapters` 最多只读到 255 章**，超过部分静默截断。这不是文件损坏。
- 校验章节数必须用 ffprobe：

```bash
ffprobe -v error -show_chapters -of csv "x.m4b" | tail -n +2 | wc -l
# 或
ffprobe -i "x.m4b" 2>&1 | grep -c "Chapter #"
```

- 实测：408 / 304 / 523 章的三个文件被 mutagen 一律报 255，ffprobe 计数正确。

## 7. Windows 资源管理器的表现

- 资源管理器把 m4b 当**普通音频**（走 WMP 的属性处理器），只显示四列：标题、参与创作的艺术家、唱片集、`#`（曲目号）。
- `#` 列 = `trkn`。按新建议**不写 `trkn`**，该列为空（资源管理器本就不展开章节，空着无妨）；若确需在该列显示 `1`，单独写 `(1,1)`，但 foobar 等章节播放器会把每章都显示成 1（见「trkn 陷阱」）。
- 资源管理器**不显示** freeform `----`/`©ser`/`©sno`/`disk`/`stik`，也不因此说明这些字段缺失——要用 mutagen/ffprobe 或 mp3tag 确认。
- 想看到系列分组，得用 Apple Books / iTunes / ABS / MusicBee 这类读 freeform `----` 的软件。

## 8. 命名与排序

中文数字（一/二/…/十/十一）按 Unicode 码点排序，结果是乱序（实测：一、七、三、九、二、五、八、六、十、十一、四）。Windows 的「自然排序」只处理阿拉伯数字，救不了中文数字。

修法：文件名用补零阿拉伯数字 `第00卷 / 第01卷 / … / 第11卷`。预告排在正片前用 `第00卷`。转换时注意「十/十一/二十一」这类复合数词，"十"→10、"十一"→11、"二十一"→21，不能逐字映射。

## 9. 预告/番外

- 预告不属于任何正片卷。若它原先落在卷一里，卷一的起止要后移一集，预告单独构建成一个 m4b。
- 独立册的系列归属由用户决定：
  - 要它显示为「系列第 0 部」：6 个 freeform + `©ser` + `©sno="0"` + `disk=(0, 11)`
  - 完全脱离系列：只写作品层字段，不写任何系列层 atom
- 两种都合理，问一句再动手，别自己拍。
