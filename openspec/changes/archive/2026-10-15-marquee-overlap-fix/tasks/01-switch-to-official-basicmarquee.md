---
id: t1-switch-official
status: done
---

切换 MarqueeText 滚动实现为官方 `Modifier.basicMarquee`，**全部使用命名参数**（iterations=Int.MAX_VALUE / Immediately / repeatDelay=0 / initialDelay=0 / spacing=两字符 / velocity=33.dp），杜绝位置参数错位（v2.0.2 曾将 0 传入 iterations 导致官方禁用动画、真机字不动）。
