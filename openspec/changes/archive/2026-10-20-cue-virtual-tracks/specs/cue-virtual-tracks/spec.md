# cue-virtual-tracks Specification

## Purpose

CUE 整轨虚拟分轨能力：解析无损整轨的 .cue 索引，将整轨文件在专辑/曲目层呈现为多条可独立选曲、播放与记忆进度的虚拟轨；不切割文件、零额外存储、位完美保真。

## ADDED Requirements

### Requirement: CUE 解析

App MUST 能够解析 .cue 文本为虚拟轨列表（轨号、标题、表演者、起始/结束毫秒）。解析 MUST 支持多 FILE 段（轨归属当前 FILE 上下文）、INDEX 00 与 INDEX 01、MM:SS:FF 时间（1 秒 = 75 帧）；PREGAP/POSTGAP MUST 被忽略；无有效 INDEX 的轨 MUST 被跳过；末轨结束时间 MUST 视为文件尾（无结束毫秒）。

#### Scenario: 标准专辑解析

- **WHEN** 解析含全局 PERFORMER/TITLE 与 3 条 TRACK（INDEX 01 递增）的 .cue
- **THEN** 得到 3 条虚拟轨：第 1 轨起始 0ms，第 2/3 轨起始为前轨结束，末轨无结束毫秒；轨级 PERFORMER 覆盖全局

#### Scenario: 多 FILE 段归属

- **WHEN** 解析引用 CD1/CD2 两个音频文件的 .cue
- **THEN** 每条虚拟轨归属其声明所在 FILE 段对应的音频文件，不串挂到其他文件

#### Scenario: 畸形容错

- **WHEN** 解析含无 INDEX 轨或 PREGAP 的 .cue
- **THEN** 无 INDEX 轨被跳过不产出；PREGAP 不影响起始时间

### Requirement: 整轨吸收与退化

当 .cue 存在且成功定位到其引用的音频文件（FILE 声明优先，同名扩展名探测兜底）时，该整轨文件在专辑/曲目层 MUST 被**吸收**——不单独显示为媒体条目，仅展示其虚拟轨。定位失败或解析失败时，整轨文件 MUST 退化为普通媒体条目入库。

#### Scenario: 吸收成功

- **WHEN** 目录含 album.cue 与 album.flac，扫描成功解析
- **THEN** 专辑/曲目层显示 N 条虚拟轨，整轨 album.flac 不单独出现

#### Scenario: 解析失败退化

- **WHEN** .cue 无 FILE 命中或解析出 0 轨
- **THEN** 整轨文件按普通媒体条目入库，行为与无 cue 一致

### Requirement: 虚拟轨入库与关联

虚拟轨 MUST 作为普通媒体条目入库（媒体数据一张表）：uri 为 `<整轨uri>#track_<起始ms>`（唯一），album/artist 取自 cue（全局值，轨级覆盖），归属作品按既有作品键规则。cue 文件本身 MUST NOT 作为媒体条目；App MUST 维护 cue 文件 → (条目, 整轨 uri, 作品) 的关联记录，供内容文件列表展示与虚拟轨查询。

#### Scenario: 入库与聚合

- **WHEN** 扫描含 cue 的条目后查看专辑/曲目
- **THEN** 虚拟轨作为普通行展示（标题=轨名、专辑=cue 专辑），归属该 cue 专辑作品

#### Scenario: 二次扫描稳定

- **WHEN** 同一内容二次扫描
- **THEN** 虚拟轨 uri 与作品归属保持稳定，已存进度不因扫描丢失

### Requirement: 虚拟轨播放与进度

虚拟轨 MUST 通过裁剪窗口播放（只播放在整轨上的可播区间），媒体标识为虚拟 uri。虚拟轨进度 MUST 按 (作品, 虚拟 uri) 内容引用记录与恢复；cue 被编辑导致起始毫秒变化时，MUST 按位置近似恢复并如实标注（可能定位不准）。

#### Scenario: 播放与断点恢复

- **WHEN** 播放某虚拟轨后杀后台再进入
- **THEN** 按 (作品, 虚拟 uri) 精确定位恢复到该虚拟轨与位置

#### Scenario: cue 编辑后近似恢复

- **WHEN** .cue 插入/删除轨导致后续轨起始毫秒全部变化后重扫
- **THEN** 原进度按顺序位置近似恢复并提示"可能定位不准"，不崩溃、不丢记录

### Requirement: 内容文件列表与 cue 入口

「内容文件」列表（内容管理 → 条目）MUST 忠实显示该条目所有文件（含 .cue 与整轨），常规文件行 MUST NOT 有点击事件；.cue 行 MUST 提供特殊点击：打开该 cue 的**虚拟轨列表**（纯浏览），点击 MUST NOT 触发任何播放或入队行为。

#### Scenario: cue 行点击浏览

- **WHEN** 在内容文件列表点击 .cue 行
- **THEN** 打开虚拟轨列表展示该 cue 的曲目，不启动播放、不加入播放队列

#### Scenario: 常规行无点击

- **WHEN** 在内容文件列表点击非 .cue 行（音频/整轨/其他文件）
- **THEN** 无任何点击响应（不含删除/刷新等管理操作）

### Requirement: 列表命名统一

界面列表名称 MUST 遵循定稿：主页专辑聚合称「专辑」，专辑内曲目称「曲目」，文件夹管理页称「内容管理」，其内文件列表称「内容文件」，cue 分轨列表称「虚拟轨」。命名 MUST 全局一致（界面文案、术语表、i18n 资源），不得引入重复或歧义称呼。

#### Scenario: 命名一致

- **WHEN** 检查各列表标题与 i18n 资源
- **THEN** 五处名称分别命中「专辑 / 曲目 / 内容管理 / 内容文件 / 虚拟轨」，无其他等价称呼并存
