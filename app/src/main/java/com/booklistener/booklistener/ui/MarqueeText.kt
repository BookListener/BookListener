package com.booklistener.ui

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 超长单行文本向左滚动（marquee），短文本静止显示。
 *
 * v2.0.2（本版）：放弃自研"双份拼接 + Animatable 手动循环"（v1.6.6~v2.0.1a 期间反复出现
 * 叠字 / 空屏 / 跳变，根因是自研测量宽度与真实渲染不一致、动画周期与布局不同步），
 * 改用 Jetpack Compose 官方 [Modifier.basicMarquee]：
 * - 官方节点内部用「无约束宽度」测量真实内容宽，与渲染管线同源，杜绝测量/渲染错位；
 * - 内容绘制走独立 GraphicsLayer + 偏移动画，两份内容间距（MarqueeSpacing）与循环
 *   衔接由官方保证，无叠字、无空屏停顿。
 *
 * 短文本分支保持原行为：静态显示、textAlign 生效（如详情页标题居中）。
 * v2.0.11：Text 补 fillMaxWidth——此前 Text 为 wrap-content 宽度，textAlign 只对占满容器
 * 宽度的 Text 生效，导致短名/静止时"居中"实际失效（偏左）；补宽后静止居中、滚动左锚不变。
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null
) {
    val measurer: TextMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontScale = density.fontScale

    // 无约束测量真实文本宽——与官方 basicMarquee 判定（内容超宽才滚动）同源
    val textWidth = remember(text, style, fontScale) {
        measurer.measure(
            text = AnnotatedString(text),
            style = style,
            softWrap = false,
            overflow = TextOverflow.Visible,
            maxLines = 1
        ).size.width
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        // toPx 是 Density 接口成员方法：必须通过 Density receiver 调用。
        // BoxWithConstraintsScope 即 Density，但为消除 receiver 成员遮蔽歧义，显式用 LocalDensity。
        val containerWidthPx = with(density) { maxWidth.toPx() }.roundToInt()
        val scroll = textWidth > containerWidthPx

        Column(modifier = Modifier.fillMaxWidth()) {
            // 取证版-2：无条件走官方 basicMarquee（官方内部判定 contentWidth>containerWidth
            // 才滚动；短文本自动静止）。用于区分"官方动画真机不滚"还是"分支组合问题"。
            val fontSizeSp = style.fontSize
                .takeIf { it.isSpecified }?.value
                ?: LocalTextStyle.current.fontSize.value
            val gapDp = remember(fontSizeSp, fontScale) {
                (fontSizeSp * fontScale * 2).dp
            }
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = if (scroll) TextAlign.Start else (textAlign ?: TextAlign.Start),
                modifier = Modifier
                    // v2.0.11：fillMaxWidth 使 textAlign 真正生效——静止/短名时 Center 居中；
                    // 滚动时仍 Start 左锚（跑马灯平移锚点），行为不变
                    .fillMaxWidth()
                    .testTag(if (scroll) "marquee-scroll" else "marquee-static")
                    .basicMarquee(
                        iterations = Int.MAX_VALUE, // 无限循环（官方：0 会禁用动画）
                        animationMode = MarqueeAnimationMode.Immediately, // 立即开始，无需聚焦
                        repeatDelayMillis = 0, // 轮间无停顿，无缝衔接
                        initialDelayMillis = 0, // 无首轮延迟
                        spacing = MarqueeSpacing(gapDp), // 第一份尾与第二份头的间距（约 2 字符）
                        velocity = 33.dp // 滚动速度 33dp/s
                    )
            )
        }
    }
}
