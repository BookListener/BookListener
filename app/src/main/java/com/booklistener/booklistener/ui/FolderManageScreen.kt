@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.booklistener.ui
import com.booklistener.ui.icons.AppIcons


import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.booklistener.core.MessageCatalog
import com.booklistener.core.SavedEntry
import com.booklistener.core.SourceType
import com.booklistener.core.formatDuration

/**
 * 文件夹管理页（v1.5.1）：列举所有已添加内容（本地文件夹 + 网盘源），
 * 每条目提供删除按钮；右上角"+"弹出添加方式（添加文件夹 / 添加网盘）。
 */
@Composable
fun FolderManageScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val savedEntries by viewModel.savedEntries.collectAsStateWithLifecycle()
    val manageFiles by viewModel.manageFiles.collectAsStateWithLifecycle()
    val useRawName by viewModel.albumTitleMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRefresh by remember { mutableStateOf<SavedEntry?>(null) }
    var showCloudDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SavedEntry?>(null) }
    var selectedEntry by remember { mutableStateOf<SavedEntry?>(null) }
    var pendingNameDetail by remember { mutableStateOf<NameDetail?>(null) }
    val nameDetail = pendingNameDetail
    if (nameDetail != null) {
        NameDetailSheet(detail = nameDetail, onDismiss = { pendingNameDetail = null })
    }

    // SAF 选择本地音频文件夹并固化
    val openFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.scanFolder(treeUri)
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加内容") },
            text = { Text("选择添加方式") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showAddDialog = false
                        openFolderLauncher.launch(null)
                    }) {
                        Text("添加文件夹")
                    }
                    TextButton(onClick = {
                        showAddDialog = false
                        showCloudDialog = true
                    }) {
                        Text("添加网盘")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCloudDialog) {
        AlertDialog(
            onDismissRequest = { showCloudDialog = false },
            title = { Text("添加 WebDAV 内容") },
            text = { Text("将 WebDAV 根目录加入曲目列表（需先在设置中完成 WebDAV 配置）") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addCloudSource(SourceType.WEBDAV)
                    showCloudDialog = false
                }) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除确认") },
            text = { Text("确定删除「${deleting.displayName}」？\n将同时移除播放进度与已缓存文件清单。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearSavedEntry(deleting)
                    pendingDelete = null
                    selectedEntry = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // v1.6.3：刷新数据库二次确认
    val refreshing = pendingRefresh
    if (refreshing != null) {
        AlertDialog(
            onDismissRequest = { pendingRefresh = null },
            title = { Text(MessageCatalog.MSG_REFRESH_CONFIRM_TITLE) },
            text = { Text(MessageCatalog.MSG_REFRESH_CONFIRM_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.refreshEntry(refreshing)
                    pendingRefresh = null
                }) {
                    Text("刷新")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRefresh = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 文件列表子视图：显示文件名（含扩展名）+ 时长，db 加载
    val opened = selectedEntry
    if (opened != null) {
        LaunchedEffect(opened.id) { viewModel.loadManageFiles(opened.id) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    viewModel.clearManageFiles()
                    selectedEntry = null
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = opened.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // v1.6.3：切换专辑内标题显示（标签标题 ↔ 原始文件名）+ 刷新数据库（二次确认）
                TextButton(onClick = { viewModel.setAlbumTitleMode(!useRawName) }) {
                    Text(
                        text = if (useRawName) "文件名" else "标签",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = { pendingRefresh = opened }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新 ${opened.displayName}")
                }
                IconButton(onClick = { pendingDelete = opened }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除 ${opened.displayName}")
                }
            }
            val files = manageFiles
            if (files == null) {
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp).padding(horizontal = 16.dp)
                )
            } else if (files.isEmpty()) {
                Text(
                    text = "目录内未发现可播放音频",
                    modifier = Modifier.padding(top = 48.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(files, key = { it.uri }) { file ->
                        ManageFileRow(file)
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 顶部：返回箭头 + 标题 + 右上角添加按钮
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = "文件夹管理",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加内容", modifier = Modifier.size(28.dp))
            }
        }
        if (savedEntries.isEmpty()) {
            Text(
                text = MessageCatalog.EMPTY_MANAGE,
                modifier = Modifier.padding(top = 48.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(savedEntries, key = { it.id }) { entry ->
                    FolderManageRow(
                        entry = entry,
                        onOpen = { selectedEntry = entry },
                        onDelete = { pendingDelete = entry }
                    )
                }
            }
        }
    }
}

/**
 * 管理条目行（v1.7.0）：封面 + 名称 + 完整路径（本地 SAF uri / 网盘名）+ 曲目数，
 * 点击进入文件列表；删除按钮需二次确认。
 */
@Composable
private fun FolderManageRow(
    entry: SavedEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EntrySummaryRow(
            entry = entry,
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            subtitle = {
                Text(
                    text = if (entry.isLocal) {
                        entry.uri
                    } else {
                        entry.displayName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailing = {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除 ${entry.displayName}")
                }
            }
        )
    }
}

/** 管理文件行（v1.7.0）：文件名（含扩展名，优先原始文件名）+ 时长 */
@Composable
private fun ManageFileRow(file: com.booklistener.core.IndexedFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = AppIcons.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = file.rawName ?: file.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = file.durationMs?.let { formatDuration(it) } ?: "时长未知",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
