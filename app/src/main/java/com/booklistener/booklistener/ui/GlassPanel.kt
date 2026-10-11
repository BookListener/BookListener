package com.booklistener.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy

/**
 * 液态玻璃面板（v1.6.17）。
 *
 * 用于弹窗/Sheet 类内容：半透明玻璃质感表面（vibrancy + blur + 主题色半透明覆盖），
 * 圆角面板浮在 scrim 之上。弹窗为独立 Window，backdrop 无法折射主界面内容，
 * 因此这里走"毛玻璃面板"路线：透明 sheet 容器 + 玻璃表面层，内容叠加其上保持清晰。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val panelBackdrop = rememberLayerBackdrop {
        drawRect(Color.Transparent)
        drawContent()
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .drawBackdrop(
                backdrop = panelBackdrop,
                shape = { RoundedCornerShape(cornerRadius) },
                effects = {
                    vibrancy()
                    blur(4f.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(surfaceColor.copy(alpha = 0.62f))
                }
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}
