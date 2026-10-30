---
name: audiobook-m4b-builder
description: 把按集拆分的音频（m4a/mp3/aac）无损合成为按卷/部组织的 m4b 有声书：stream-copy 合并、每集一个 chapter、内嵌单封面、MP4 三段式标签（系列/部/集）、文件名零填充排序，并规避中文数字排序、ffmpeg 丢弃 Apple 扩展、mutagen 章节读取上限等陷阱。当用户要求「把一堆 m4a 合成 m4b」「有声书按卷拆分」「给 m4b 加章节/封面/系列标签」「修正 m4b 标签或文件名排序」时使用。
agent_created: true
---

# 有声书 m4b 合成

把一个「每集一个文件」的有声书，无损合成为「每卷一个 m4b」的有声书：卷内每集一个 chapter、单封面、按系列/部/集三段式打标签，并修正文件名排序。

## 适用场景

- 用户有一堆 `第001集 xxx.m4a`，想合并成按卷/部拆分的 `.m4b`
- 需要给 m4b 加**章节（chapter）**、内嵌**封面**、**系列/部/集**标签
- 修正 m4b 的**标签语义**（作者/演播拆分、系列名、部号、媒体类型）
- 修正 m4b **文件名排序**（中文数字排序错乱）
- 把预告/番外从正片**独立**出来

## 环境准备（Windows）

```bash
# ffmpeg：免安装，直接用 imageio-ffmpeg 自带二进制
python -m pip install imageio-ffmpeg
python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"

# 读写 MP4 标签：mutagen
python -m pip install mutagen
```

约定：所有 Python 脚本用同一个 venv 运行（Windows 是 `Scripts/python.exe`，不是 `bin/`）。pip 用官方源 `-i https://pypi.org/simple`。

ffprobe 不是必需的：imageio-ffmpeg 只带 `ffmpeg`。装了 ffprobe 时校验走 ffprobe，没装则自动退回 `ffmpeg -f ffmetadata`。

## 总体流程

1. **审计**：确认音频完整、集号连续、tag 覆盖情况
2. **定边界**：确定每卷 = 哪些集（**按文件名对比原著判定，禁止用集数线性推算**）
3. **构建**：逐卷 ffmpeg 无损耗合并 + 章节 + 封面
4. **打标签**：写入系列/部/集三段式标签
5. **校验**：ffprobe 核对章节数与时长
6. **文件名零填充**：中文数字 → `第01卷` 之类，保证排序
7. （可选）预告/番外独立成册

各步可直接调用 `scripts/` 下的脚本。

## 步骤 1：审计

逐文件用 `mutagen.mp4.MP4` 打开，统计：能否解析、时长、核心 tag（©nam/©art/©alb）是否齐全、集号是否连续。集号连续性用 `第(\d+)集` 从文件名抽取后排序检查缺口/重复。

## 步骤 2：定卷/部边界（关键）

**绝不要用「总章数 / 卷数 × 集号」线性推算边界**——有声书集号与原著章号通常不是线性对应（本作实测：ep69=第127章、ep100=第171章、ep245=第364章、ep1041 才到「灵界」，比例一路在变）。

正确做法：**用每集文件名里的章节名，对比原著的卷/部结构，找出每一卷起始那一集**。步骤：
1. 导出全部「集号 → 集名」清单（`scripts/build_m4b.py` 里同样的 `第(\d+)集` 抽取）
2. 确定原著的卷名与各卷的「开卷章节名 / 标志性情节名」
3. 在集名清单里 grep 这些标志名，定位到具体集号作为边界
4. 相邻卷的边界必须首尾相接、无缺口无重叠（校验 Σ=总集数）

例：`ep54《离开》→ ep55《钱财惹祸》` 为卷一/卷二界；`ep100《返回》→ ep101《聚集》` 为卷二/卷三界；`ep245《孤岛、巨舟》` 起卷四；`ep1041《何为灵界》` 起灵界；`ep2089《真仙之身（上）》` 起仙界卷。

> 若原著卷名/边界本身有争议，把候选边界和依据（命中集号+集名）列给用户确认，不要擅自拍板。

## 步骤 3：构建 m4b（核心）

对每一卷，按集号顺序用 ffmpeg **concat demuxer 流拷贝**（无损、不重编码）合成，并在同一次命令里挂章节与封面：

```bash
ffmpeg -y -f concat -safe 0 -i list.txt -i meta.txt -i cover.jpeg \
  -map 0:a -map 2 -disposition:v:0 attached_pic \
  -map_chapters 1 -map_metadata 1 \
  -c copy -movflags +faststart "输出 第NN卷 卷名.m4b"
```

