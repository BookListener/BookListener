package com.booklistener.player

import com.booklistener.core.AudioItem

/**
 * 播放队列状态的纯计算逻辑：无 Android / 协程依赖，可在纯 JVM 单元测试中覆盖。
 * 防止"入队后 currentItem 缺失 / 切换后 UI 状态错乱"这类回归。
 */
object PlaybackQueueLogic {

    /** 入队后的状态快照：currentItem = 队列中 startIndex 条目 */
    fun buildQueueState(queue: List<AudioItem>, startIndex: Int): PlaybackState =
        PlaybackState(
            queue = queue,
            queueIndex = startIndex,
            currentItem = queue.getOrNull(startIndex)
        )

    /** 播放条目切换后的状态：currentItem 跟随 index，越界则为 null（不再有可播放条目） */
    fun transitionState(st: PlaybackState, index: Int): PlaybackState =
        st.copy(
            currentItem = st.queue.getOrNull(index),
            queueIndex = index,
            positionMs = 0L,
            durationMs = 0L
        )

    /** 列表中某条目是否为当前播放条目（高亮判定，按条目 id 匹配） */
    fun isCurrentItem(st: PlaybackState, itemId: String): Boolean =
        st.currentItem?.id == itemId
}
