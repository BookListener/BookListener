package com.booklistener.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃日志捕获：未捕获异常写入 App 私有外部目录 crash_log.txt，
 * 用于 release 构建（R8）在真机上闪退时无 adb 也能拿到崩溃栈。
 * 文件路径：Android/data/com.booklistener/files/crash_log.txt（或内部 files 兜底）。
 */
object CrashLogger {

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(dir, "crash_log.txt")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText(
                    "=== $stamp ===\nThread: ${thread.name}\n" +
                        android.util.Log.getStackTraceString(throwable) + "\n\n"
                )
            } catch (_: Exception) {
                // 捕获逻辑本身失败时不再抛，避免二次崩溃
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
