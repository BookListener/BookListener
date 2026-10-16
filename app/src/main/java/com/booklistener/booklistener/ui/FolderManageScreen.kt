@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.booklistener.ui
import com.booklistener.ui.icons.AppIcons


import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
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
    // v1.6.28：网盘文件夹选择器（null = 账号列表阶段，非 null = 该账号目录树）
    var pickerCred by remember { mutableStateOf<com.booklistener.source.WebDavCredential?>(null) }
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
            text = {
                Column {
                    Text(
                        text = "选择要添加的内容来源，添加后即可在曲目中浏览播放",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                    // 添加本地文件夹：整行图标 + 标题 + 说明，竖向排列
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddDialog = false
                                openFolderLauncher.launch(null)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.Folder,
                            contentDescription = null
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("添加本地文件夹", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "从手机存储中选择音频所在目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                    // 添加网盘：整行图标 + 标题 + 说明，竖向排列
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddDialog = false
                                showCloudDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.Cloud,
                            contentDescription = null
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("添加网盘目录", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "连接 WebDAV 网盘，浏览并添加音频目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCloudDialog) {
        val accounts by viewModel.webDavAccounts.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showCloudDialog = false },
            title = { Text("选择网盘账号") },
            text = {
                if (accounts.isEmpty()) {
                    Text("尚未配置 WebDAV 账号，请先到 设置 → 网盘配置 中添加")
                } else {
                    Column {
                        accounts.forEach { cred ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showCloudDialog = false
                                        // v1.6.28：不直接固化根条目，进入目录树选择具体文件夹
                                        pickerCred = cred
                                        viewModel.openWebDavPicker(cred)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cred.host,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = cred.username,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCloudDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ---- v1.6.28：网盘文件夹选择器（目录树逐层浏览，保存当前目录为固化条目）----
    val picker = pickerCred
    if (picker != null) {
        val pickerUrl by viewModel.pickerUrl.collectAsStateWithLifecycle()
        val pickerStack by viewModel.pickerStack.collectAsStateWithLifecycle()
        val pickerDirs by viewModel.pickerDirs.collectAsStateWithLifecycle()
        val pickerItems by viewModel.pickerItems.collectAsStateWithLifecycle()
        val pickerLoading by viewModel.pickerLoading.collectAsStateWithLifecycle()
        BackHandler(onBack = {
            if (pickerStack.isNotEmpty()) viewModel.backPickerDir() else {
                viewModel.closePicker()
                pickerCred = null
            }
        })
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (pickerStack.isNotEmpty()) viewModel.backPickerDir() else {
                        viewModel.closePicker()
                        pickerCred = null
                    }
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = pickerUrl?.let {
                        android.net.Uri.decode(it.trimEnd('/').substringAfterLast('/'))
                    } ?: picker.host,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    viewModel.savePickerFolder(picker)
                    // v1.6.28：保存后立即关闭选择器（否则停留在"该目录为空"的空状态）
                    pickerCred = null
                }) {
                    Text("保存此文件夹")
                }
            }
            when {
                pickerLoading -> Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp).padding(horizontal = 16.dp)
                )
                pickerDirs.isEmpty() && pickerItems.isEmpty() -> Text(
                    text = "该目录为空\n请返回上级目录或另选目录",
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                else -> LazyColumn {
                    items(pickerDirs, key = { it.href }) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.enterPickerDir(dir.href) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = AppIcons.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = dir.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 12.dp)
                            )
                        }
                        HorizontalDivider()
                    }
                    items(pickerItems, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = AppIcons.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 10.dp)
                            )
                            Text(
                                text = formatBytes(item.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        return
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
                    style = MaterialTheme.typography.titleMedium,
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
                    text = MessageCatalog.EMPTY_DIR_AUDIO,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn {
                    items(files, key = { it.uri }) { file ->
                        ManageFileRow(file)
                        // v2.0.12：文件列表行间格线（同设置页/账号列表风格）
                        HorizontalDivider()
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
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加内容", modifier = Modifier.size(28.dp))
            }
        }
        if (savedEntries.isEmpty()) {
            Text(
                text = MessageCatalog.EMPTY_MANAGE,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            LazyColumn {
                items(savedEntries, key = { it.id }) { entry ->
                    FolderManageRow(
                        entry = entry,
                        onOpen = { selectedEntry = entry },
                        onDelete = { pendingDelete = entry }
                    )
                    // v2.0.12：管理列表行间格线（同设置页/账号列表风格）
                    HorizontalDivider()
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
                    text = when {
                        entry.isLocal -> entry.uri
                        // v1.6.30：网盘条目副标题显示文件夹路径（不再重复目录名/账号）
                        entry.sourceType == com.booklistener.core.SourceType.WEBDAV ->
                            runCatching { java.net.URI(entry.uri).path.orEmpty() }
                                .getOrDefault(entry.displayName)
                        else -> entry.displayName
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

/** 字节数友好格式化（网盘选择器音频行显示大小用） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
