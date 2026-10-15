# Backdrop 液态玻璃组件：引用与配置方案

> 适用版本：`io.github.kyant0:backdrop:2.0.0`（Compose Multiplatform 版 AndroidLiquidGlass）
> 最后校准：v2.0.6d（2026-10-15，真机验收"很好"）
> 本文全部参数语义来自对 AAR 字节码的反编译实证，非文档推断。

## 一、组件引用

```groovy
// app/build.gradle
// Liquid Glass 特效（v1.6.15 工具链升级后引入：Compose Multiplatform backdrop）
implementation 'io.github.kyant0:backdrop:2.0.0'
```

依赖树：`io.github.kyant0:backdrop:2.0.0` → `backdrop-android:2.0.0`（AAR）+ `io.github.kyant0:shapes:1.2.0`（圆角 SDF 支持）。

关键 import（MainScreen.kt / PlayerDetailSheet.kt）：

```kotlin
import com.kyant.backdrop.backdrops.layerBackdrop        // 内容层捕捉 modifier
import com.kyant.backdrop.backdrops.rememberLayerBackdrop // 创建 Backdrop 捕捉层
import com.kyant.backdrop.drawBackdrop                   // 玻璃面板绘制 modifier
import com.kyant.backdrop.effects.blur                   // 模糊 effect
import com.kyant.backdrop.effects.lens                   // 透镜折射 effect
```

## 二、使用位置

| 位置 | 文件 | 用途 | 是否使用 backdrop |
|---|---|---|---|
| 主页底部动作条 | MainScreen.kt | 96dp 玻璃底栏 | ✅ drawBackdrop + layerBackdrop |
| 播放详情面板 | PlayerDetailSheet.kt | 玻璃浮层（同合成树复用同一 backdrop） | ✅ drawBackdrop |
| 顶部搜索框 | DirectoryScreen.kt | 半透明圆角输入框 | ❌ 纯色半透明（见"已知限制"） |

## 三、核心概念与渲染管线（字节码实证）

### 3.1 三个角色

1. **Backdrop（捕捉层）**：`rememberLayerBackdrop { drawRect(backgroundColor); drawContent() }` —— 定义"玻璃后面是什么"的绘制内容。
2. **layerBackdrop(backdrop)**：挂在内容层（Scaffold）上，`LayerBackdropNode.draw` **每帧**把内容 record 进 backdrop 的 GraphicsLayer（背景实时捕捉，列表滚动可见）。
3. **drawBackdrop(...)**：挂在玻璃面板上，`DrawBackdropNode.draw` 绘制玻璃。

### 3.2 DrawBackdropNode.draw() 绘制顺序（反编译实证）

```
1. onDrawBehind          （本项目未用）
2. drawBackdropLayer     ← backdrop 捕捉层内容经 effects（blur/lens）生成 RenderEffect 后绘制
3. onDrawSurface         ← 表面层：drawRect(表面色.copy(alpha))，画在模糊层之上
4. drawContent()         ← 面板内图标/文本
5. onDrawFront           （本项目未用）
6. recordLayer           （exportedBackdrop 未传时跳过）
```

要点：**onDrawSurface 的 alpha 只影响"模糊结果"的透出比例，不影响背景内容可辨度**。内容可辨度由 effects 里的 blur 半径决定。

## 四、effects 参数语义（反编译实证）

### 4.1 blur

```
签名：blur(radius: Float, tileMode: Int = Clamp)
底层：BlurEffect(radius, radius, Clamp) —— Android RenderEffect 模糊
单位：radius 为 PX（本地写法 blur(10f.dp.toPx())）
```

| radius | ≈px（3x 密度） | 观感 |
|---|---|---|
| 24dp | 72px | 背景糊成光斑、轮廓全无（"不透"假象的来源） |
| 10dp | 30px | 背景文字/封面轮廓可辨，主流毛玻璃（**当前值**） |
| 4dp | 12px | 近普通半透明面板 |

### 4.2 lens（透镜折射，AGSL shader）

```
签名：lens(refractionHeight: Float, refractionAmount: Float,
          depthEffect: Boolean = false, chromaticAberration: Boolean = false)
单位：均为 PX；depthEffect / chromaticAberration 默认关
```

shader 逻辑（Refraction）：`sd` = 到圆角矩形的有符号距离（内部为负）。
- **`-sd >= refractionHeight` 的区域直接原样采样、完全不折射** → refractionHeight 决定折射带宽度；
- 折射带内偏移量 `d = circleMap(1 - (-sd)/refractionHeight) * refractionAmount` → 越靠边缘越强，refractionAmount 决定最大偏移（px）。

