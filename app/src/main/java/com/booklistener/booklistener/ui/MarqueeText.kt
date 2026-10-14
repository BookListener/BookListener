package com.booklistener.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 超长单行文本向左缓慢滚动（marquee），短文本静止显示。
 * 判定"是否滚动"为纯逻辑（shouldMarquee），可单元测试。
 *
 * v1.6.5 修复：此前用 Text 的 onTextLayout 测量文本宽，但 Text 在 fillMaxWidth
 * 约束下布局时，超长文本的测量宽会被裁成容器宽，导致 shouldMarquee 恒为 false、
 * 焦点位置超长标题从不滚动。现改用 TextMeasurer 做无约束测量，得到真实文本宽。
 *
 * v1.6.6：滚动改为跑马灯式无缝回绕——"文本 + 间隔空白 + 文本"双份拼接循环：
 * 每轮从左至右平移 (textWidth + gap)，尾端离开后开头随下一轮平滑滑入，
 * 不再"滚动到底后跳回开头重滚"的生硬跳跃；短文本不拼接、不滚动。
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
    var containerWidth by remember { mutableIntStateOf(0) }

    // 无约束测量真实文本宽（softWrap=false + Visible，结果不受父容器约束裁剪）
    val textWidth = remember(text, style, fontScale) {
        measurer.measure(
            text = AnnotatedString(text),
            style = style,
            softWrap = false,
            overflow = TextOverflow.Visible,
            maxLines = 1
        ).size.width
    }

    val scroll = shouldMarquee(textWidth, containerWidth)

    if (!scroll || containerWidth == 0) {
        // 短文本：单份静态显示（textAlign 生效）
        // v2.0.1：containerWidth==0（首帧视口未测得）也走静态分支——若以两字符间隔
        // 先行启动滚动，之后 gap 重算为视口宽但动画周期不同步，会造成"头尾叠加"残留。
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { containerWidth = it.width },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    // v2.0.1 无缝回绕间隔：必须 ≥ 视口宽——"文本+间隔+文本"循环中，
    // 只有间隔覆盖整个视口，第一份文本的尾部才会在第二份头部滑入前完全离开，
    // 杜绝两者同时在视口内造成的"头尾叠加"（此前间隔仅两字符宽，滚动中首尾重叠显示）。
    // 首帧 containerWidth 尚未测得（0）时退化为两字符宽，布局完成后按实际视口重算。
    val twoCharGap = (style.fontSize.value * fontScale * density.density * 2).roundToInt()
        .coerceAtLeast(24)
    val gap = remember(containerWidth, twoCharGap) { marqueeGap(twoCharGap, containerWidth) }

    // 每轮平移距离 = 文本宽 + 间隔空白；Restart 时画面已等同"下一份文本的开头"，视觉无缝
    val cycleDist = textWidth + gap

    // v2.0.1：改为 Animatable + LaunchedEffect(cycleDist) 手动循环。
    // rememberInfiniteTransition.animateFloat 的 target/duration 在参数变化后不保证重算，
    // 一旦 gap（随 containerWidth 测得值）变化，循环周期与拼接长度错位，
    // 会在滚动中产生"第二份文本提前滑入"的头尾叠加。手动循环使周期与 cycleDist 严格同步。
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(cycleDist) {
        while (true) {
            offsetX.animateTo(
                targetValue = -cycleDist.toFloat(),
                animationSpec = tween(
                    durationMillis = (cycleDist * 30).coerceAtLeast(1),
                    easing = LinearEasing
                )
            )
            offsetX.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { containerWidth = it.width },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start)
                .graphicsLayer { translationX = offsetX.value }
        ) {
            MarqueeTextPiece(text, style, color)
            Spacer(modifier = Modifier.width(gap.dp))
            MarqueeTextPiece(text, style, color)
        }
    }
}

/** 单份滚动文本（不可换行、不省略，由外层 Box clip） */
@Composable
private fun MarqueeTextPiece(text: String, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        textAlign = TextAlign.Start
    )
}

/** 文本实际宽度超过可视宽度时启动滚动；恰好相等或更短则静止 */
internal fun shouldMarquee(textWidth: Int, containerWidth: Int): Boolean =
    textWidth > containerWidth

/**
 * 无缝回绕间隔：至少覆盖整个视口宽（containerWidth），
 * 保证滚动循环中视口内同时最多一份文本，避免首尾叠加。
 * 视口未测得（0）时退化为两字符宽下限。
 */
internal fun marqueeGap(twoCharWidth: Int, containerWidth: Int): Int =
    maxOf(twoCharWidth, containerWidth)
