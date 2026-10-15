# player-sheet-rise-animation Specification

## MODIFIED Requirements

### Requirement: 点击封面按钮触发双组件上移动画

点击主页底部圆形封面按钮、专辑路径（requestPlayerSheet）或动作条整体上滑（48dp 阈值）弹出播放详情时，MUST 触发两段式展开动画：动作条三个组件（曲目/封面/设置）上移消失（200ms）→ 面板滑入（400ms），无空档；动作条玻璃条本身留在底部不动。v2.0.7 触发路径（showPlayerSheet=true）同步置 sheetOpen=true。

#### Scenario: 打开动画

- **WHEN** 点击封面按钮 / 点击专辑 / 动作条上滑（任一入口）
- **THEN** 三组件上移消失（200ms）→ 面板滑入（400ms），无空档，动作条玻璃条不动

#### Scenario: 点击封面按钮弹出详情

- **WHEN** 用户点击底部圆形封面按钮（正在播放图标）
- **THEN** 详情面板从底部滑入，同时动作条三组件（曲目/封面/设置）上移消失，两段式接力展开

### Requirement: 验收

`testDebugUnitTest` 全绿后构建 APK；真机验收 MUST 覆盖：①打开可见三组件上移消失→面板滑入（无空档）；②关闭可见面板下滑（全程可见）→组件归位（尾部连贯）；③专辑列表页内返回键先关面板；④动作条上滑展开；⑤点击/专辑/上滑三种入口动画一致。

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.8 APK 真机操作上述场景
- **THEN** 动画效果符合本 spec 全部场景，测试全绿

## ADDED Requirements

### Requirement: 关闭动画（连贯收回）

关闭 MUST 为：面板下滑（400ms，slideOutVertically + fadeOut 同步 400ms——下滑全程可见）→ 面板滑到组件位置（delay 250ms）→ 三组件下移归位（200ms），与面板尾部重叠衔接（视觉"面板收回到组件处→组件接着收回"）。

#### Scenario: 关闭时序

- **WHEN** `sheetOpen=false`（LaunchedEffect 关闭分支）
- **THEN** 面板 0→400ms 下滑；组件 250→450ms 下移归位；与面板尾部重叠连贯

### Requirement: 返回键优先级

面板显示（visible=true）时系统返回键 MUST 先关闭面板：`DisposableEffect + OnBackPressedCallback` 在 visible 变化时 remove 再 add 重新注册到 LIFO 栈顶，优先于专辑列表页/文件夹页的 BackHandler；面板关闭后返回键行为回到背后页面。

#### Scenario: 专辑列表页内返回

- **WHEN** 用户在专辑列表页上面板已显示，按系统返回键
- **THEN** 面板关闭（onDismiss），专辑列表不退出；再按返回键才退出专辑列表

### Requirement: 动作条上滑展开

底部玻璃动作条（曲目/封面/设置三组件所在区域整体）MUST 支持向上拖拽累计超 48dp 展开面板，动画与点击封面按钮同一套；上滑只展开面板，不切文件夹、不恢复播放、不切换页面。

#### Scenario: 动作条上滑

- **WHEN** 用户在动作条任意位置向上拖拽超过 48dp
- **THEN** 展开播放详情面板（同套动画），页面与播放状态不变

## REMOVED Requirements

### Requirement: 动作条让位到面板顶边上方

被"动作条玻璃条不动、三组件上移消失"语义取代（v2.0.8d 用户澄清"三大件=动作条三组件"）。

### Requirement: 面板滑入与动作条同一时间轴

被两段式接力时序取代（组件 200ms 先消失、面板 400ms 后滑入）。

### Requirement: 400ms 快节奏与对称退出

被打开/关闭独立时序取代（打开 200+400ms、关闭 400+250+200ms，两端动作时间、空间连贯）。

### Requirement: 面板高度实时联动

不再需要——动作条不再整体位移（onPanelHeightChange 回调保留为默认空实现）。
