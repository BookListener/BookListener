---
id: marquee-overlap-fix
status: proposed
title: 修复超长文本跑马灯（叠字/空屏/字不动）——最终以官方 basicMarquee 根治
---

## Why

MarqueeText（专辑视图顶部标题、专辑行、播放详情页标题）在超长文本滚动上先后出现三类缺陷：叠字、空屏停顿、字不动。前两类源于自研方案（TextMeasurer 无约束测量 + Row 双份拼接 + Animatable 手动循环）**测量宽度与真实渲染宽度不一致**，任何偏差都表现为叠字/空屏；第三类源于切换官方方案时的**位置参数错位**（iterations=0 → 官方禁用动画）。用户要求标准无缝跑马灯：两份文本同屏、间隔不超过两字符、连续左滚、无叠字无空屏无跳变。

## Context

- 组件：`app/src/main/java/com/booklistener/booklistener/ui/MarqueeText.kt`。
- 演化史：
  1. **v1.6.4 ~ v2.0.1a（自研方案）**：双份拼接 + 手动循环。反复出现叠字（v2.0.1a 真机截图 238 列双簇笔画）。根因是测量与渲染不一致 + 动画周期手工同步，现象层修补（dp/px 换算、gap≥视口宽）均未根治；v2.0.1"gap≥视口宽"还引入空屏停顿，被用户拆穿为投机式修复。
  2. **v2.0.2（切官方）**：改用 Jetpack Compose 官方 `Modifier.basicMarquee`，但**位置参数错位**：`basicMarquee(0, Immediately, Int.MAX_VALUE, 0, spacing, 33.dp)` 中第 1 个位置 `0` 落到 `iterations`（官方语义 0 = 禁用动画）→ 真机"字不动"；注释也全错位。
  3. **v2.0.3（取证）**：DBG 红字行确认分支判定正确（tw=1136 > cw=984），但取证点选在外围旁证，未打印传给官方的参数；Robolectric 像素/帧验证不可行（captureToImage 抛 WindowCapture、waitForIdle 不感知动画帧，对照实验证实）；1.11.3 与 1.12.1 官方字节码逐字节一致（升级无效）。
  4. **v2.0.4（根治）**：basicMarquee 全部改用**命名参数**（iterations=Int.MAX_VALUE / Immediately / repeatDelay=0 / initialDelay=0 / spacing=两字符 / velocity=33.dp）；textAlign 按滚动/静态分支恢复；测试重构为分支判定 + 布局约束 + 静态 bounds（5 用例）。真机验收通过（用户 2026-10-15 确认"好了"）。
- 复盘与经验固化：`docs/marquee-fix-retrospective.md`（8 条弯路清单 + R1-R7 可复用规则）。

## What Changes

MarqueeText 滚动实现由"自研双份拼接 + 手动循环"改为**官方 `Modifier.basicMarquee` 单一实现**，调用参数从位置传参改为**命名参数**（杜绝参数错位）；滚动/静态分支判定保留（仅用于 textAlign 与 DBG），动画行为完全交给官方节点（无约束测量 + GraphicsLayer 偏移动画 + 双份无缝绘制）。

## Requirements

- **REQ-1（滚动判定）**: MarqueeText SHALL 仅当文本实际宽度超过可视宽度时启动滚动，短文本 MUST 静止显示。
- **REQ-2（画面关系）**: 滚动中两份文本 MUST 同时在屏幕上可见——第一份尾部未完全离开时第二份头部已从右侧进入。
- **REQ-3（间隔上限）**: 第一份最后一个字符与第二份第一个字符之间的空白间隔 MUST NOT 超过两个字符宽（实现取 `(fontSizeSp * fontScale * 2).dp` 传入 `MarqueeSpacing`）。
- **REQ-4（动画启动参数）**: 官方 `basicMarquee` 调用 MUST 使用命名参数，且 `iterations` MUST NOT 为 0（0 = 官方禁用动画），MUST 为 `Int.MAX_VALUE` 实现无限循环；`repeatDelayMillis` / `initialDelayMillis` MUST 为 0（无缝、无延迟）。
- **REQ-5（无缝循环）**: 滚动 MUST 连续循环，MUST NOT 出现空屏停顿、跳变或叠字。
- **REQ-6（回归测试）**: MarqueeTextTest MUST 覆盖：短文本静态（bounds 不变）、超长文本渲染可见、超长文本进入滚动分支（testTag marquee-scroll）、短文本进入静态分支（testTag marquee-static）、官方 basicMarquee 布局约束（Text 布局宽被约束到容器宽）。
- **REQ-7（验收）**: `testDebugUnitTest` 全绿后出包，真机验收超长专辑名/曲目名滚动满足 REQ-2/REQ-3/REQ-5（用户确认通过）。

## Out of Scope

- 不改变短文本静止显示的既有行为与 textAlign（居中）语义。
- 不改变滚动速度（33dp/s）。
- 不涉及其他组件（播放、队列、搜索等）行为。
- 专辑列表格线/分割线/毛玻璃等 UI 遗留问题（独立跟踪）。
- release 正式包（移除 DBG 取证行）待 debug 验收后另行交付。

## Consequences

- 滚动观感恢复为标准无缝跑马灯（官方实现保证测量与渲染同源、双份绘制与循环衔接）。
- 命名参数调用成为该组件防参数错位的长期约束；分支判定测试成为防回归基线。
- 自研拼接方案（v1.6.4~v2.0.1a）及其投机式补丁不再保留。
