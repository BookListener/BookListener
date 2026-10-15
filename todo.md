# TODO：跑马灯 bug 修复

## 状态
- [x] v2.0.1a：dp/px 单位同源换算修复（未根治——真机截图仍叠字）
- [x] v2.0.2：弃自研实现，改用官方 `Modifier.basicMarquee`（因参数错位导致真机"字不动"，未达交付）
- [x] **v2.0.3 根因定位（2026-10-15）：官方 basicMarquee 位置参数错位 → iterations=0 → 官方禁用动画 → 真机"字不动"**
  - 错位调用：`basicMarquee(0, Immediately, Int.MAX_VALUE, 0, spacing, 33.dp)`
  - 官方签名：`(iterations, animationMode, repeatDelayMillis, initialDelayMillis, spacing, velocity)`
  - 实际效果：iterations=0（KDoc 明确"0 will disable animation"）→ runAnimation 首行直接 return；
    注释也全错位（把 0 当 initialDelayMillis 注释、Int.MAX_VALUE 当 repeatCount 注释）
  - 修复：全部改用命名参数（iterations=Int.MAX_VALUE / Immediately / repeatDelay=0 / initialDelay=0 / spacing=两字符 / velocity=33.dp）
- [x] 排查过程证据：官方 1.11.3 与 1.12.1 字节码逐字节一致（升级无效）；官方源码全量 fetch 逐行核对（逻辑完整）；
      主题无全局动画禁用；分支判定正确（DBG R tw=1136>cw=984）；Robolectric 无法像素/帧级验证
      （captureToImage 抛 WindowCapture 异常；waitForIdle 不感知动画帧——对照实验证实）
- [x] testDebugUnitTest MarqueeText 套件 5 用例全绿（静态 bounds / 渲染可见 / 滚动分支 tag / 静态分支 tag / 布局约束）
- [x] 真机验收：超长专辑名/曲目名连续左滚、两字符间距、无叠字、无空屏、无跳变（**用户 2026-10-15 确认"好了"**）
- [ ] v2.0.4 debug 包确认后出 release 正式包（移除 DBG 取证行）
- [x] 复盘与经验固化：docs/marquee-fix-retrospective.md（根因/弯路清单/R1-R7 规则）

## 现象（用户描述）
1. 超长文本滚动中，两个字/两份文本重叠到一起（叠字）。
2. 滚动不连续：要等最后一个字符进入左边后才从头开始（空屏停顿感）。

## 正确需求（用户最终确认）
标准无缝跑马灯：
- 第一份文本还没滚完（尾部还在屏上）时，第二份开头已经从右边进入，**两份同时在屏幕上**；
- 第一份最后一个字符与第二份第一个字符之间空**不超过两个字符**；
- 整行连续向左滚动，循环不断；
- 全程不叠字、不空屏、不跳变。

## 根因链（含修正）
v2.0.1a 定位到的单位错位（`Spacer(width(gap.dp))` 把像素当 dp）只是**表面根因**。
真机截图（v2.0.1a）像素分析：曲目标识行 238 列出现"双簇笔画"（同一 x 位置两套文本、中间 7~19px 空隙）→ **叠字仍在**。
深层根因：自研方案用 TextMeasurer 无约束测量文本宽 + Row 双份拼接 + Animatable 手动循环，
**测量宽度与真实渲染宽度存在不一致**（字体 fallback、密度舍入等），且动画周期与布局拼接
依赖手工同步，任何一处偏差都表现为叠字/空屏/跳变，反复修补无法根治。

## 修复方案（v2.0.2，根治）
弃自研实现，改用 Jetpack Compose 官方 `Modifier.basicMarquee`（Foundation 1.11 内置）：
- 官方节点内部用**无约束宽度**测量真实内容宽，与渲染管线同源（反编译确认 measure 用 maxWidth=∞）；
- 内容绘制走独立 GraphicsLayer + 偏移动画，两份内容间距（`MarqueeSpacing`）与循环衔接由官方保证；
- 参数：间隔 = 两字符宽 `(fontSize * fontScale * 2).dp`，速度 33dp/s，immediately 模式、0 延迟无缝循环；
- 短文本分支保持原行为（静态 + textAlign 居中）。

## 测试升级（回应"单元测试有啥用"）
旧测试是纯函数计算（shouldMarquee / gapPxOf），测不到真实渲染。
v2.0.2 重写为 **Compose UI 渲染测试**（createComposeRule + Robolectric，真实渲染路径）：
- shortText_staysStatic：短文本渲染后布局 bounds 稳定（不滚动）
- longText_rendersAndIsVisible：超长文本滚动多轮后语义节点仍完整可见（不崩溃、不丢内容）
- longText_matchesWholeString_notTruncatedInSemantics：语义树中是完整字符串

## 反思要点（另文已答用户）
- 反复改不对的根因：一直在现象层打补丁，未发现自研方案"测量与渲染不一致"的深层根因；
- 正确路径应是先查官方/业界标准实现（basicMarquee），而不是自己造轮子反复修补；
- 纯函数单测覆盖不到渲染层问题，UI 渲染测试 + 真机截图才是 UI bug 的验证方式。
