---
id: m4b-moov-range-read
status: proposed
title: MP4/m4b 扫描只读 moov 区域，消除 1GB 大文件整读 OOM 风险（v2.2.5）
---

## Why

用户实测单部 m4b 约 1GB（《哈利·波特》7 部）。当前本地扫描 `ScanCoordinatorFactory.readSafBytes` 把整个文件读成 `ByteArray` 再喂给解析器（Media3 `MetadataExtractor` + 自研 `Mp4IlstReader`/`Mp4ChapterReader`）——1GB 级文件在 Android 上极易 OOM 或扫描卡死。而标签（`©nam`/`©ART`/`©alb`/`©ser`/`©sno`/`covr`）、章节（iTunes 章节表 / chpl）、时长（mvhd）**全部位于 moov box**，mdat（音频数据）对扫描无意义。播放侧已核实为 Media3 `ProgressiveMediaSource` 流式（`DefaultDataSource` 文件描述符按需读），无此问题；本提案只改扫描侧。

## Context

- 事实（已核实）：`resolveLocalTags` 对 `ILST_EXTS = {m4a, mp4, alac, m4b}` 统一走 `readSafBytes` 全量读；`MetadataExtractor.extract`、`Mp4IlstReader.read`、`Mp4ChapterReader.read` 均以整文件字节为输入。mp3/flac/ogg 分支同样整读（文件通常小，不在本次范围）。
- 事实（已核实）：v2.2.4 已交付自研解析器对「文件头非 box 前导」的健壮性修复（commit 43eb029，box 遍历逐字节重对齐 + 已知类型白名单 `KNOWN_BOX_TYPES`）——该白名单遍历逻辑可复用于「定位 moov box 位置」。
- 事实（已核实）：moov 位置有两种形态——头部（ftyp→moov→mdat，faststart 优化）与尾部（ftyp→mdat→moov，非 faststart）；定位必须两者都覆盖。
- 版本状态：v2.2.4 交付后健壮性修复未升版本号（APK 仍标 100/2.2.4）；本提案实施完成后一并升 **2.2.5 / versionCode 101**（patch 位，未经人工确认不升第二位）。
- 风险提示（推演级）：把「ftyp+moov 区间字节」喂给 Media3 `Mp4Extractor`（其预期输入为完整文件布局），需用真实样本验证时长/标签提取不受 mdat 缺失影响；若受影响，保底方案为区间字节尾部补足 mdat 头/预留长度。

## What Changes

1. 新增 moov 区间定位器（纯 Kotlin，无 Android 依赖可单测的 box 遍历部分 + SAF `openAssetFileDescriptor` 读取壳）：
   - 头部路径：读文件头前 1MB（含白名单 box 遍历定位 moov 起止；1MB 内必有 ftyp 与 moov 或可判定 moov 不在头部）。
   - 尾部路径：头 1MB 无 moov 时，读文件末尾 16MB，从后往前定位 moov（覆盖非 faststart 形态；16MB 覆盖大 stbl）。
   - 返回「ftyp 起 + moov 结束」的连续区间字节（含 moov 前的 free/wide 等前导 box，保持解析器输入布局语义）。
   - 定位失败 → 回退整读（保底不丢识别，行为同现状）。
2. 扫描接入：`resolveLocalTags` 的 `ILST_EXTS` 分支改用区间字节（替换 `readSafBytes` 全读）；`Mp4IlstReader.read` / `Mp4ChapterReader.read` / `MetadataExtractor.extract` 输入不变（同一份区间字节，解析器零改动）。
3. 非 `ILST_EXTS` 分支（mp3/flac/ogg）保持整读现状，不扩大改动面。
4. 版本：`app/build.gradle` → versionCode 101 / versionName '2.2.5'；debug APK。

## Out of Scope

WebDAV/网盘路径（`forWebDav` tagReader 恒 null，不解析标签，无此问题）；播放路径（已流式）；mp3/flac/ogg 等非 MP4 容器的大文件整读优化；流式 mmap / 分块解析架构重构；1GB 文件扫描期间的 UI 进度反馈（另行排期）。

## How to Test

- 单测：构造 moov 在头部 / moov 在尾部 / 带非 box 前导 三类字节流，断言区间定位结果正确且 `Mp4IlstReader`/`Mp4ChapterReader` 解析输出与整读一致；`MetadataExtractor` 对区间字节（真实样本 `tagprobe/probe_custom2.m4a` 截取）时长/标签不回归。
- Flow：`M4bFlowTest` 全量回归。
- 全量 UT 0 失败；`assembleDebug` 产物 aapt 核验 101/2.2.5。
- 真机（用户执行，交付后验证）：1GB m4b 重扫不 OOM、不卡死，系列聚合 + 章节虚拟轨与整读版本一致。