| 参数 | 当前值 | 调大效果 |
|---|---|---|
| refractionHeight | 40dp | 折射带更宽，边缘/中心对比更强 |
| refractionAmount | 72 | 边缘弯曲/放大感更强 |
| depthEffect | true | 边缘深度扭曲（玻璃球感） |
| chromaticAberration | true | 边缘 RGB 色散（棱镜感） |

### 4.3 表面与遮罩

- 表面：`onDrawSurface = { drawRect(glassSurface.copy(alpha = 0.30f)) }`，`glassSurface = MaterialTheme.colorScheme.surfaceVariant`（浅灰）。
  **浅色主题下禁止用 background 做表面**——它与模糊背景同色系，alpha 变化肉眼不可见（v2.0.5/v2.0.5b 无效调参的实证结论）。
- 详情弹窗遮罩：`Color.Black.copy(alpha = 0.5f)`（v2.0.6 由 0.9 降来）。遮罩挡光独立于玻璃，0.9 时弹窗总透光率仅 ~7%，"永远不透"。

## 五、当前最终配置（v2.0.6d，三处统一口径）

```kotlin
// 主页底部动作条 MainScreen.kt / 播放详情面板 PlayerDetailSheet.kt 一致
.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) },
    effects = {
        blur(10f.dp.toPx())
        lens(
            refractionHeight = 40f.dp.toPx(),
            refractionAmount = 72f,
            depthEffect = true,
            chromaticAberration = true
        )
    },
    onDrawSurface = { drawRect(glassSurface.copy(alpha = 0.30f)) }  // glassSurface = surfaceVariant
)

// 详情弹窗遮罩 PlayerDetailSheet.kt
.background(Color.Black.copy(alpha = 0.5f))

// 搜索框 DirectoryScreen.kt（无 blur，纯色半透明）
focusedContainerColor = surfaceVariant.copy(alpha = 0.30f)
unfocusedContainerColor = surfaceVariant.copy(alpha = 0.28f)
```

## 六、调参指南（真机验收为唯一标准）

| 需求 | 调整 |
|---|---|
| 更透（内容可辨） | blur 10→8dp；表面 alpha 0.30→0.20 |
| 更糊（柔和） | blur 10→16dp |
| 折射更强 | refractionAmount 72→96+；refractionHeight 40→56dp |
| 折射更弱/简洁 | refractionAmount 72→40；chromaticAberration 关 |
| 弹窗更透/更遮 | 遮罩 0.5→0.3 / 0.7 |

## 七、历史版本参数演变（回溯依据）

| 版本 | blur | lens | 表面 | 备注 |
|---|---|---|---|---|
| v1.6.16~18 | 4dp | 16/32 | 45% 遮盖 | 深色主题下几乎看不出 |
| v1.6.19 | 12dp | 24/48 | 25% 遮盖 | 增强可见度 |
| v2.0.1 | 24dp | 去掉 | background 55% | 均匀毛玻璃（去 lens 因圆角边缘模糊不均） |
| v2.0.5 / 5b | 24dp | 无 | background 0.40/0.30 | 仅调 alpha——浅色背景下**无效**（实证） |
| v2.0.6 | 10dp | 无 | surfaceVariant 0.30 | 根因修复：blur 决定可辨度；遮罩 0.9→0.5 |
| v2.0.6d | 10dp | 40dp/72 + 全开 | surfaceVariant 0.30 | 透镜折射到位，用户验收"很好" |

## 八、已知限制与踩坑记录

1. **搜索框不接入 backdrop**：搜索框位于 backdrop 捕捉层内部（layerBackdrop 挂 Scaffold），drawBackdrop 采样自身所在层形成**渲染自引用 → 真机闪退**（v1.6.17 挂 TextField / v1.6.19 挂外层 Box 两次踩坑，v1.6.20 起纯色半透明为根治方案）。
2. **浅色主题 alpha 无效**：表面色与模糊背景同色系时，alpha 变化不可见——调"透"必须先确认表面色与背景有色差。
3. **Robolectric 无法验证玻璃视觉**：渲染效果以真机验收为准，单元测试只保证编译与结构。
4. **lens 依赖 runtimeShader 支持**：`isRuntimeShaderSupported` 检查，极老设备/模拟器可能静默降级（effect 直接 return，无报错）。
