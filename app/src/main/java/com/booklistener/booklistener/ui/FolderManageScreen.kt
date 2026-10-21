@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.booklistener.ui
import androidx.compose.ui.res.stringResource
import com.booklistener.R
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
            title = { Text(stringResource(R.string.folder_add_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.folder_add_desc),
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
                            Text(stringResource(R.string.folder_add_local), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(R.string.folder_add_local_desc),
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
                            Text(stringResource(R.string.folder_add_cloud), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(R.string.folder_add_cloud_desc),
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
                    Text(stringResource(R.string.nav_cancel))
                }
            }
        )
    }

    if (showCloudDialog) {
        val accounts by viewModel.webDavAccounts.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showCloudDialog = false },
            title = { Text(stringResource(R.string.folder_select_account)) },
            text = {
                if (accounts.isEmpty()) {
                    Text(stringResource(R.string.folder_no_account))
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
                    Text(stringResource(R.string.nav_cancel))
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
                        contentDescription = stringResource(R.string.nav_back),
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
                    Text(stringResource(R.string.folder_save))
                }
            }
            when {
                pickerLoading -> Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp).padding(horizontal = 16.dp)
                )
                pickerDirs.isEmpty() && pickerItems.isEmpty() -> Text(
                    text = stringResource(R.string.folder_empty),
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
            title = { Text(stringResource(R.string.folder_delete_title)) },
            text = { Text(stringResource(R.string.folder_delete_text, deleting.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearSavedEntry(deleting)
                    pendingDelete = null
                    selectedEntry = null
                }) {
                    Text(stringResource(R.string.nav_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.nav_cancel))
                }
            }
        )
    }

    // v1.6.3：刷新数据库二次确认
    val refreshing = pendingRefresh
    if (refreshing != null) {
        AlertDialog(
            onDismissRequest = { pendingRefresh = null },
            title = { Text(stringResource(MessageCatalog.MSG_REFRESH_CONFIRM_TITLE)) },
            text = { Text(stringResource(MessageCatalog.MSG_REFRESH_CONFIRM_TEXT)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.refreshEntry(refreshing)
                    pendingRefresh = null
                }) {
                    Text(stringResource(R.string.common_refresh))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRefresh = null }) {
                    Text(stringResource(R.string.nav_cancel))
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
                        contentDescription = stringResource(R.string.nav_back),
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
                        text = if (useRawName) stringResource(R.string.folder_mode_file_name)
                        else stringResource(R.string.folder_mode_tag),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = { pendingRefresh = opened }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh) + " " + opened.displayName)
                }
                IconButton(onClick = { pendingDelete = opened }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.nav_delete) + " " + opened.displayName)
                }
            }
            val files = manageFiles
            if (files == null) {
                Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp).padding(horizontal = 16.dp)
                )
            } else if (files.isEmpty()) {
                Text(
                    text = stringResource(MessageCatalog.EMPTY_DIR_AUDIO),
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                // v2.1.4：内容文件列表忠实显示"原始文件"——过滤虚拟轨行（rawName 形如 "整轨.cue#1"），
                // .cue 文件本身照常显示，虚拟轨仅在该 cue 行点击后的弹层中展示
                val sourceFiles = files.filterNot { com.booklistener.core.VirtualTrackUri.parse(it.uri) != null }
                LazyColumn {
                    items(sourceFiles, key = { it.uri }) { file ->
                        ManageFileRow(
                            file = file,
                            // v5.0：仅 .cue 行可点击（打开该 cue 的虚拟轨列表，纯浏览不播放）；常规行无点击
                            onClick = if (isCueFileRow(file)) {
                                { viewModel.openVirtualTracks(file.uri, file.rawName ?: file.displayName) }
                            } else null
                        )
                        // v2.0.12：文件列表行间格线（同设置页/账号列表风格）
                        HorizontalDivider()
                    }
                }
            }
        }
        return
    }

    // v5.0：内容文件列表 .cue 行 → 虚拟轨浏览弹层（数据源 = cue_sheets 关联作品的虚拟轨）
    val vts = viewModel.virtualTrackSheet.collectAsStateWithLifecycle().value
    if (vts != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissVirtualTracks() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = vts.cueName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                Text(
                    text = stringResource(MessageCatalog.CUE_VIRTUAL_TRACKS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
                if (vts.tracks.isEmpty()) {
                    Text(
                        text = stringResource(MessageCatalog.CUE_NO_TRACKS),
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 24.dp, end = 24.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn {
                        items(vts.tracks, key = { it.uri }) { track ->
                            VirtualTrackRow(track)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
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
                    contentDescription = stringResource(R.string.nav_back),
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = stringResource(R.string.folder_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.folder_add_title), modifier = Modifier.size(28.dp))
            }
        }
        if (savedEntries.isEmpty()) {
            Text(
                text = stringResource(MessageCatalog.EMPTY_MANAGE),
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
            showCover = false,
            subtitle = {
                Text(
                    text = when {
                        // v2.1.11：本地 SAF uri 显示可读路径（内部存储/...）
                        entry.isLocal ->
                            com.booklistener.core.folderReadablePath(android.net.Uri.parse(entry.uri))
                        // v1.6.30：网盘条目副标题显示文件夹路径（不再重复目录名/账号）
                        entry.sourceType == com.booklistener.core.SourceType.WEBDAV ->
                            runCatching { java.net.URI(entry.uri).path.orEmpty() }
                                .getOrDefault(entry.displayName)
                        else -> entry.displayName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailing = {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.nav_delete) + " " + entry.displayName)
                }
            }
        )
    }
}

/** .cue 文件行判定（rawName 或显示名以 .cue 结尾，大小写不敏感） */
private fun isCueFileRow(file: com.booklistener.core.IndexedFile): Boolean =
    (file.rawName ?: file.displayName).substringAfterLast('.', "").equals("cue", ignoreCase = true)

/** 管理文件行（v1.7.0）：文件名（含扩展名，优先原始文件名）+ 时长 */
@Composable
private fun ManageFileRow(
    file: com.booklistener.core.IndexedFile,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
            // v2.2.0：文件级错误信息直接显示在失败文件行
            if (file.error != null) {
                Text(
                    text = file.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // v2.2.0：文件行标注相对路径（子文件夹文件），与时长组合
                val sub = listOfNotNull(
                    file.relativePath?.takeIf { it.isNotBlank() },
                    file.durationMs?.let { formatDuration(it) }
                        ?: stringResource(R.string.common_unknown_duration)
                ).joinToString(" · ")
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (file.error != null) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = file.error,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(MessageCatalog.CUE_VIRTUAL_TRACKS),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 虚拟轨行（v5.0）：轨号 · 标题 + 表演者 · 时长（纯浏览） */
@Composable
private fun VirtualTrackRow(track: com.booklistener.core.IndexedFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = track.track?.let { "%02d".format(it) } ?: "--",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = track.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    track.artist?.takeIf { it.isNotBlank() },
                    track.durationMs?.let { formatDuration(it) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
