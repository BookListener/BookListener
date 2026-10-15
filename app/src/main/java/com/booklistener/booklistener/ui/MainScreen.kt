package com.booklistener.ui
import com.booklistener.ui.icons.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
// Liquid Glass（AndroidLiquidGlass / backdrop）：主页底部动作条液态玻璃特效
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 主界面：底部导航（曲目 / 播放封面 / 设置）。
 * 中间封面按钮向上弹出播放详情（PlayerDetailSheet）；
 * 网盘配置已并入设置页。
 */
@Composable
fun MainScreen(
    playerViewModel: PlayerViewModel = viewModel()
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

    // 设置 tab 顶层：系统返回键回到曲目页（子页内部返回由各 Screen 内层 BackHandler 优先处理）
    BackHandler(enabled = tabIndex == 2) { tabIndex = 0 }

    // v1.6.16：主页底部动作条液态玻璃化（AndroidLiquidGlass backdrop）。
    // 内容层通过 layerBackdrop 关联 backdrop，底部自绘玻璃条覆盖，
    // 列表滚动经过玻璃条时呈现 折射(lens)+模糊(blur)+饱和增强(vibrancy) 的液态玻璃质感。
    Box(modifier = Modifier.fillMaxSize()) {
        val backgroundColor = MaterialTheme.colorScheme.background
        // v2.0.6：玻璃表面色改用 surfaceVariant（浅灰）——与模糊背景拉开色差，alpha 变化才可见
        val glassSurface = MaterialTheme.colorScheme.surfaceVariant
        val backdrop = rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }

        Scaffold(modifier = Modifier.layerBackdrop(backdrop)) { innerPadding ->
            // v2.0.1：去掉列表底部 96dp 预留——列表底端延伸至屏幕底部，滚动到底时
            // 最后一项透过底部液态玻璃条可见（玻璃条 25% 遮盖 + blur，不影响遮挡）
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (tabIndex) {
                    0 -> DirectoryScreen(playerViewModel)
                    2 -> SettingsScreen(playerViewModel)
                }
            }
        }

        // 玻璃底栏：高度 96dp 容纳增大的 84dp 中间播放封面
        // v1.6.19：增强特效可见度——blur 4→12dp、lens 16/32→24/48dp、
        // 表面遮盖 45%→25%（深色主题下原参数几乎看不出模糊/折射，已按头条毛玻璃风格校准）
        // v2.0.1：均匀毛玻璃（参考图白条质感）——仅强模糊 blur 24dp（48dp 过糊），不叠加 lens（圆角边缘折射
        // 导致模糊不均匀）与 vibrancy（脏色斑），与详情弹窗两处特效视觉一致。
        // v2.0.5：表面遮盖 55%→40%（透出率 45%→60%）——用户反馈"不够透"，三处玻璃统一调透。
        // v2.0.5b：40%→30%（透出率 60%→70%）——用户实测仍"不够透"。
        // v2.0.6（源组件排查实证）：blur 24dp→10dp（24dp≈72px 把背景糊成光斑，是"看不出透"的核心）；
        // 表面 background→surfaceVariant（浅色背景下与模糊背景同色系，alpha 变化不可见）。
        // v2.0.1：移除 safeContentPadding——液态玻璃特效延伸至屏幕最底（含导航条区域），
        // 列表底端滚动到底时在原留白处也呈现玻璃特效
        Row(
            modifier = Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) },
                    effects = {
                        blur(10f.dp.toPx())
                        // v2.0.6b：透镜折射（源组件 lens：圆角边缘向内 refractionHeight 像素产生折射，
                        // 强度 refractionAmount 像素采样偏移；depthEffect/chromaticAberration 默认关）。
                        // 调大折射更明显：refractionHeight↑ 折射带更宽 / refractionAmount↑ 扭曲更强。
                        // v2.0.6c：四项全加强——refractionHeight 16→24dp、refractionAmount 28→48、
                        // 开启 depthEffect（边缘深度扭曲）与 chromaticAberration（彩色色散）。
                        // v2.0.6d：边缘/中心对比仍不足——折射带再拉宽 24→40dp（shader 中 -sd>=refractionHeight
                        // 区域直接原样采样，带窄则中间大片区域无折射）、强度 48→72。
                        lens(
                            refractionHeight = 40f.dp.toPx(),
                            refractionAmount = 72f,
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    },
                    onDrawSurface = { drawRect(glassSurface.copy(alpha = 0.30f)) }
                )
                .height(96.dp)
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
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
                icon = { Icon(AppIcons.MusicNote, contentDescription = "曲目", modifier = Modifier.size(30.dp)) },
                modifier = Modifier.weight(1f)
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
                icon = { Icon(Icons.Outlined.Settings, contentDescription = "设置", modifier = Modifier.size(30.dp)) },
                modifier = Modifier.weight(1f)
            )
        }

        // v2.0.1：播放详情液态玻璃浮层——挂在主页 backdrop Box 内（同合成树），
        // 直接复用动作条同一 backdrop，实现完全一致的液态玻璃材质
        if (showPlayerSheet) {
            PlayerDetailSheet(
                viewModel = playerViewModel,
                state = playbackState,
                entry = PlaybackController.currentQueueOwnerId?.let { playerViewModel.savedEntryById(it) },
                albumCoverUri = playerViewModel.currentAlbum.value?.coverUri,
                backdrop = backdrop,
                onDismiss = { showPlayerSheet = false }
            )
        }
    }
}
