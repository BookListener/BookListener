package com.booklistener

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.booklistener.core.CrashLogger
import com.booklistener.core.StoreMigration
import com.booklistener.ui.MainScreen
import com.booklistener.ui.PlayerViewModel
import com.booklistener.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // release（R8）真机闪退定位：未捕获异常写入 crash_log.txt
        CrashLogger.install(applicationContext)
        // v1.6.0：prefs → Room 首启数据迁移（幂等，同步、毫秒级，不删 prefs）
        StoreMigration.run(
            getSharedPreferences("saved_folders", MODE_PRIVATE),
            com.booklistener.db.AppDatabase.getInstance(applicationContext)
        )

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }

        // 冷启动时直接收到 deep link（用户未运行 App 就完成授权回调）
        handleOAuthCallback(intent)
        // 冷启动外部打开：文件管理器"用其他应用打开"或"分享到"进入本播放器
        handleExternalOpen(intent)
    }

    /** 处理阿里云盘 OAuth 回调 deep link：booklistener://oauth/callback?code=xxx */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
        handleExternalOpen(intent)
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "booklistener" && data.host == "oauth") {
            val vm = ViewModelProvider(this)[PlayerViewModel::class.java]
            vm.handleAliyunCallback(data)
        }
    }

    /**
     * 外部打开本地音频（Android 规范）：
     * - ACTION_VIEW：文件管理器"用其他应用打开"，data 为音频 Uri；
     * - ACTION_SEND：其他应用"分享"音频，EXTRA_STREAM 为音频 Uri。
     * 仅处理音频类 MIME（或无法识别类型但来自 content scheme 的 Uri），避免抢占其他文件类型。
     */
    private fun handleExternalOpen(intent: Intent?) {
        val i = intent ?: return
        var data: Uri? = null
        when (i.action) {
            Intent.ACTION_VIEW -> data = i.data
            Intent.ACTION_SEND -> {
                data = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> Unit
        }
        if (data == null) return
        val uri: Uri = data

        var mime: String? = i.type
        if (mime == null) {
            mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        }
        val audioLike = mime?.startsWith("audio/") == true ||
            mime == "*/*" ||
            mime == "application/octet-stream" ||
            (mime == null && uri.scheme == "content")
        if (!audioLike) return

        // 持久化读取授权：Uri 来自其他应用授权，保存后可跨重启访问
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val vm = ViewModelProvider(this)[PlayerViewModel::class.java]
        vm.playUri(uri)
    }
}