- `list.txt`：每行 `file '<m4a 相对路径>'`
- `meta.txt`：FFMETADATA 章节（每集一章，START/END 毫秒，title=集名）
- **必须 `-map 0:a` 只取音轨**：m4a 里常带 `mjpeg` 封面流和一个 `bin_data(text)` 数据流，ipod/mp4 封装器会报 `Could not find tag for codec mjpeg` / `Tag text incompatible`。只 map 音轨、封面另挂一路即可。
- 单文件 m4b 只能挂**一个**封面（多封面也会触发 mjpeg 报错）。

完整可改参数的实现见 `scripts/build_m4b.py`（顶部 CONFIG；含集号抽取、章节时间轴、封面、初始标签）。

## 步骤 4：打三段式标签（系列 / 部 / 集）

m4b 的正确「合集」不是把多卷并成一个专辑，而是**三层**（详见 `references/m4b-tag-model.md`）：

| 层 | 字段 | 含义 | 本作取值 |
|---|---|---|---|
| 系列层 | `----:com.apple.iTunes:SERIES` | 系列名（**正解**，ABS/Plex/Apple Books 识别） | 凡人修仙传 |
| 系列层 | `----:com.apple.iTunes:SERIES-PART` | 系列序号/部号 | 1…11（预告 0） |
| 系列层 | `----:com.apple.iTunes:series` / `series-part` | 小写镜像（ABS/Plex 兼容） | 同上 |
| 系列层 | `----:com.booklistener:SERIES` / `SERIES-PART` | BookListener 命名空间 | 同上 |
| 系列层 | `©ser` / `©sno` | 非标准冗余（Apple 老生态） | 同上 |
| 部层 | `©alb` | 专辑 = 部名 | 各卷名 |
| 部层 | `disk` | 碟号 = 部号 | (N, 11) |
| 集层 | `©nam` | 标题 = 集名/卷名 | 各卷名 |
| 集层 | `trkn` | 曲目号 | **单文件 m4b 不写**（见下） |
| 作品层 | `aART` | 专辑艺术家 = 作者 | 忘语 |
| 作品层 | `©ART` | 表演者 = 演播 | 光合积木 |
| 作品层 | `©gen` | 流派 | 有声书 |
| 作品层 | `stik` | 媒体类型（2=有声书） | 2 |

要点：
- **系列层必须用 mutagen 写 freeform `----`**：mutagen key 形如 `"----:com.apple.iTunes:SERIES"`，值必须 `MP4FreeForm(系列名.encode("utf-8"))`——实测直接赋 str 会在 `save()` 时报 `TypeError: can't concat str to bytes`（mutagen 1.48.1）。ffmpeg 实测丢弃全部 freeform 与 `©ser`/`©sno`，构建后必须补写。
- **`©ser`/`©sno` 是非标准 atom**（ABS/Plex/Media3 均不识别），本模型仅作 Apple 老生态冗余写入；系列语义以 freeform 为准。
- `aART` 放**作者**、`©ART` 放**演播**（有声书语义）；若用户另有偏好以其为准。
- **单文件 m4b 不要写 `trkn`**：foobar2000 等播放器对多章节文件，会用文件级 `trkn` 覆盖每章默认序号——写了 `(1,1)` 会让所有章节的「音轨号」都显示成 1。不写时播放器按章节序号 1..N 显示。卷号放 `disk/SERIES-PART/©sno`，不要塞进 trkn；要逐集编号用 `.cue` 或拆成每集文件。

用 `scripts/tag_m4b.py` 批量写入（顶部 CONFIG 指定目录、系列名、命名空间、作者、演播、部号映射）。

## 步骤 5：校验

- **章节数用外部工具数，不要用 `len(mp4.chapters)`**：
  - 装了 ffprobe：`ffprobe -v error -show_chapters -of csv "x.m4b" | tail -n +2 | wc -l`
  - **没装 ffprobe**（imageio-ffmpeg 只带 ffmpeg）：`ffmpeg -v error -i "x.m4b" -f ffmetadata - | grep -c '\[CHAPTER\]'`
- ⚠️ **mutagen 对 >255 章的 m4b 只读到 255**（读取上限，非文件缺陷）。实测 408/304/523 章的三个文件一律被报成 255，ffprobe 计数正确。以外部工具为准。
- 另外注意 mutagen 1.48 的 `MP4.chapters` 元素是 `Chapter` 对象、不是可排序的 key，取前几章要用 `list(rec.chapters)[:3]`。
- 时长、`stik`（应=2）、封面尺寸也从 mutagen/ffprobe 抽查。
- `scripts/verify_m4b.py` 已按此实现：章节数自动选 ffprobe → ffmpeg(ffmetadata) 兜底，标签/时长/封面用 mutagen，系列层读 `----:com.apple.iTunes:SERIES`/`SERIES-PART`（©ser/©sno 兜底），并检查 6 个 freeform 齐全、`disk[0]` 与系列序号一致。

