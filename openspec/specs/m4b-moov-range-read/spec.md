# m4b-moov-range-read Specification

## Purpose
本地扫描 MP4 容器（m4a/mp4/alac/m4b）时只读取 moov 区间字节（标签/章节/时长均在 moov），替换整文件读入内存，消除 1GB 级 m4b 的 OOM 风险；解析器（Media3 MetadataExtractor、Mp4IlstReader、Mp4ChapterReader）输入输出语义不变。

## Requirements

### Requirement: MP4 容器扫描只读 moov 区间

App MUST 对 `ILST_EXTS`（m4a/mp4/alac/m4b）本地扫描改为读取「moov 所在区间」的字节而非整文件：头部路径读文件头前 1MB 定位 moov（ftyp→moov→mdat 形态）；头部无 moov 时读文件尾 16MB 反向定位（ftyp→mdat→moov 形态）；返回包含 moov 及其前导（ftyp/free/wide）的连续区间字节。

#### Scenario: moov 在文件头部

- **WHEN** 本地存在 faststart 形态的 1GB m4b（ftyp→moov→mdat）
- **THEN** 扫描仅读取文件头约 moov 结束为止的字节（几十 KB~几 MB），不触碰 mdat 音频数据，无 OOM

#### Scenario: moov 在文件尾部

- **WHEN** 本地存在非 faststart 形态的 1GB m4b（ftyp→mdat→moov）
- **THEN** 扫描读取文件末尾 16MB 区间并定位 moov，标签/章节/时长解析不受影响

#### Scenario: 文件头带非 box 前导

- **WHEN** m4b 文件头存在 ID3/私有头等非 box 前导数据（v2.2.4 已支持的容错场景）
- **THEN** 区间定位使用已知 box 类型白名单遍历，仍能正确定位 moov，解析输出与整读一致

### Requirement: 定位失败回退整读

App MUST 在 moov 区间定位失败（头部/尾部均未命中、文件过小、读取异常）时回退为整文件读取，保证识别能力不因定位器缺陷而退化。

#### Scenario: 定位失败保底

- **WHEN** moov 定位器对某 MP4 文件无法定位（如极端布局或异常文件）
- **THEN** 扫描回退整读该文件，行为与 v2.2.4 完全一致（标签/章节仍可解析）

### Requirement: 解析输出与整读一致

App MUST 保证区间字节输入下 `MetadataExtractor.extract`（时长/标准帧）、`Mp4IlstReader.read`（标签）、`Mp4ChapterReader.read`（章节）的输出与整文件输入一致，解析器代码零改动。

#### Scenario: 区间字节解析等价

- **WHEN** 对同一 m4b，分别以整文件字节与区间字节调用三个解析器
- **THEN** 标签条目、章节列表、时长毫秒逐项相等
