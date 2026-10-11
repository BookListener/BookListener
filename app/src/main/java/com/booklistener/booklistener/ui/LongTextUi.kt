package com.booklistener.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 超长文本处理（v1.6.4 方案 B 落地）：
 * - 列表条目：静态单行 + 智能截断（文件名保留扩展名）+ 长按查看全名；
 * - 焦点位置：继续用 MarqueeText 滚动（不在此文件重复实现）。
 */

/** 提取文件扩展名：最后一个 '.' 后 1~6 位字母/数字且点号前有内容；无则 null。纯逻辑可单测。 */
internal fun fileExtension(name: String): String? {
    val idx = name.lastIndexOf('.')
    if (idx <= 0 || idx == name.length - 1) return null
    val ext = name.substring(idx + 1)
    return ext.takeIf { it.length in 1..6 && it.all { c -> c.isLetterOrDigit() } }
}

/**
 * 文件名显示：超长时主名省略号截断，扩展名（.mp3/.flac…）固定在行尾永不丢失；
 * 无扩展名时退化为普通单行省略。行尾图标由外层 Row 固定宽度保证不被挤压。
 */
@Composable
fun FileNameText(
    name: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val ext = fileExtension(name)
    if (ext == null) {
        Text(
            text = name,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name.removeSuffix(".$ext"),
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = ".$ext",
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** 长按查看全名的弹层内容模型 */
data class NameDetail(
    val primary: String,
    val secondary: String? = null,
    val meta: String? = null
)

/** 长按条目后弹出的完整信息：主名可滚动显示全文，次级信息多行可折行 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NameDetailSheet(detail: NameDetail, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MarqueeText(
                text = detail.primary,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            detail.secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            detail.meta?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
