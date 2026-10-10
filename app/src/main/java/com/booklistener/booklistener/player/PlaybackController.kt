package com.booklistener.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import com.booklistener.core.AudioItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 可播放条目：音频信息 + 已解析的真实播放 Uri（缓存本地文件或网盘直链） */
data class PlayableItem(
    val item: AudioItem,
    val uri: String,
    val coverUri: String? = null
)

/**
 * 进程级播放控制器：持有 ExoPlayer（由前台 Service 创建），
 * ViewModel 只与本单例交互，不直接操作 Service 内部。
 * 播放 Uri 由调用方在入队前解析完成。
 */
object PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var positionTicker: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 当前队列所属的 SavedEntry id（本地=文件夹树 Uri 字符串，网盘=条目 id）。
     *  判定"再次进入的是否为当前正在播放的队列"必须依赖此播放层状态，
     *  不能依赖易被清空的 UI 状态（_currentFolderUri 在退出条目视图时置 null）。 */
    @Volatile
    private var queueOwnerId: String? = null
    val currentQueueOwnerId: String? get() = queueOwnerId

    /**
     * 队列是否已就绪（存在可播放内容）：
     * player 未 attach 时看暂存队列是否非空；attach 后看实际当前曲目。
     * v1.6.9：进入条目/专辑视图"是否重启队列"的判定改用此值——
     * 暂停态点击封面恢复播放是异步的，旧判定（isPlaying）在暂停态恒判定需重启，
     * 会覆盖刚恢复的播放（点击暂停封面进详情再次停止播放的根因）。
     */
    val queueReady: Boolean
        get() = pendingQueue?.items?.isNotEmpty() == true || _state.value.currentItem != null

    // v1.6.7 循环模式：顺序(OFF) / 单曲(ONE) / 列表(ALL)，实时作用于播放器
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    /** 设置循环模式并立即作用于已创建的播放器（attach 后同样生效） */
    fun setRepeatMode(mode: Int) {
        _repeatMode.value = mode
        player?.repeatMode = mode
    }

    /** Service 尚未创建播放器时暂存队列（含起点与是否自动播放），attach 后立即应用 */
    private data class PendingQueue(
        val items: List<PlayableItem>,
        val startIndex: Int,
        val startPositionMs: Long,
        val autoPlay: Boolean,
        val ownerId: String?
    )

    private var pendingQueue: PendingQueue? = null

    /** Service 创建播放器后注册 */
    fun attach(exoPlayer: ExoPlayer) {
        player = exoPlayer
        exoPlayer.repeatMode = _repeatMode.value
        exoPlayer.addListener(playerListener)
        startPositionTicker()
        pendingQueue?.let { q ->
            pendingQueue = null
            applyQueue(q.items, q.startIndex, q.startPositionMs, q.autoPlay, q.ownerId)
        }
    }

    /** Service 销毁时解绑 */
    fun detach(player: Player) {
        positionTicker?.cancel()
        player.removeListener(playerListener)
        if (this.player === player) this.player = null
        _state.value = PlaybackState()
        queueOwnerId = null
    }

    /** 常规播放入队：从 startIndex 开始并立即播放；ownerId=null 表示不属于任何已添加条目队列 */
    fun setQueue(items: List<PlayableItem>, startIndex: Int, ownerId: String? = null) {
        queueOwnerId = ownerId
        val p = player
        if (p == null) {
            pendingQueue = PendingQueue(items, startIndex, 0L, true, ownerId)
            return
        }
        applyQueue(items, startIndex, 0L, autoPlay = true, ownerId)
    }

    /**
     * 文件夹播放记录恢复：队列加载 + 定位到记录曲目/位置，但保持暂停不自动播放。
     * 用户点击播放后再开始（从记录位置续播）。
     */
    fun prepareFolder(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long, ownerId: String? = null) {
        queueOwnerId = ownerId
        val p = player
        if (p == null) {
            pendingQueue = PendingQueue(items, startIndex, startPositionMs.coerceAtLeast(0L), false, ownerId)
            return
        }
        applyQueue(items, startIndex, startPositionMs.coerceAtLeast(0L), autoPlay = false, ownerId)
    }

    private fun applyQueue(
        items: List<PlayableItem>,
        startIndex: Int,
        startPositionMs: Long,
        autoPlay: Boolean,
        ownerId: String?
    ) {
        val p = player ?: return
        // MediaMetadata 必须随 MediaItem 设置：系统媒体通知/锁屏取它显示曲目名，
        // 缺失时会回退成应用名占位（截图中的 "BookListener / Playing"）
        val mediaItems = items.map {
            val metaBuilder = MediaMetadata.Builder().setTitle(it.item.displayName)
            // v1.6.3：通知/锁屏封面 = 播放上下文解析的专辑封面（无则不设，绝不串用其他专辑图）
            it.coverUri?.let { uri -> metaBuilder.setArtworkUri(Uri.parse(uri)) }
            MediaItem.Builder()
                .setUri(it.uri)
                .setMediaMetadata(metaBuilder.build())
                .build()
        }
        val audioItems = items.map { it.item }
        // 关键：autoPlay=false 时必须先显式 pause() 把 playWhenReady 置 false，
        // 否则 ExoPlayer 会保留上次播放状态（true），新队列 prepare 完成后立即自动播放。
        if (!autoPlay) p.pause()
        p.setMediaItems(mediaItems, startIndex, startPositionMs)
        _state.value = PlaybackQueueLogic.buildQueueState(audioItems, startIndex)
            .copy(positionMs = startPositionMs)
        p.prepare()
        if (autoPlay) p.play()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun next() {
        player?.next()
    }

    fun previous() {
        player?.previous()
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        player?.release()
        player = null
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            _state.update { it.copy(isBuffering = buffering) }
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(isPlaying = false, positionMs = 0L) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val idx = player?.currentMediaItemIndex ?: 0
            _state.update { st -> PlaybackQueueLogic.transitionState(st, idx) }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(isBuffering = false, isPlaying = false) }
        }
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (true) {
                val p = player ?: break
                _state.update {
                    it.copy(
                        positionMs = p.currentPosition,
                        durationMs = p.duration.takeIf { d -> d > 0 } ?: it.durationMs
                    )
                }
                delay(500)
            }
        }
    }
}
