package com.booklistener.player

import com.booklistener.core.AudioItem

/** 播放器对外暴露的状态（UI 单向数据流来源） */
data class PlaybackState(
    val currentItem: AudioItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<AudioItem> = emptyList(),
    val queueIndex: Int = -1
) {
    val hasItem: Boolean get() = currentItem != null
}
