---
id: m4a-m4b-series-freeform-priority
status: proposed
title: 系列分组标签解析优先级：MP4 自由格式 SERIES / SERIES-PART 首选 + 命名空间同源配对（v2.2.22）
---

## Why

主页系列分组目前只认 `©ser` / `©sno` 标准原子与 ID3 `TXXX:SERIES`。有声书生态（ABS/Plex）的实际文件常把系列信息写入 MP4 自由格式原子（name=`SERIES` / `SERIES-PART`，常附小写镜像 `series` / `series-part`）——这些键当前只进 `entry_tag_extras`、不进结构化 `series` 列，导致大量真实 m4b/m4a 无法按系列分组。本次调整解析优先级，兼容生态现状。

## Context

- 经 grill-me 多轮评审定稿（2026-10-30）：Q1=大小写不敏感归一匹配、Q2=取值原样入库不规范化、Q3=文档全篇不提任何商业品牌自有命名、Q4=本轮一并实施解析层；命名空间评审（2026-10-30）：Q1=键格式 `----:<issuer>:<name>`、Q2=无同源序号回退 `©sno`、Q3=自由格式仅序号时允许字段级回退。
- 环境事实（已核实）：`Mp4IlstReader.parseFreeForm` 当前输出键 `----:name`（payload = `\0issuer\0name\0value`，name 取第二段，**issuer 被丢弃**）；`TagFields.series()` 当前只查 `TXXX:SERIES / ©ser / TIT1`，`seriesNumber()` 只查 `TXXX:SERIESNUMBER / ©sno`，均不查 `----` 键；`entry_tag_extras` 全量保留不受影响。
- 文档已先行落地：`docs/TAG-FIELDS.md` §2（系列分组标签定义含命名空间同源配对）、README「系列分组 / 专辑化曲目」条目已更新。

## What Changes

1. `parseFreeForm` 保留命名空间：键格式改 `----:<issuer>:<name>`（issuer 非空）；issuer 为空保持 `----:<name>` 旧格式（视为独立命名空间）。
2. `TagFields.series()`：MP4 链路**首选**自由格式系列键（`SERIES`/`series`，命名空间内大小写不敏感归一），缺失降级 `©ser`；mp3 链路不变（`TXXX:SERIES` → 次选 `TIT1`）。
3. `TagFields.seriesNumber()`：MP4 链路**首选**自由格式序号键（`SERIES-PART`/`series-part`），缺失降级 `©sno`；mp3 链路不变（`TXXX:SERIESNUMBER`）。
4. **同源配对**：系列名与系列序号必须来自同一命名空间——同一命名空间成对提供则同取；只提供其一 → 另一字段回退字段级标准降级链；**禁止跨命名空间拼凑**。
5. 取值原样入库，不做数字提取/规范化（"Part 1" / "Vol 2" / 纯数字均可，组内排序已有数字感知自然序兜底）。
6. 版本：v2.2.22 / versionCode 递增 1（已确认变更，仅升 patch，不升 minor）。

## Out of Scope

`----` 键之外的其他私有系列原子；对存量数据回填（重扫生效，不做迁移，沿用 m4b「重扫生效」语义）；`SERIES-PART` 取值数字归一（原样入库）；extras 旧键格式数据迁移（重扫整体替换）。

## How to Test

- flow 测试（ScanCoordinator 注入含自由格式系列标签的 m4b/m4a）断言结构化 `series`/`seriesNumber` 与主页分组产物：① 同命名空间成对命中；② 跨命名空间禁止（名 A、序号 B → 序号回退 `©sno`/空）；③ 仅小写镜像命中；④ 与 `©ser`/`©sno` 并存时自由格式优先；⑤ 自由格式缺失降级；⑥ 自由格式仅序号 → 序号取自由格式、名回退 `©ser`；⑦ mp3 `TXXX:SERIES`/`TIT1` 行为不变（回归）。
- 全量 UT（Robolectric + Room in-memory）通过；debug APK `BookListener-v2.2.22-series-freeform.apk` 交付。
