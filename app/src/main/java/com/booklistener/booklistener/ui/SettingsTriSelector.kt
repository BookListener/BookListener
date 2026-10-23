package com.booklistener.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 三元选择器（v2.1.20 设置页卡片风格）。
 *
 * 三个选项横向均匀分布，每项 = 上方细线图标 + 下方文字；选中项主题色图标/文字 + 细短下划线，
 * 未选中项深灰。无卡片、纯底，用于外观模式（白天/黑夜/自动）等三态配置。
 */
data class TriOption(
    val icon: ImageVector,
    val label: String
)

@Composable
fun SettingsTriSelector(
    options: List<TriOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = option.label,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (selected) {
                    Row(
                        modifier = Modifier
                            .width(24.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {}
                } else {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}
