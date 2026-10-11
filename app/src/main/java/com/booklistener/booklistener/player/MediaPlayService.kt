package com.booklistener.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.booklistener.MainActivity
import com.booklistener.R
import com.booklistener.net.OkHttpHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台播放服务：持有 ExoPlayer + MediaSession。
 *
 * 通知策略（media3 1.4.0 事实约束）：media3 的 MediaNotificationManager 只在有
 * 外部 MediaController 连接时才发布系统媒体通知（shouldShowNotification 依赖
 * controllerMap），App 内直连 ExoPlayer 时它不会接管。因此这里用手动 MediaStyle
 * 通知承载锁屏/状态栏内容（曲目名 + 播放/暂停/上一下一 + 进度），并保留
 * DefaultMediaNotificationProvider 作为外部 controller 连接时的增强通道。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlayService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        val player = buildPlayer()
        // 通知点击跳回主界面
        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        PlaybackController.attach(player)
        requestAudioFocus()
        // 保留 media3 provider：外部 controller（系统媒体中心）连接时接管通知
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_play)
            }
        )
        // Android 8+ 要求 startForegroundService 后 5 秒内 startForeground（合规占位）
        startForegroundCompat()
        // 播放状态驱动 MediaStyle 通知内容（曲目名 / 播放状态 / 进度 / 控件按钮 / 封面）
        notificationJob = notificationScope.launch {
            PlaybackController.state.collect { state ->
                // 封面在 IO 线程解码（file:// 直接解码 / content:// 流解码）
                val icon = withContext(Dispatchers.IO) { loadLargeIcon() }
                updateMediaNotification(state, icon)
            }
        }
    }

    /**
     * 当前曲目通知大图标（v1.6.22 修复"跨文件夹封面串图"）：
     * 优先当前 MediaItem metadata 的 artworkUri；无封面时返回程序生成的占位图
     * （圆形渐变，与 App 无封面占位一致）。绝不返回 null——Android 通知更新时
     * 若不设置 largeIcon 会沿用上一次的图（播放无封面文件夹时显示上一个文件夹的封面）。
     */
    private fun loadLargeIcon(): Bitmap {
        val artUri = mediaSession?.player?.currentMediaItem?.mediaMetadata?.artworkUri
        val loaded = artUri?.let { loadBitmap(it) }
        return loaded ?: placeholderCover()
    }

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        when (uri.scheme) {
            "file" -> BitmapFactory.decodeFile(uri.path)
            "content" -> contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            else -> null
        }
    }.getOrNull()

    /** 无封面占位：与 App 内渐变占位同色系，显式覆盖旧图 */
    private fun placeholderCover(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            android.graphics.Color.parseColor("#2E5F8A"),
            android.graphics.Color.parseColor("#3FA0B5"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bmp
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS -> PlaybackController.previous()
            ACTION_TOGGLE -> PlaybackController.togglePlayPause()
            ACTION_NEXT -> PlaybackController.next()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        mediaSession?.player?.let { PlaybackController.detach(it) }
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        mediaSession?.release()
        super.onDestroy()
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { _ -> }
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** 首次前台占位通知（media3 通知与 MediaStyle 更新前的合规兜底） */
    private fun startForegroundCompat() {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ready")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** 播放状态驱动的 MediaStyle 通知：锁屏/状态栏显示曲目名、播放状态、进度、控件与封面 */
    private fun updateMediaNotification(state: PlaybackState, largeIcon: Bitmap) {
        val session = mediaSession ?: return
        val title = state.currentItem?.displayName ?: return
        val playing = state.isPlaying
        val text = when {
            state.isBuffering -> "Buffering..."
            playing -> "Playing"
            else -> "Paused"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MediaPlayService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MediaPlayService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MediaPlayService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val toggleLabel = if (playing) "Pause" else "Play"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(largeIcon)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextIntent)
        // 展开视图进度条（未知时长时显示不确定进度）
        val duration = state.durationMs
        if (duration > 0) {
            builder.setProgress(
                (duration / 1000).toInt().coerceAtLeast(1),
                (state.positionMs.coerceIn(0, duration) / 1000).toInt(),
                false
            )
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification = builder.build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
    }

    /**
     * 复合数据源（Android 规范做法）：
     * http/https 走 OkHttp（Range、UA、代理），
     * content:// / file:// / asset:// 等本地 URI 走系统 ContentDataSource/FileDataSource，
     * 使 SAF 选择的本地音频与文件夹内文件可直接播放。
     */
    private fun buildPlayer(): ExoPlayer {
        val okHttpDs = OkHttpDataSource.Factory(OkHttpHolder.client)
            .setUserAgent("BookListener/1.0")
        val compositeDs = DefaultDataSource.Factory(this, okHttpDs)
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(compositeDs))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIF_ID = 1001

        private const val ACTION_TOGGLE = "com.booklistener.action.TOGGLE"
        private const val ACTION_NEXT = "com.booklistener.action.NEXT"
        private const val ACTION_PREVIOUS = "com.booklistener.action.PREVIOUS"

        fun start(context: Context) {
            val intent = Intent(context, MediaPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
