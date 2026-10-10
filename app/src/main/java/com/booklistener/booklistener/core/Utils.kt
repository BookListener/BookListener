package com.booklistener.core

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

object AppLog {
    private const val TAG = "SimpleMusic"
    fun d(msg: String) = Log.d(TAG, msg)
    fun e(msg: String, tr: Throwable?) = Log.e(TAG, msg, tr)
}

fun <T> Flow<T>.asUiState(): Flow<UiState<T>> {
    return this
        .map<T, UiState<T>> { UiState.Success(it) }
        .catch { emit(UiState.Error(it.localizedMessage ?: "Unknown error")) }
}


/** 格式化时长：<1h 显示 mm:ss；>=1h 显示 h:mm:ss */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, min, sec)
    } else {
        "%02d:%02d".format(min, sec)
    }
}
