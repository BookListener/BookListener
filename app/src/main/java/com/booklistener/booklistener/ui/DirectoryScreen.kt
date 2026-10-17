@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.booklistener.ui
import androidx.compose.ui.res.stringResource
import com.booklistener.R
import com.booklistener.ui.icons.AppIcons


import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.booklistener.core.AlbumSummary
import com.booklistener.core.AudioItemFactory
import com.booklistener.core.MessageCatalog
import com.booklistener.core.AudioItem
import com.booklistener.core.IndexedFile
import com.booklistener.core.SavedEntry
import com.booklistener.core.SearchHit
import com.booklistener.core.SourceType
import com.booklistener.core.formatDuration
import com.booklistener.core.splitHomeSections
import com.booklistener.player.PlaybackQueueLogic
import com.booklistener.player.PlaybackState
import com.booklistener.source.WebDavDirEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * 曲目浏览页（v1.5.0）：顶部搜索框（搜已添加内容）+ 主界面文件夹层两区
 * （最近播放 = 所有已播放过的文件夹按新→旧 / 尚未播放）。
 * 本地/网盘已添加内容合并展示，条目带来源小标识与封面。
 */
@Composable
fun DirectoryScreen(viewModel: PlayerViewModel) {
    val inFolderView by viewModel.inFolderView.collectAsStateWithLifecycle()
    val folderItems by viewModel.folderItems.collectAsStateWithLifecycle()
    // v1.6.27：WebDAV 目录导航状态（子目录列表 + 路径栈）
    val webDavDirs by viewModel.webDavDirs.collectAsStateWithLifecycle()
    val webDavPathStack by viewModel.webDavPathStack.collectAsStateWithLifecycle()

    // v1.6.4：长按条目查看完整名称/信息
    var pendingNameDetail by remember { mutableStateOf<NameDetail?>(null) }
    val nameDetail = pendingNameDetail
    if (nameDetail != null) {
        NameDetailSheet(detail = nameDetail, onDismiss = { pendingNameDetail = null })
    }
    val currentFolderUri by viewModel.currentFolderUri.collectAsStateWithLifecycle()
    val savedEntries by viewModel.savedEntries.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val recentAlbums by viewModel.recentAlbums.collectAsStateWithLifecycle()
    val neverAlbums by viewModel.neverAlbums.collectAsStateWithLifecycle()
    val currentAlbum by viewModel.currentAlbum.collectAsStateWithLifecycle()
    val albumSongs by viewModel.albumSongs.collectAsStateWithLifecycle()
    val albumSongsEnded by viewModel.albumSongsEnded.collectAsStateWithLifecycle()
    val albumsEnded by viewModel.albumsEnded.collectAsStateWithLifecycle()

    // v1.7.0：进入曲目界面刷新专辑分区（db 加载）；扫描完成/增删条目后随 savedEntries 变化补刷
    LaunchedEffect(Unit) { viewModel.refreshAlbums() }
    LaunchedEffect(savedEntries.size) { viewModel.refreshAlbums() }

    // 通知权限（Android 13+），首次进入请求；本地媒体库已不使用，无需 READ_MEDIA_AUDIO
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it } }
    if (!granted && android.os.Build.VERSION.SDK_INT >= 33) {
        LaunchedEffect(Unit) {
            launcher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val albumView = currentAlbum
        if (inFolderView && albumView != null) {
            // 专辑视图（v1.7.0）：标题=专辑名，歌曲列表从 db 分页加载（不重扫）
            BackHandler(onBack = { viewModel.exitAlbumView() })
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.exitAlbumView() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                MarqueeText(
                    text = albumView.album,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            if (albumSongs.isEmpty()) {
                Text(
                    text = stringResource(MessageCatalog.EMPTY_DIR_AUDIO),
                    modifier = Modifier.padding(top = 48.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val songListState = rememberLazyListState()
                val useRawName by viewModel.albumTitleMode.collectAsStateWithLifecycle()
                // v1.6.9：与条目视图共用同一段定位代码（rememberScrollToPlaying）——
                // 进入专辑视图时正在播放条目置顶；首帧条目在深分页（playingIndex=-1）时
                // 随分页增长补定位，已到底/深分页也能置顶
                val playingIndex = albumSongs.indexOfFirst {
                    PlaybackQueueLogic.isCurrentItem(playbackState, it.uri)
                }
                rememberScrollToPlaying(songListState, playingIndex, albumSongs.size, albumView.entryId)
                LazyColumn(state = songListState) {
                    itemsIndexed(albumSongs, key = { _, file -> file.uri }) { index, file ->
                        AlbumSongRow(
                            file = file,
                            useRawName = useRawName,
                            isPlayingCurrent = PlaybackQueueLogic.isCurrentItem(playbackState, file.uri),
                            onClick = { viewModel.playAlbumFrom(albumView, index) },
                            onLongClick = {
                                pendingNameDetail = NameDetail(
                                    primary = if (useRawName) file.rawName ?: file.displayName else file.displayName,
                                    secondary = file.artist,
                                    meta = file.durationMs?.let { formatDuration(it) }
                                )
                            }
                        )
                        // v2.0.1：专辑视图行间分隔线（用户反馈重新进入专辑列表格线缺失，
                        // 与主页文件夹列表分隔线样式统一——所有列表格线一致）
                        if (index < albumSongs.size - 1) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 10.dp))
                        }
                    }
                    if (!albumSongsEnded) {
                        item(key = "album_songs_loading") {
                            Text(
                                text = stringResource(R.string.dir_loading_more),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            )
                        }
                    }
                }
                // 滑底加载下一页
                LaunchedEffect(songListState, albumSongs.size) {
                    snapshotFlow {
                        val last = songListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last to songListState.layoutInfo.totalItemsCount
                    }.distinctUntilChanged().collect { (last, total) ->
                        if (total > 0 && last >= total - 4) viewModel.loadAlbumSongsMore()
                    }
                }
            }
            return
        }

        if (inFolderView) {
            // 条目视图（兼容路径：搜索命中文件夹 / 旧入口）：目录名 + 返回箭头 + 目录内可播放音频列表
            // v1.6.27：WebDAV 目录导航——子目录行可进入，返回键逐级退回（根目录时退出条目视图）
            val isWebDav = currentFolderUri?.toString()?.startsWith("webdav://") == true
            val isWebDavSubDir = isWebDav && webDavPathStack.isNotEmpty()
            BackHandler(onBack = { viewModel.backWebDavDirOrExit() })
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.backWebDavDirOrExit() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = when {
                        // 当前 WebDAV 子目录：显示目录名（栈顶 href 末段解码）
                        isWebDavSubDir -> android.net.Uri.decode(
                            webDavPathStack.last().trimEnd('/').substringAfterLast('/')
                        )
                        else -> currentFolderUri?.let { entryDisplayName(it.toString(), savedEntries) } ?: stringResource(R.string.dir_folder)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (webDavDirs.isEmpty() && folderItems.isEmpty()) {
                Text(
                    text = stringResource(MessageCatalog.EMPTY_DIR_AUDIO),
                    modifier = Modifier.padding(top = 48.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // v1.6.9：杀后台恢复/进入条目视图时，正在播放条目置顶——
                // 与专辑视图共用同一段定位代码（rememberScrollToPlaying）；
                // 正在播放条目在列表底部（长列表）也能定位置顶
                val folderListState = rememberLazyListState()
                val folderPlayingIndex = folderItems.indexOfFirst {
                    PlaybackQueueLogic.isCurrentItem(playbackState, it.id)
                }
                rememberScrollToPlaying(
                    listState = folderListState,
                    playingIndex = folderPlayingIndex,
                    itemCount = folderItems.size,
                    sessionKey = currentFolderUri?.toString() ?: ""
                )
                LazyColumn(state = folderListState) {
                    if (isWebDav) {
                        items(webDavDirs, key = { dir -> dir.href }) { dir ->
                            WebDavDirRow(dir = dir, onClick = { viewModel.enterWebDavDir(dir.href) })
                        }
                    }
                    itemsIndexed(folderItems, key = { _, item -> item.id }) { index, item ->
                        AudioRow(
                            item = item,
                            isPlayingCurrent = PlaybackQueueLogic.isCurrentItem(playbackState, item.id),
                            onClick = { viewModel.playItems(folderItems, index) },
                            onLongClick = {
                                pendingNameDetail = NameDetail(
                                    primary = item.displayName,
                                    meta = item.size.formatSize()
                                )
                            }
                        )
                        // v2.0.1：主页文件列表行间分隔线（用户反馈表格线缺失）
                        if (index < folderItems.size - 1) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 10.dp))
                        }
                    }
                }
            }
            return
        }

        // 顶部搜索框（替代原三标签，配色调淡）
        // v1.6.20：半透明圆角毛玻璃风格（头条式）。不接入 backdrop——
        // 搜索框位于主页 backdrop 捕捉层内部（layerBackdrop 挂 Scaffold），drawBackdrop 采样自身所在层
        // 形成渲染自引用，真机闪退（v1.6.17 挂 TextField / v1.6.19 挂外层 Box 两次踩坑，此为根治）。
        // v2.0.5b：聚焦态 0.40→0.30（透出 70%，与主玻璃统一）；未聚焦 0.28 保持（更透）。
        OutlinedTextField(
            value = searchQuery,
            onValueChange = viewModel::onSearchQueryChange,
            placeholder = { Text(stringResource(R.string.dir_search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                focusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                cursorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        if (searchQuery.isNotBlank()) {
            // v2.0.1：搜索态 = 专辑命中 + 歌曲命中（面向数据库，不再命中文件夹）
            if (searchResults.isEmpty()) {
                Text(
                    text = stringResource(MessageCatalog.EMPTY_SEARCH),
                    modifier = Modifier.padding(top = 48.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(searchResults, key = { hit -> hitKey(hit) }) { hit ->
                        when (hit) {
                            is SearchHit.Album -> {
                                val albumMeta = stringResource(
                                    R.string.dir_song_count,
                                    hit.album.songCount,
                                    formatDuration(hit.album.totalDurationMs)
                                )
                                AlbumRow(
                                    album = hit.album,
                                    entry = savedEntries.firstOrNull { it.id == hit.album.entryId },
                                    onClick = { viewModel.openAlbum(hit.album) },
                                    onLongClick = {
                                        pendingNameDetail = NameDetail(
                                            primary = hit.album.album,
                                            secondary = hit.album.artist,
                                            meta = albumMeta
                                        )
                                    },
                                    showDivider = false
                                )
                            }
                            is SearchHit.File -> {
                                val srcLabel = if (hit.sourceType == SourceType.LOCAL) {
                                    stringResource(R.string.dir_source_local)
                                } else {
                                    stringResource(R.string.dir_source_cloud)
                                }
                                FileResultRow(
                                    displayName = hit.file.displayName,
                                    sourceType = hit.sourceType,
                                    onClick = { viewModel.playItems(listOf(fileItem(hit)), 0) },
                                    onLongClick = {
                                        pendingNameDetail = NameDetail(
                                            primary = hit.file.displayName,
                                            secondary = srcLabel
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
            return
        }

        // 主界面专辑层两区（v1.7.0：按专辑聚合，db 分页加载；分区沿用条目级 lastPlayedAt）
        if (savedEntries.isEmpty() && recentAlbums.isEmpty() && neverAlbums.isEmpty()) {
            Text(
                text = stringResource(MessageCatalog.EMPTY_HOME),
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            return
        }
        val listState = rememberLazyListState()
        LazyColumn(state = listState) {
            if (recentAlbums.isNotEmpty()) {
                item(key = "header_recent") { SectionHeader(stringResource(R.string.dir_recent)) }
                recentAlbums.forEachIndexed { i, album ->
                    item(key = "album_${album.entryId}_${album.album}") {
                        val albumMeta = stringResource(
                            R.string.dir_song_count, album.songCount, formatDuration(album.totalDurationMs)
                        )
                        val entry = savedEntries.firstOrNull { it.id == album.entryId }
                        if (entry != null && isCurrentEntry(entry, currentFolderUri, playbackState)) {
                            CurrentAlbumCard(
                                album = album,
                                entry = entry,
                                state = playbackState,
                                onToggle = viewModel::togglePlayPause,
                                onClick = { viewModel.openAlbum(album) }
                            )
                        } else {
                            AlbumRow(
                                album = album,
                                entry = entry,
                                onClick = { viewModel.openAlbum(album) },
                                onLongClick = {
                                    pendingNameDetail = NameDetail(
                                        primary = album.album,
                                        secondary = album.artist,
                                        meta = albumMeta
                                    )
                                },
                                // v2.0.1：最近播放最后一个不要行间细线（其后紧接分区线+「尚未播放」标题）
                                showDivider = i < recentAlbums.size - 1
                            )
                        }
                    }
                }
                item(key = "divider") { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            }
            item(key = "header_not") { SectionHeader(stringResource(R.string.dir_not_played)) }
            neverAlbums.forEach { album ->
                item(key = "album_${album.entryId}_${album.album}") {
                    val albumMeta = stringResource(
                        R.string.dir_song_count, album.songCount, formatDuration(album.totalDurationMs)
                    )
                    AlbumRow(
                        album = album,
                        entry = savedEntries.firstOrNull { it.id == album.entryId },
                        onClick = { viewModel.openAlbum(album) },
                        onLongClick = {
                            pendingNameDetail = NameDetail(
                                primary = album.album,
                                secondary = album.artist,
                                meta = albumMeta
                            )
                        }
                    )
                }
            }
            if (!albumsEnded) {
                item(key = "albums_loading") {
                    Text(
                        text = stringResource(R.string.dir_loading_more),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    )
                }
            }
        }
        // 滑底翻页：两区共用同一 LazyColumn，触底加载下一页
        LaunchedEffect(listState, recentAlbums.size, neverAlbums.size) {
            snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last to listState.layoutInfo.totalItemsCount
            }.distinctUntilChanged().collect { (last, total) ->
                if (total > 0 && last >= total - 4) viewModel.loadAlbumsMore()
            }
        }
    }
}

private fun entryDisplayName(id: String, entries: List<SavedEntry>): String =
    entries.find { it.id == id }?.displayName
        ?: id.substringAfterLast('/').substringAfterLast(':')

private fun isCurrentEntry(entry: SavedEntry, currentUri: android.net.Uri?, state: PlaybackState): Boolean =
    state.currentItem != null && currentUri?.toString() == entry.id

private fun hitKey(hit: SearchHit): String = when (hit) {
    is SearchHit.Album -> "album_${hit.album.entryId}_${hit.album.album}"
    is SearchHit.File -> "file_${hit.entry.id}_${hit.file.uri}"
}

/** 搜索结果中的文件条目 → 可直接播放的 AudioItem（仅路径/来源，无额外元数据） */
private fun fileItem(hit: SearchHit.File): AudioItem =
    AudioItemFactory.fromSearch(
        sourceType = hit.sourceType,
        displayName = hit.file.displayName,
        uri = hit.file.uri
    )

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/** 当前播放条目卡片：封面 + 目录名 + 当前曲目 + 进度条 + 播放/暂停 */
@Composable
private fun CurrentEntryCard(
    entry: SavedEntry,
    state: PlaybackState,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val current = state.currentItem ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        EntrySummaryRow(
            entry = entry,
            titleStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            subtitle = {
                Text(
                    text = "${current.displayName}  ${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailing = {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (state.isPlaying) AppIcons.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (state.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play)
                    )
                }
            }
        )
        LinearProgressIndicator(
            progress = {
                if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                } else 0f
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
    }
}

/** 搜索到的文件结果行 */
@Composable
private fun FileResultRow(
    displayName: String,
    sourceType: SourceType,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = AppIcons.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Icon(
            imageVector = if (sourceType == SourceType.LOCAL) AppIcons.Folder else AppIcons.Cloud,
            contentDescription = if (sourceType == SourceType.LOCAL) stringResource(R.string.cd_local) else stringResource(R.string.cd_cloud),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
    }
}

/** 专辑行（v1.7.0）：封面（来源条目）+ 作者 + 专辑名 + 曲数·总时长 + 来源标识 */
@Composable
private fun AlbumRow(
    album: AlbumSummary,
    entry: SavedEntry?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showDivider: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            coverUri = if (album.albumCountInEntry <= 1) entry?.coverUri else null,
            displayName = album.album,
            size = 56.dp
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            album.artist?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = album.album,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.dir_song_count, album.songCount, formatDuration(album.totalDurationMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // v1.6.5：来源图标调小（16dp），与时长同行右对齐
                Icon(
                    imageVector = if (entry?.isLocal != false) AppIcons.Folder else AppIcons.Cloud,
                    contentDescription = if (entry?.isLocal != false) stringResource(R.string.cd_local) else stringResource(R.string.cd_cloud),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp).padding(start = 6.dp)
                )
            }
        }
    }
    // v1.6.8：专辑行细行线——明显弱于「最近播放/尚未播放」分区之间的分隔（更淡更细、仅从封面右侧起，缩进不越界）
    // v2.0.1：最近播放最后一个不显示（showDivider=false），其后紧接分区线+「尚未播放」标题，避免细线粗线叠加
    if (showDivider) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 68.dp, top = 6.dp)
        )
    }
}

/** 当前播放专辑大卡（v1.7.0）：封面 + 专辑名 + 当前曲目 + 进度 + 播放/暂停 */
@Composable
private fun CurrentAlbumCard(
    album: AlbumSummary,
    entry: SavedEntry,
    state: PlaybackState,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val current = state.currentItem ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                coverUri = album.coverUri ?: if (album.albumCountInEntry <= 1) entry.coverUri else null,
                displayName = album.album,
                size = 56.dp
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                album.artist?.let { artist ->
                    MarqueeText(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                MarqueeText(
                    text = album.album,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${current.displayName}  ${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (state.isPlaying) AppIcons.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (state.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play)
                )
            }
        }
        LinearProgressIndicator(
            progress = {
                if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                } else 0f
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
    }
}

/** 专辑歌曲行（v1.7.0）：音轨号/序号 · 标题 + 作者 + 时长；正在播放高亮 */
@Composable
private fun AlbumSongRow(
    file: IndexedFile,
    useRawName: Boolean,
    isPlayingCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (isPlayingCurrent) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = file.track?.toString() ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPlayingCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            FileNameText(
                name = if (useRawName) file.rawName ?: file.displayName else file.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlayingCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = listOfNotNull(
                    file.artist,
                    file.durationMs?.let { formatDuration(it) }
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = if (isPlayingCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isPlayingCurrent) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
/** WebDAV 子目录行（v1.6.27 目录导航）：文件夹图标 + 目录名，点击进入下一层 */
private fun WebDavDirRow(dir: WebDavDirEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = AppIcons.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = dir.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AudioRow(item: AudioItem, isPlayingCurrent: Boolean, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (isPlayingCurrent) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isPlayingCurrent) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlayingCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.size.formatSize(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isPlayingCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 底部常驻播放条：曲目 + 进度 + 上一曲/播放暂停/下一曲 */
@Composable
fun NowPlayingBar(
    state: PlaybackState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val current = state.currentItem ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(AppIcons.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (state.isPlaying) AppIcons.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play"
                )
            }
            IconButton(onClick = onNext) {
                Icon(AppIcons.SkipNext, contentDescription = "Next")
            }
        }
        if (state.isBuffering) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

private fun Long.formatSize(): String {
    return when {
        this >= 1024 * 1024 * 1024 -> "%.1f GB".format(this / 1024.0 / 1024 / 1024)
        this >= 1024 * 1024 -> "%.1f MB".format(this / 1024.0 / 1024)
        this >= 1024 -> "%.1f KB".format(this / 1024.0)
        else -> "$this B"
    }
}

/**
 * 列表进入视图时定位到正在播放条目（置顶）——专辑视图与条目视图共用同一段定位代码。
 * - sessionKey：视图进入会话标识（专辑 entryId / 条目 uri），组合重建即重新定位（杀后台恢复进入也覆盖）；
 * - itemCount/playingIndex：随分页增长重试——首帧条目在深分页（playingIndex=-1）时先不定位，
 *   分页加载后再补定位，保证"已到底/深分页"也能置顶；
 * - 定位成功后本次会话不再强制跳转（用户后续滚动/切歌不打断）。
 */
@Composable
fun rememberScrollToPlaying(
    listState: LazyListState,
    playingIndex: Int,
    itemCount: Int,
    sessionKey: Any
) {
    var positionedForEntry by remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(sessionKey, itemCount, playingIndex) {
        if (!positionedForEntry && playingIndex in 0 until itemCount) {
            listState.scrollToItem(playingIndex)
            positionedForEntry = true
        }
    }
}
