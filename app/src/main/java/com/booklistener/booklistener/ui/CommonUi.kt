package com.booklistener.ui

import androidx.compose.ui.res.stringResource
import com.booklistener.R
import com.booklistener.ui.icons.AppIcons

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.booklistener.core.SavedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 真实封面（content:// SAF 或 file:// 缓存）优先，无则渐变占位（目录条目/管理页共用） */
@Composable
fun CoverImage(coverUri: String?, displayName: String, size: Dp) {
    val context = LocalContext.current
    var bitmap by remember(coverUri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(coverUri) {
        bitmap = if (coverUri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val uri = android.net.Uri.parse(coverUri)
                val bmp = if (uri.scheme == "file") {
                    BitmapFactory.decodeFile(uri.path)
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                bmp?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = stringResource(R.string.cd_album_cover),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        AlbumCover(displayName = displayName, size = size)
    }
}

/**
 * 已添加条目行共用模板（霰弹收敛 A2）：封面 56dp + 作者(可选) + 名称 + 副标题槽 + 来源标识 + 尾部槽。
 * 曲目页行 / 当前播放卡片 / 文件夹管理行共用；场景差异通过 titleStyle/subtitle/trailing 注入，视觉不变。
 */
@Composable
fun EntrySummaryRow(
    entry: SavedEntry,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CoverImage(coverUri = entry.coverUri, displayName = entry.displayName, size = 56.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            entry.artist?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = entry.displayName,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.invoke()
        }
        Icon(
            imageVector = if (entry.isLocal) AppIcons.Folder else AppIcons.Cloud,
            contentDescription = if (entry.isLocal) stringResource(R.string.cd_local) else stringResource(R.string.cd_cloud),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp)
        )
        trailing?.invoke()
    }
}
