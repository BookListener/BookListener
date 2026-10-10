package com.booklistener.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    if (!scroll) {
        // 短文本：单份静态显示（textAlign 生效）
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

    // 间隔空白：两个字符宽（用户要求的"适当空格"），让尾端离开后短暂留白再滑入开头
    val gap = (style.fontSize.value * fontScale * density.density * 2).roundToInt()
        .coerceAtLeast(24)

    // 每轮平移距离 = 文本宽 + 间隔空白；Restart 时画面已等同"下一份文本的开头"，视觉无缝
    val cycleDist = textWidth + gap
    val transition = rememberInfiniteTransition()
    val offsetX by transition.animateFloat(
        initialValue = 0f,
        targetValue = -cycleDist.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (cycleDist * 30).coerceAtLeast(1), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

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
                .graphicsLayer { translationX = offsetX }
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
