## 01-switch-to-official-basicmarquee
---
id: t1-switch-official
status: done
---

切换 MarqueeText 滚动实现为官方 `Modifier.basicMarquee`，**全部使用命名参数**（iterations=Int.MAX_VALUE / Immediately / repeatDelay=0 / initialDelay=0 / spacing=两字符 / velocity=33.dp），杜绝位置参数错位（v2.0.2 曾将 0 传入 iterations 导致官方禁用动画、真机字不动）。

## 02-restore-align-and-tests
---
id: t2-align-tests
status: done
---

textAlign 按滚动/静态分支恢复（短文本居中、长文本左对齐滚动）；测试重构为 5 用例：短文本静态 bounds、超长渲染可见、滚动分支 tag、静态分支 tag、官方布局约束。

## 03-verify-deliver
---
id: t3-verify-deliver
status: done
---

testDebugUnitTest 233 用例全绿；出包 BookListener-v2.0.4-marquee-fix.apk；真机验收通过（用户确认：连续左滚、两字符间距、无叠字无空屏）。

