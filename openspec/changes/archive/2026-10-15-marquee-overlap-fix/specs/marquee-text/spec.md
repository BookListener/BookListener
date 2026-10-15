# MarqueeText 规范

## ADDED Requirements

### Requirement: 滚动判定（仅超宽滚动）

MarqueeText SHALL 仅当文本实际宽度超过可视宽度时启动滚动，短文本 MUST 静止显示。

#### Scenario: 超长文本进入滚动分支

- **WHEN** 文本宽度（无约束测量）超过容器宽度
- **THEN** Text 进入滚动分支（testTag `marquee-scroll`），使用官方 basicMarquee 滚动

#### Scenario: 短文本静止显示

- **WHEN** 文本宽度不超过容器宽度
- **THEN** Text 进入静态分支（testTag `marquee-static`），不滚动、textAlign 生效

### Requirement: 无缝跑马灯画面（两份同屏）

滚动过程中，第一份文本尚未完全滚出屏幕（尾部仍在屏幕上）时，第二份文本的开头 SHALL 已经从屏幕右侧进入，两份文本 MUST 同时在屏幕上可见并一起向左移动。

#### Scenario: 滚动中段两份同屏

- **WHEN** 超长文本触发跑马灯，滚动进行到中段
- **THEN** 屏幕上 MUST 同时显示第一份文本的尾部和第二份文本的头部，且整行连续向左移动

### Requirement: 间隔不超过两个字符

滚动中第一份文本的最后一个字符与第二份文本的第一个字符之间的空白间隔 SHALL NOT 超过两个字符宽（实现：`MarqueeSpacing((fontSizeSp * fontScale * 2).dp)`）。

#### Scenario: 间隔不超过两字符

- **WHEN** 超长文本滚动，两份文本同时在屏幕上
- **THEN** 两者之间的空白间隔 MUST NOT 超过两个字符宽

### Requirement: 无空屏、无跳变、无叠字

滚动 SHALL 连续循环，MUST NOT 出现空屏停顿、跳变或叠字。

#### Scenario: 完整循环无断层

- **WHEN** 跑马灯滚动完整循环任意多轮
- **THEN** 屏幕上 MUST 始终有文本在滚动，MUST NOT 出现整屏空白等待、画面跳变或字体重叠

### Requirement: 官方 basicMarquee 动画启动参数正确

官方 `Modifier.basicMarquee` 调用 MUST 全部使用命名参数；`iterations` MUST 为 `Int.MAX_VALUE`（0 = 官方禁用动画，导致文本永不滚动）；`animationMode` MUST 为 `Immediately`；`repeatDelayMillis` 与 `initialDelayMillis` MUST 为 0（无缝衔接、无首轮延迟）。

#### Scenario: iterations 非零保证动画启动

- **WHEN** 超长文本进入滚动分支
- **THEN** 传给官方 basicMarquee 的 `iterations` MUST NOT 为 0，动画 MUST 持续运行（真机可见连续左滚）

### Requirement: 回归测试

MarqueeTextTest MUST 覆盖：短文本静态（动画推进后布局 bounds 不变）、超长文本渲染可见（滚动多轮后语义节点仍完整）、超长文本进入滚动分支（testTag）、短文本进入静态分支（testTag）、官方 basicMarquee 布局约束（Text 布局宽被约束到容器宽）。

#### Scenario: 测试套件存在且通过

- **WHEN** 运行 `testDebugUnitTest`
- **THEN** MarqueeTextTest 5 用例 MUST 全部通过（Robolectric 无法像素/帧级验证动画，动画行为以真机验收为准）
