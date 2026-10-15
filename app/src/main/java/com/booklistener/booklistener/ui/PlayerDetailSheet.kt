package com.booklistener.ui
import com.booklistener.ui.icons.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.booklistener.core.AudioMetaDefaults
import com.booklistener.core.MessageCatalog
import com.booklistener.core.PlaybackSpeedTiers
import com.booklistener.core.formatDuration
import com.booklistener.player.PlaybackState
// v2.0.1 液态玻璃：详情弹层复用主页底部动作条同款 backdrop（AndroidLiquidGlass 开源组件能力）
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 播放详情弹层：底部向上弹出（网易云风格），v2.0.1 起材质与主页底部动作条一致（液态玻璃 backdrop）。
 * 显示封面、歌名、专辑名、进度条（可拖动 seek）、
 * 速度入口（弹动作条）/ 上一曲 / 播放暂停 / 下一曲 / 定时关闭、AB 段重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDetailSheet(
    viewModel: PlayerViewModel,
    state: PlaybackState,
    entry: com.booklistener.core.SavedEntry? = null,
    albumCoverUri: String? = null,
    backdrop: Backdrop? = null,
    onDismiss: () -> Unit,
    // v2.0.8：面板实时高度回调（px）——主页动作条按此联动上移让位（停在面板顶边上方）
    onPanelHeightChange: (Int) -> Unit = {},
    // v2.0.8c：浮层可见性由外部状态驱动——常驻组合，enter/exit 动画真正播放
    // （此前 visible 恒 true：首次组合直接显示不播 enter；父 if 移除组合时 exit 也不播）
    visible: Boolean = true
) {
    if (!state.hasItem) {
        // 空态引导：无播放队列时点击播放不应"毫无反应"，给出明确指引（v2.0.1 同款液态玻璃浮层）
        PlayerSheetGlassPanel(
            backdrop = backdrop,
            onDismiss = onDismiss,
            onPanelHeightChange = onPanelHeightChange,
            visible = visible
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = MessageCatalog.EMPTY_PLAYER_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = MessageCatalog.EMPTY_PLAYER_GUIDE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        return
    }
    var showSleepOptions by remember { mutableStateOf(false) }
    var showSpeedOptions by remember { mutableStateOf(false) }
    // v2.0.8c：常驻组合（不再随 if 销毁）——关闭时重置内部选项状态，
    // 避免再次打开时仍停留在睡眠/倍速选项页
    LaunchedEffect(visible) {
        if (!visible) {
            showSleepOptions = false
            showSpeedOptions = false
        }
    }
    PlayerSheetGlassPanel(
        backdrop = backdrop,
        onDismiss = onDismiss,
        onPanelHeightChange = onPanelHeightChange,
        visible = visible
    ) {
        PlayerDetailSheetContent(
            state = state,
            entry = entry,
            albumCoverUri = albumCoverUri,
            sleepRemainingMs = viewModel.sleepOnceRemaining.collectAsStateWithLifecycle().value,
            onOpenSleepTimer = { showSleepOptions = true },
            abA = viewModel.abA.collectAsStateWithLifecycle().value,
            abB = viewModel.abB.collectAsStateWithLifecycle().value,
            onToggleAbPoint = viewModel::toggleAbPoint,
            onClearAb = viewModel::clearAb,
            speed = viewModel.playbackSpeed.collectAsStateWithLifecycle().value,
            onOpenSpeedSheet = { showSpeedOptions = true },
            onToggle = viewModel::togglePlayPause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onSeek = viewModel::seekTo
        )
    }
    if (showSleepOptions) {
        SleepTimerSheet(
            mode = viewModel.sleepOnceMode.collectAsStateWithLifecycle().value,
            minutes = viewModel.sleepOnceMinutes.collectAsStateWithLifecycle().value,
            remainingMs = viewModel.sleepOnceRemaining.collectAsStateWithLifecycle().value,
            onSelectOff = {
                viewModel.cancelSleepTimerOnce()
                showSleepOptions = false
            },
            onSelectEndOfTrack = {
                viewModel.setSleepTimerToEndOfTrack()
                showSleepOptions = false
            },
            onSelectMinutes = { m ->
                viewModel.setSleepTimerOnce(m)
                showSleepOptions = false
            },
            onClose = { showSleepOptions = false }
        )
    }
    if (showSpeedOptions) {
        SpeedSheet(
            speed = viewModel.playbackSpeed.collectAsStateWithLifecycle().value,
            onSelect = viewModel::setPlaybackSpeed,
            onClose = { showSpeedOptions = false }
        )
    }
}

/**
 * v2.0.1 液态玻璃详情浮层：复用主页底部动作条同一 backdrop（AndroidLiquidGlass 开源组件能力），
 * 特效参数完全一致（vibrancy + blur12 + lens24/48 + 25% 表面遮盖、顶部圆角 28dp），
 * 由半透明遮罩 + 底部玻璃面板组成；进入滑入渐显，点击遮罩关闭。
 * 说明：ModalBottomSheet 为独立窗口，无法采样主页 backdrop 层，故改为同合成树内浮层以真正复用组件能力。
 */
@Composable
private fun PlayerSheetGlassPanel(
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    // v2.0.8：面板实际高度上报（px，onSizeChanged）——供动作条联动位移
    onPanelHeightChange: (Int) -> Unit,
    // v2.0.8c：浮层可见性由外部状态驱动——遮罩 fadeIn/fadeOut、面板 slideIn/slideOut 真正播放
    visible: Boolean,
    content: @Composable () -> Unit
) {
    // v2.0.6：玻璃表面改用 surfaceVariant（浅灰）——与模糊背景拉开色差，alpha 变化才可见（浅色背景下
    // background 与模糊背景同色系，alpha 0.55→0.30 肉眼无区别，此为排查实证的无效变量）
    val glassSurface = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = Modifier.fillMaxSize()) {
        // v2.0.7 bugfix：浮层可见时拦截系统返回键 → 关闭弹窗。
        // 原实现无 BackHandler，返回键穿透到背后专辑列表的 BackHandler，直接退出列表（弹窗关不掉）。
        BackHandler(enabled = visible) { onDismiss() }
        // 半透明遮罩：点击关闭（v2.0.1 用户要求"透明度10%"＝遮罩 90% 不透明，遮住背景列表）
        // v2.0.6：遮罩 90%→50%——0.9 挡 90% 光，弹窗总透光率仅 ~7%，是"弹窗不透"的独立元凶；
        // 降至 0.5 后弹窗下背景半隐可见、玻璃透感成立，仍保点击关闭语义
        // v2.0.8c：遮罩改为 AnimatedVisibility 状态驱动——打开淡入、关闭淡出（400ms，与面板/动作条同步）
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onDismiss)
            )
        }
        // 液态玻璃面板（同款 backdrop 特效）
        // backdrop 为 null（测试/无 backdrop 上下文）时回退普通表面底
        // v2.0.8：动画时长统一 400ms，与主页动作条上移同步（连续上移接力，用户要求更慢一档）
        // v2.0.8c：visible 由外部状态驱动（常驻组合）——此前恒 true 时首次组合直接显示、enter 从不播放，
        // 且父 if 移除组合时 exit 不播；这是"弹出几乎看不到动画"的根因，本次修正
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(tween(400)),
            // v2.0.8g：exit 的 fadeOut 160→400ms——此前 160ms 内面板淡成透明，
            // 400ms 的下滑位移在透明状态下不可见，表现为"下滑动作没有播放"
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(tween(400))
        ) {
            if (backdrop != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onPanelHeightChange(it.height) }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) },
                            effects = {
                                // v2.0.6：blur 24dp→10dp（≈72px→28px）——24dp 把背景糊成光斑、轮廓全无，
                                // 是"看不出透"的核心原因；10dp 后背景文字/封面轮廓可辨（参考图效果）。
                                blur(10f.dp.toPx())
                                // v2.0.6b：透镜折射（与主页动作条一致），圆角边缘 refractionHeight 像素折射带、
                                // refractionAmount 像素偏移强度；调大折射更明显。
                                // v2.0.6c：四项全加强（refractionHeight 16→24dp、refractionAmount 28→48、
                                // depthEffect + chromaticAberration 开启）。
                                // v2.0.6d：折射带拉宽 24→40dp、强度 48→72（与主页动作条一致，增强边缘/中心对比）。
                                lens(
                                    refractionHeight = 40f.dp.toPx(),
                                    refractionAmount = 72f,
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            },
                            // v2.0.6：表面=surfaceVariant（浅灰）×alpha 0.30——色差拉开后透感成立
                            onDrawSurface = { drawRect(glassSurface.copy(alpha = 0.30f)) }
                        )
                ) {
                    SheetDragHandle()
                    content()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onPanelHeightChange(it.height) }
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    SheetDragHandle()
                    content()
                }
            }
        }
    }
}

