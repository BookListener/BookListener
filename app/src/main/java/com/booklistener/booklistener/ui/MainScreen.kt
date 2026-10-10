package com.booklistener.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.booklistener.player.PlaybackController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 主界面：底部导航（曲目 / 播放封面 / 设置）。
 * 中间封面按钮向上弹出播放详情（PlayerDetailSheet）；
 * 网盘配置已并入设置页。
 */
@Composable
fun MainScreen(
    playerViewModel: PlayerViewModel = viewModel(),
    cloudConfigViewModel: CloudConfigViewModel = viewModel()
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showPlayerSheet by rememberSaveable { mutableStateOf(false) }

    // 必须用 collect 订阅 StateFlow：直接读 .value 不会触发 Compose 重组，
    // 会导致"能播放但界面不显示"（本 bug 由 UT 覆盖回归）
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    val inFolderView by playerViewModel.inFolderView.collectAsStateWithLifecycle()
    // 切换文件夹后就绪 → 自动弹出播放详情（但不自动播放）
    val requestPlayerSheet by playerViewModel.requestPlayerSheet.collectAsStateWithLifecycle()
    LaunchedEffect(requestPlayerSheet) {
        if (requestPlayerSheet) {
            showPlayerSheet = true
            playerViewModel.consumePlayerSheetRequest()
        }
    }

    if (showPlayerSheet) {
        PlayerDetailSheet(
            viewModel = playerViewModel,
            state = playbackState,
            entry = PlaybackController.currentQueueOwnerId?.let { playerViewModel.savedEntryById(it) },
            albumCoverUri = playerViewModel.currentAlbum.value?.coverUri,
            onDismiss = { showPlayerSheet = false }
        )
    }

    // 设置 tab 顶层：系统返回键回到曲目页（子页内部返回由各 Screen 内层 BackHandler 优先处理）
    BackHandler(enabled = tabIndex == 2) { tabIndex = 0 }

    Scaffold(
        bottomBar = {
            // 底部导航背景跟随主题背景色（深色模式与页面融为一体，无突兀色块）
            // v1.6.8：高度 96dp 容纳增大的 84dp 中间播放封面
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.height(96.dp)
            ) {
                NavigationBarItem(
                    selected = tabIndex == 0,
                    onClick = {
                        // 在条目视图内点击"曲目"tab：先退回文件夹列表
                        if (tabIndex == 0 && inFolderView) {
                            playerViewModel.exitFolderView()
                        } else {
                            tabIndex = 0
                        }
                    },
                    icon = { Icon(Icons.Outlined.MusicNote, contentDescription = "曲目") },
                    label = { Text("曲目") }
                )
                // 中间：当前播放封面按钮（v1.6.8：84dp 圆形，播放中缓慢旋转；暂停显示半透明播放按钮），
                // 点击弹出播放详情并后台切到所属文件夹；若处于暂停则同时恢复播放
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            // 播放框弹出的同时，后台切换到当前播放队列所属文件夹的条目视图
                            val ownerId = PlaybackController.currentQueueOwnerId
                            val entry = ownerId?.let { playerViewModel.savedEntryById(it) }
                            if (entry != null) {
                                tabIndex = 0
                                val alreadyIn = playerViewModel.inFolderView.value &&
                                    playerViewModel.currentFolderUri.value?.toString() == ownerId
                                if (!alreadyIn) {
                                    playerViewModel.openEntryAlbum(entry)
                                }
                            }
                            // 暂停状态点击：弹出详情的同时恢复播放
                            if (!playbackState.isPlaying && playbackState.currentItem != null) {
                                playerViewModel.togglePlayPause()
                            }
                            showPlayerSheet = true
                        }
                        .padding(vertical = 8.dp)
                ) {
                    // 底部"正在播放"封面：播放上下文专辑封面优先（单专辑文件夹图/多专辑内嵌），无则渐变占位
                    val ownerEntry = PlaybackController.currentQueueOwnerId
                        ?.let { playerViewModel.savedEntryById(it) }
                    val coverUri = playerViewModel.currentAlbum.value?.coverUri ?: ownerEntry?.coverUri
                    val displayName = playbackState.currentItem?.displayName
                        ?: ownerEntry?.displayName ?: ""
                    // v1.6.8：正在播放时封面缓慢旋转（8s/圈，线性）；暂停时不转
                    val rotation = rememberInfiniteTransition(label = "cover_spin")
                        .animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(8000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "cover_angle"
                        )
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .then(
                                    if (playbackState.isPlaying) Modifier.rotate(rotation.value)
                                    else Modifier
                                )
                        ) {
                            if (coverUri != null) {
                                CoverImage(
                                    coverUri = coverUri,
                                    displayName = displayName,
                                    size = 84.dp
                                )
                            } else {
                                AlbumCover(
                                    displayName = displayName,
                                    size = 84.dp
                                )
                            }
                        }
                        // v1.6.8：暂停时叠加半透明播放按钮（仅真实播放会话存在时）
                        if (!playbackState.isPlaying && playbackState.currentItem != null) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
                NavigationBarItem(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = "设置") },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (tabIndex) {
                0 -> DirectoryScreen(playerViewModel)
                2 -> SettingsScreen(playerViewModel, cloudConfigViewModel)
            }
        }
    }
}