```bash
python verify_m4b.py "E:/.../m4b" --expect 54,46,144   # 按文件名顺序比对应有章节数
```

## 步骤 6：文件名零填充排序

中文数字（卷一/卷二/…/卷十/卷十一）在字符串排序里按 **Unicode 码点**排，顺序会乱（实测：卷一、卷七、卷三、卷九、卷二、卷五、卷八、卷六、卷十、卷十一、卷四）。资源管理器「自然排序」也救不了中文数字。

修法：文件名改成**阿拉伯补零** `第00卷 / 第01卷 … 第11卷`，字符串排序即数值排序。`scripts/rename_zeropad.py` 含中文数字→整数转换（处理 十 / 十一 / 二十一 等）与安全重命名（先检测冲突再执行）。

## 步骤 7：预告/番外独立（可选）

若某一集（如「第000集 预告」）不属于正片：从该卷构建时排除它（卷起点后移），单独用同一套命令合成一个独立 m4b。独立册仍写系列名，但序号按用户决定：`SERIES-PART`/`©sno="0"` + `disk=(0,11)` 排进系列，或完全不写系列层（8 个 atom 全删）。

## 常见坑速查

| 现象 | 原因 | 处理 |
|---|---|---|
| ffmpeg `Could not find tag for codec mjpeg` | 源文件带封面流被 concat | `-map 0:a` 只取音轨，封面另挂 |
| ffmpeg `Tag text incompatible with output codec id` | 源带 `bin_data(text)` 数据流 | 同上，只 map 音轨 |
| mutagen 写 `----` 报 `TypeError: can't concat str to bytes` | freeform 值必须 `MP4FreeForm(bytes)` | `MP4FreeForm(v.encode("utf-8"))`，别直接赋 str |
| 构建后系列 atom 全丢了 | ffmpeg 实测丢弃全部 freeform `----` 与 `©ser`/`©sno` | 构建后 mutagen 补写（tag_m4b.py） |
| mutagen 只读到 255 章 | mutagen 读取上限 | 用 ffprobe / `ffmpeg -f ffmetadata -` 数章节 |
| `sorted(mp4.chapters)` 报 TypeError | mutagen 1.48 元素是 `Chapter` 对象 | 用 `list(rec.chapters)[:3]` |
| 文件名排序乱 | 中文数字按码点排 | 改阿拉伯补零 |
| 读取标签全是 None | heredoc 里 `'\\xa9alb'` 被当字面反斜杠 | Python 源码里用单反斜杠 `'\xa9alb'` |
| 安卓/Media3 读不到系列 | Media3 跳过 `©ser`/`©sno`/`stik`；freeform 走 `InternalFrame(domain/name/value)` | 已写 `----:com.booklistener:SERIES` 等命名空间 |
| m4b 里「音轨 1」≠ 预告 | 整卷单文件=1 条音轨；集=章节 | 正常，集号在章节标题里 |

## 脚本

四个脚本都是「改顶部 CONFIG → 运行」。`tag_m4b.py` 与 `rename_zeropad.py` 默认 **dry-run**，加 `--apply` 才落盘；`build_m4b.py` 是构建步骤，直接产出文件。

```bash
# 1) 构建：改 CONFIG 里的 media_dir / out_dir / cover / volumes
python scripts/build_m4b.py

# 2) 打标签：先 dry-run 看差异，确认后 --apply
python scripts/tag_m4b.py
python scripts/tag_m4b.py --apply

# 3) 校验
python scripts/verify_m4b.py "E:/.../m4b"
python scripts/verify_m4b.py "E:/.../m4b" --expect 54,46,144

# 4) 文件名补零排序
python scripts/rename_zeropad.py
python scripts/rename_zeropad.py --apply
```

- `build_m4b.py` — 按卷无损合并 + 章节 + 封面 + 初始标签（系列层 8 atom）
- `tag_m4b.py` — 批量写系列/部/集标签（6 freeform + ©ser/©sno 冗余；自动识别预告/番外等独立册）
- `verify_m4b.py` — 章节数（ffprobe→ffmpeg 兜底）、时长、stik、封面尺寸、6 freeform 齐全性、disk 与系列序号一致性
- `rename_zeropad.py` — 中文数字卷名 → 补零阿拉伯，冲突检测 + 两段式重命名

## 参考

- `references/m4b-tag-model.md` — MP4 atom 三段式标签模型（freeform `----` 两个命名空间 + ©ser/©sno 冗余）、freeform 的 mutagen 写法与实测坑、与 ID3v2 的字段对照、ffmpeg/mutagen 分工的实测结论。