/** 面板顶部下滑指示条：参照 ModalBottomSheet 默认 dragHandle（32x4dp），提示可下滑关闭 */
@Composable
private fun ColumnScope.SheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 12.dp, bottom = 8.dp)
            .size(width = 32.dp, height = 4.dp)
            .align(Alignment.CenterHorizontally)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    )
}

/** 详情页内容（独立可测，不含弹层容器） */
@Composable
fun PlayerDetailSheetContent(
    state: PlaybackState,
    entry: com.booklistener.core.SavedEntry? = null,
    albumCoverUri: String? = null,
    repeatMode: Int = androidx.media3.common.Player.REPEAT_MODE_OFF,
    onCycleRepeatMode: () -> Unit = {},
    sleepRemainingMs: Long? = null,
    onOpenSleepTimer: () -> Unit = {},
    abA: Long? = null,
    abB: Long? = null,
    onToggleAbPoint: () -> Unit = {},
    onClearAb: () -> Unit = {},
    speed: Float = 1.0f,
    onOpenSpeedSheet: () -> Unit = {},
    onToggle: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSeek: (Long) -> Unit = {}
) {
    val current = state.currentItem ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .padding(top = 8.dp) // v2.0.1：顶部留白参照速度弹窗标题布局，避免内容顶天
            .padding(bottom = 40.dp), // v2.0.1：详情弹窗底部恢复原 40dp 留白（用户明确"列表留白"非此处）
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MarqueeText(
            text = current.displayName,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        // v2.0.1：取消作者显示，仅保留专辑名（居中单行，缺失兜底 Unknown）
        val albumName = current.album ?: entry?.displayName
        Text(
            text = albumName?.takeIf { it.isNotBlank() } ?: AudioMetaDefaults.UNKNOWN_ARTIST,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = if (state.queue.size > 0) {
                "第 ${state.queueIndex + 1} 首 / 共 ${state.queue.size} 首"
            } else {
                "BookListener"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(28.dp))

        // 详情封面：播放上下文专辑封面优先（单专辑文件夹图/多专辑内嵌封面），无则渐变占位——绝不串用其他专辑图
        val coverUri = albumCoverUri ?: entry?.coverUri
        if (coverUri != null) {
            CoverImage(coverUri = coverUri, displayName = current.displayName, size = 240.dp)
        } else {
            AlbumCover(displayName = current.displayName, size = 240.dp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 进度条：拖动中显示拖动值，松手后 seek
        val duration = state.durationMs
        var dragPos by remember { mutableStateOf<Float?>(null) }
        val maxVal = duration.toFloat().coerceAtLeast(1f)
        val sliderValue = (dragPos ?: state.positionMs.toFloat()).coerceIn(0f, maxVal)
        Slider(
            value = sliderValue,
            onValueChange = { dragPos = it },
            onValueChangeFinished = {
                dragPos?.let { onSeek(it.toLong()) }
                dragPos = null
            },
            enabled = duration > 0,
            // 关键：Slider 默认 valueRange 是 0f..1f，不传会把真实毫秒值压缩到 0..1，
            // 进度条永远满格、拖动 seek 到 0/1ms。必须显式给 0..duration。
            valueRange = 0f..maxVal,
            modifier = Modifier
                .fillMaxWidth()
                .testTagForSeek()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            // 拖动中显示拖动值（松手 seek 后才回落到播放器实际位置）
            Text(
                text = formatDuration(sliderValue.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatDuration(state.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // v2.0.1：AB 复读未开始（未设任何点）时不显示"未设置 AB 点"与取消 X，
        // 仅保留右对齐小旗；设点后恢复完整行（取消 | 状态文字 | 小旗）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (abA != null || abB != null) {
                IconButton(
                    onClick = onClearAb,
                    enabled = true
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清除 AB 点",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = when {
                        abA != null && abB != null ->
                            "${formatDuration(abA)} ~ ${formatDuration(abB)}"
                        abA != null -> "A ${formatDuration(abA)}（再点设 B）"
                        abB != null -> "B ${formatDuration(abB)}（再点设 A）"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // 未开始：占位撑开，小旗保持右对齐原位
                Spacer(modifier = Modifier.weight(1f))
            }
            IconButton(onClick = onToggleAbPoint) {
                Icon(
                    imageVector = AppIcons.Flag,
                    contentDescription = "设置复读区间",
                    tint = if (abA != null || abB != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 控制按钮：循环模式 / 上一曲 / 播放暂停（大圆）/ 下一曲 / 定时关闭
        // v1.6.8：两端图标（循环/定时）用 weight Spacer 推开，远离中间播放控制组（上一曲/播放/下一曲）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // v2.0.1：循环模式 UI 入口暂时取消（repeatMode 业务逻辑保留于 ViewModel/PlaybackController，
            // 未删除），相同位置改为播放速度入口：显示当前档位文字，点击弹出速度调节动作条
            IconButton(onClick = onOpenSpeedSheet, modifier = Modifier.size(48.dp)) {
                Text(
                    text = PlaybackSpeedTiers.label(speed),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (speed != 1.0f) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = AppIcons.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(36.dp)
                )
            }
            Surface(
                onClick = onToggle,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(72.dp)
                    .padding(0.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.isPlaying) AppIcons.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = AppIcons.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // v1.6.7：一次性定时关闭；激活时按钮上方显示剩余时间
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onOpenSleepTimer, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = AppIcons.Timer,
                        contentDescription = "定时关闭",
                        tint = if (sleepRemainingMs != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(26.dp)
                    )
                }
                if (sleepRemainingMs != null) {
                    Text(
                        text = formatDuration(sleepRemainingMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** v1.6.7 一次性定时关闭底部弹层：不开启 / 听完本章节 / 15 / 30 / 60 / 90 / 自定义 / 关闭 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    mode: SleepTimerMode,
    minutes: Int,
    remainingMs: Long?,
    onSelectOff: () -> Unit,
    onSelectEndOfTrack: () -> Unit,
    onSelectMinutes: (Int) -> Unit,
    onClose: () -> Unit
) {
    var customInput by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "定时停止播放",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                textAlign = TextAlign.Center
            )
            if (remainingMs != null && mode != SleepTimerMode.OFF) {
                Text(
                    text = "剩余 ${formatDuration(remainingMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
            if (customInput) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("分钟数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            customMinutes.toIntOrNull()?.let(onSelectMinutes)
                        },
                        modifier = Modifier.padding(start = 12.dp)
                    ) { Text("确定") }
                }
            } else {
                SleepOption(
                    label = "不开启",
                    selected = mode == SleepTimerMode.OFF
                ) { onSelectOff() }
                SleepOption(
                    label = "听完本章节",
                    selected = mode == SleepTimerMode.END_OF_TRACK
                ) { onSelectEndOfTrack() }
                listOf(15, 30, 60, 90).forEach { m ->
                    SleepOption(
                        label = "$m 分钟",
                        selected = mode == SleepTimerMode.MINUTES && minutes == m
                    ) { onSelectMinutes(m) }
                }
                SleepOption(
                    label = "自定义",
                    selected = mode == SleepTimerMode.MINUTES && minutes !in listOf(15, 30, 60, 90)
                ) { customInput = true }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.End).padding(end = 24.dp, top = 4.dp)
            ) { Text("关闭") }
        }
    }
}

/** 定时选项行：左侧文案，选中态高亮 + 对勾 */
@Composable
private fun SleepOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * v2.0.1 播放速度调节动作条：Slider 拖动（0.5x-2.0x 连续，步进 0.125，松手应用）
 * + 常用档位快捷点选。拖动/点选均即时应用并持久化，弹层保持打开可连续调节，关闭/下滑手势退出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
    speed: Float,
    onSelect: (Float) -> Unit,
    onClose: () -> Unit
) {
    var dragValue by remember { mutableStateOf(speed) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "播放速度",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = "当前 ${PlaybackSpeedTiers.label(dragValue)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                textAlign = TextAlign.Center
            )
            Slider(
                value = dragValue,
                // v2.0.1：拖动即吸附最近档位（非档位值立即归位），保证滑块位置与下方档位标志一致
                onValueChange = { v -> dragValue = PlaybackSpeedTiers.nearest(v) },
                onValueChangeFinished = { onSelect(dragValue) },
                valueRange = 0.5f..2.0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            // 六档标签：文字中心与 Slider 刻度点严格居中，且自适应不同屏宽。
            // 坐标基准 = Slider 的 thumb 实际行程（非轨道端点）：
            //   Slider 组件 = fillMaxWidth + padding(horizontal=24dp)，thumb 行程 = [24dp+10dp, 屏宽-24dp-10dp]
            //   （10dp = M3 Slider 默认 thumb 半径，即 thumb 中心可停范围的两端）
            // 每档按线性比例（0.5→0%、0.75→16.7%、1.0→33.3%、1.25→50%、1.5→66.7%、2.0→100%）
            // 绝对定位 + onSizeChanged 测量文字宽，文字中心恰好落在 thumb 行程刻度上。
            // BoxWithConstraints 不带水平 padding（避免 maxWidth 双重缩减），maxWidth = 屏宽，
            // 不同宽度设备下 trackStart/trackLen 实时由 maxWidth 换算。
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                val density = LocalDensity.current
                // Slider 轨道起点 = 24dp 组件 padding + 10dp thumb 半径
                val trackStartPx = with(density) { 34.dp.toPx() }
                // thumb 行程长度 = 屏宽 - 2×34dp
                val trackLenPx = with(density) { (maxWidth - 68.dp).toPx() }
                PlaybackSpeedTiers.TIERS.forEach { tier ->
                    val fraction =
                        PlaybackSpeedTiers.fraction(tier)
                    val selected = abs(tier - dragValue) < 0.01f
                    var textWidth by remember(tier) { mutableIntStateOf(0) }
                    Row(
                        modifier = Modifier.offset {
                            IntOffset(
                                x = (trackStartPx + trackLenPx * fraction - textWidth / 2f).roundToInt(),
                                y = 0
                            )
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = PlaybackSpeedTiers.label(tier),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.onSizeChanged { textWidth = it.width }
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "已选择",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp).size(16.dp)
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.End).padding(end = 24.dp, top = 4.dp)
            ) { Text("关闭") }
        }
    }
}

/** 播放详情进度条语义标记（供 UI 测试定位） */
internal fun Modifier.testTagForSeek(): Modifier = this.then(Modifier.testTag("seek_bar"))

/**
 * 占位专辑封面：圆形渐变 + 曲名首字（本项目暂无真实封面资源，按播放器常规占位呈现）。
 */
@Composable
fun AlbumCover(displayName: String, size: Dp, modifier: Modifier = Modifier) {
    val initial = displayName.trim().firstOrNull()?.toString() ?: ""
    val density = LocalDensity.current
    val fontSize = with(density) { (size * 0.38f).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2E5F8A), Color(0xFF3FA0B5))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (initial.isEmpty()) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = "专辑封面",
                tint = Color.White,
                modifier = Modifier.size(size * 0.42f)
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(size * 0.12f)
                    .size(size * 0.22f)
            )
        }
    }
}
