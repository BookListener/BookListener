---
id: m4b-audiobook-chapters
status: proposed
title: m4b 有声书支持：章节虚拟轨（iTunes 优先 + chpl 兜底）+ ALAC 解码（v2.2.4）
---

## Why

m4b（MPEG-4 有声书容器）是主流有声书分发格式，但当前 `AudioExt.SUPPORTED` 未列入，m4b 文件完全不识别（不扫描、不播放、不显示）。用户要求支持 m4b：识别、标签（系列聚合）、内嵌章节按章播放、删除/进度语义与既有 CUE 虚拟轨一致。

## Context

- 经 grill-me 三轮评审定稿（2026-10-28）：Q1=B 章节虚拟轨、Q2=两种章节原子都实现且 **iTunes 章节表优先**、Q3=A 无章节退化普通音频；ALAC 用 `media3-decoder-alac`；识别仅 `.m4b` 扩展名。
- 环境事实（已核实）：m4b 与 m4a 同为 MP4 容器，`Mp4IlstReader` 已解析 `©nam`/`©ART`/`©alb`/`©ser`/`©sno`/`covr`；`chpl` 目前只记存在性不解析内容；CUE 虚拟轨机制（uri=`<整轨>#track_<startMs>`、整轨吸收、work 级进度按虚拟轨锚点）可整体复用；解码栈 MediaCodec（Media3 1.4.0），FFmpeg 未落代码。
- 决策落档：SPECS §十五（草案）、GLOSSARY（虚拟轨定义扩展 + 章节术语）、ADR-0003。

## What Changes

1. 识别：`AudioExt.SUPPORTED` 增 `"m4b"`；`ILST_EXTS` 增 `"m4b"`（标签/系列/封面现成）。
2. 章节解析器（纯函数，无 Android 依赖）：`Mp4ChapterReader` 解析 **iTunes 章节表优先**（moov 内章节轨 + tref/chap 引用 + 章节名样本），`ChplChapterReader` 解析 chpl 兜底；输出 (startMs, title) 列表。
3. 扫描接入：`ResolvedTags` 增章节字段；ScanCoordinator 对有章节 m4b 生成章节虚拟轨（整本吸收不入 entry_index，归属该书作品）；无章节退化为普通音频文件。
4. ALAC（实施范围调整，经用户拍板）：本版**标记为不支持**——`PlaybackController.onPlayerError` 对 m4b 解码器错误置 `alacUnsupported`，UI 事件消息提示「此 m4b 可能为 ALAC 编码，当前版本暂不支持播放」。评审拟引入的 `media3-decoder-alac` 经实施核实不存在（Google Maven/Maven Central 404），Media3 无内置 ALAC 软解；全设备可播需第三方 FFmpeg 扩展（用户拍板后续单独排期）。
5. CUE 联动：`CueIndexer.WHOLE_EXTENSIONS` 增 `"m4b"`。
6. 版本：v2.2.4 / versionCode 100（debug APK）。

## Out of Scope

FFmpeg 集成（APE/DSF/DFF，长期挂起待用户拍板，不随本提案实施）；iTunes 章节表之外的其他章节形态（如 ASR 章节、M4B 非标准私有原子）；Audible .aax（加密格式）；MIME/容器探测识别（仅扩展名）。

## How to Test

- 章节解析器：构造字节单测（最小 MP4 box 结构：chpl 原子 / 简化 iTunes 章节轨）。
- 扫描接入：flow 测试经 ScanCoordinator 注入带章节的标签 → 断言章节虚拟轨产物（吸收/归属/退化）。
- 全量 UT（Robolectric + Room in-memory）通过；debug APK `BookListener-v2.2.4-m4b.apk`（versionCode 100）交付。
- ALAC m4b 播放提示与 AAC m4b 播放真机行为标注真机验证；iTunes 章节表真实样本（Audible m4b）标注真机验证。
