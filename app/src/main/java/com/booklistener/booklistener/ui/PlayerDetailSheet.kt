package com.booklistener.ui
import com.booklistener.ui.icons.AppIcons

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    onDismiss: () -> Unit
) {
    if (!state.hasItem) {
        // 空态引导：无播放队列时点击播放不应"毫无反应"，给出明确指引（v2.0.1 同款液态玻璃浮层）
        PlayerSheetGlassPanel(backdrop = backdrop, onDismiss = onDismiss) {
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
    PlayerSheetGlassPanel(backdrop = backdrop, onDismiss = onDismiss) {
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
    content: @Composable () -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    Box(modifier = Modifier.fillMaxSize()) {
        // 半透明遮罩：点击关闭（v2.0.1 按用户要求调至 0.3，通透液态玻璃质感）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onDismiss)
        )
        // 液态玻璃面板（同款 backdrop 特效）；visible 恒真：组合即滑入，移除即消失
        // backdrop 为 null（测试/无 backdrop 上下文）时回退普通表面底
        AnimatedVisibility(
            visible = true,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(320)) + fadeIn(tween(220)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)) + fadeOut(tween(160))
        ) {
            if (backdrop != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) },
                            effects = {
                                vibrancy()
                                blur(12f.dp.toPx())
                                lens(24f.dp.toPx(), 48f.dp.toPx())
                            },
                            onDrawSurface = { drawRect(backgroundColor.copy(alpha = 0.25f)) }
                        )
                ) {
                    SheetDragHandle()
                    content()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
            // v2.0.1：底部不留白，内容底端延伸到屏幕底部
        ,
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
            // 六档标签按 Slider 线性刻度对齐（0.5→0%、0.75→16.7%、1.0→33.3%、1.25→50%、1.5→66.7%、2.0→100%）：
            // 用 Spacer weight 表达档位间距比例，滑块位置与下方标识一一对应
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                PlaybackSpeedTiers.TIERS.forEachIndexed { i, tier ->
                    if (i > 0) {
                        Spacer(
                            modifier = Modifier.weight(
                                (tier - PlaybackSpeedTiers.TIERS[i - 1]) / 1.5f
                            )
                        )
                    }
                    SpeedOption(
                        label = PlaybackSpeedTiers.label(tier),
                        selected = kotlin.math.abs(tier - dragValue) < 0.01f
                    ) { onSelect(tier) }
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.End).padding(end = 24.dp, top = 4.dp)
            ) { Text("关闭") }
        }
    }
}

/** 速度档位快捷项：文案 + 选中态高亮 + 对勾（与 SleepOption 视觉一致，六档单行紧凑布局） */
@Composable
private fun SpeedOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 4.dp)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
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
