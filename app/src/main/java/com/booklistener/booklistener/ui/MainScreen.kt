package com.booklistener.ui

import androidx.compose.ui.res.stringResource
import com.booklistener.R
import com.booklistener.ui.icons.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.booklistener.player.PlaybackController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
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

    // v2.0.8d：弹窗"接力"动画（用户澄清：三大件 = 动作条上的三个组件，非整条玻璃）
    // 打开：动作条三个组件（曲目/封面/设置）快速上移消失(200ms) 与 面板滑入(400ms) 同时启动——
    // 组件路程短先消失、面板路程长继续上移（视觉"先消失→面板接着上移"）；
    // 关闭（相反）：面板下滑(400ms) → 三组件下移归位(200ms)。
    // 动作条玻璃条本身留在底部不动，面板滑入盖住它。
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetPanelVisible by remember { mutableStateOf(false) } // 面板+遮罩实际滑入状态
    var barItemsHidden by remember { mutableStateOf(false) }    // 动作条三组件隐藏状态
    // 三组件动画进度：0=原位，1=上移消失（下移归位为反向）
    val barItemsProgress by animateFloatAsState(
        targetValue = if (barItemsHidden) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "bar_items_rise"
    )
    // 编排（v2.0.8g：两个动作时间、空间连贯）：
    // 打开 = 动作1 组件上移消失(200ms) → 消失瞬间动作2 面板滑入(400ms)，无空档 = 总 600ms；
    // 关闭 = 动作1 面板下滑(400ms) → 面板下滑到组件位置（delay 250ms，非等面板消失）→
    //         动作2 组件下移归位(200ms)，与面板尾部重叠衔接（"面板收回到组件处→组件接着收回"），总 ~450ms
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) {
            barItemsHidden = true            // 动作1：三组件上移消失（200ms）
            delay(200)                        // 等动作1完全结束（消失后，无空档）
            sheetPanelVisible = true          // 动作2：面板接着出现并继续上移（400ms）
        } else {
            sheetPanelVisible = false         // 动作1：面板下滑（400ms，下滑全程可见）
            delay(250)                        // 面板下滑到组件位置（组件藏身处=动作条上方）时开始组件下滑，非等面板消失
            barItemsHidden = false            // 动作2：三组件下移归位（200ms），与面板尾部重叠连贯
        }
    }

    // 必须用 collect 订阅 StateFlow：直接读 .value 不会触发 Compose 重组，
    // 会导致"能播放但界面不显示"（本 bug 由 UT 覆盖回归）
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    val inFolderView by playerViewModel.inFolderView.collectAsStateWithLifecycle()
    // 切换文件夹后就绪 → 自动弹出播放详情（但不自动播放）
    val requestPlayerSheet by playerViewModel.requestPlayerSheet.collectAsStateWithLifecycle()
    // v2.0.10：毛玻璃特效组件级开关（设置页控制，实时生效）
    val glassActionBar by playerViewModel.glassActionBar.collectAsStateWithLifecycle()
    val glassPlayerPanel by playerViewModel.glassPlayerPanel.collectAsStateWithLifecycle()
    LaunchedEffect(requestPlayerSheet) {
        if (requestPlayerSheet) {
            showPlayerSheet = true
            // v2.0.8c：专辑路径（点击专辑/openAlbum）同步置 sheetOpen——驱动动作条上移动画。
            // 此前只置 showPlayerSheet，动作条不动，弹出只剩面板/遮罩（且二者 enter 未生效）→ 弹出几乎无动画
            sheetOpen = true
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
        // v2.0.10：毛玻璃特效组件级开关——关闭时动作条降级为纯色圆角表面（不再采样 backdrop）
        val actionBarSurface = if (glassActionBar) {
            Modifier.drawBackdrop(
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
        } else {
            Modifier.background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            )
        }
        Row(
            modifier = actionBarSurface
                // v2.0.8d：动作条玻璃条本身留在底部不动（不再整体上移）；
                // 上移让位的是内部三个组件（各自 graphicsLayer 上移消失/下移归位）
                .height(96.dp)
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                // v2.0.8h：动作条整体上滑展开播放详情面板——向上拖拽累计超过 48dp 触发，
                // 动画与点击封面按钮同一套（组件上移消失→面板滑入；关闭反向）
                .pointerInput(Unit) {
                    var accumulatedUp = 0f
                    var triggered = false
                    detectVerticalDragGestures(
                        onDragStart = {
                            accumulatedUp = 0f
                            triggered = false
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (!triggered) {
                                if (dragAmount < 0) {
                                    accumulatedUp += -dragAmount
                                    if (accumulatedUp > 48.dp.toPx()) {
                                        triggered = true
                                        showPlayerSheet = true
                                        sheetOpen = true
                                    }
                                } else {
                                    accumulatedUp = 0f
                                }
                            }
                        }
                    )
                },
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
                icon = { Icon(AppIcons.MusicNote, contentDescription = stringResource(R.string.cd_tracks), modifier = Modifier.size(30.dp)) },
                modifier = Modifier
                    // v2.0.8d：三组件上移消失（barItemsProgress 0→1）/下移归位（反向）
                    .graphicsLayer {
                        translationY = -96.dp.toPx() * barItemsProgress
                        alpha = 1f - barItemsProgress
                    }
                    .weight(1f)
            )
            // 中间：当前播放封面按钮（v1.6.8：84dp 圆形，播放中缓慢旋转；暂停显示半透明播放按钮），
            // 点击弹出播放详情并后台切到所属文件夹；若处于暂停则同时恢复播放
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    // v2.0.8d：与曲目/设置组件同步上移消失/下移归位
                    .graphicsLayer {
                        translationY = -96.dp.toPx() * barItemsProgress
                        alpha = 1f - barItemsProgress
                    }
                    .clip(CircleShape)
                    .clickable {
                        // 播放框弹出的同时，后台切换到当前播放队列所属文件夹的条目视图
                        // v2.1.2：队列所属语义为 "work:<workId>"，经 queueOwnerEntry 反查到来源条目
                        val entry = playerViewModel.queueOwnerEntry()
                        if (entry != null) {
                            tabIndex = 0
                            val alreadyIn = playerViewModel.inFolderView.value &&
                                playerViewModel.currentFolderUri.value?.toString() == entry.id
                            if (!alreadyIn) {
                                playerViewModel.openEntryAlbum(entry)
                            }
                        }
                        // 暂停状态点击：弹出详情的同时恢复播放
                        if (!playbackState.isPlaying && playbackState.currentItem != null) {
                            playerViewModel.togglePlayPause()
                        }
                        showPlayerSheet = true
                        // v2.0.8d：置 sheetOpen → LaunchedEffect 编排"组件上移消失→面板出现"
                        sheetOpen = true
                    }
                    .padding(vertical = 8.dp)
            ) {
                // 底部"正在播放"封面：播放上下文专辑封面优先（单专辑文件夹图/多专辑内嵌），无则渐变占位
                // v2.1.2：队列所属为作品级时经专辑来源条目兜底（内存版，组合期不查库）
                val ownerEntry = playerViewModel.queueEntryForDisplay()
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
                                contentDescription = stringResource(R.string.cd_play),
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
                icon = { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_settings), modifier = Modifier.size(30.dp)) },
                modifier = Modifier
                    // v2.0.8d：三组件同步上移消失/下移归位
                    .graphicsLayer {
                        translationY = -96.dp.toPx() * barItemsProgress
                        alpha = 1f - barItemsProgress
                    }
                    .weight(1f)
            )
        }

        // v2.0.1：播放详情液态玻璃浮层——挂在主页 backdrop Box 内（同合成树），
        // 直接复用动作条同一 backdrop，实现完全一致的液态玻璃材质。
        // v2.0.8c：恒组合 + visible 状态驱动——此前 if(showPlayerSheet) 移除组合导致
        // AnimatedVisibility exit 不播（关闭瞬间消失）、visible 恒 true 时 enter 不播（打开无滑入）；
        // 改为常驻组合、内部 visible 由状态控制，打开/关闭动画才真正播放。
        // v2.0.8d：visible 改为 sheetPanelVisible——面板在组件消失后"接着出现"（LaunchedEffect 编排）
        PlayerDetailSheet(
            viewModel = playerViewModel,
            state = playbackState,
            // v2.1.2：队列所属为作品级时经专辑来源条目兜底（内存版）
            entry = playerViewModel.queueEntryForDisplay(),
            albumCoverUri = playerViewModel.currentAlbum.value?.coverUri,
            // v2.0.10：播放详情面板玻璃开关——关闭时传 null，面板走 surfaceContainerLow 纯色回退分支
            backdrop = if (glassPlayerPanel) backdrop else null,
            visible = sheetPanelVisible,
            onDismiss = {
                showPlayerSheet = false
                // v2.0.8d：置 sheetOpen=false → LaunchedEffect 编排"面板下滑→组件下移归位"
                sheetOpen = false
            }
        )
    }
}
