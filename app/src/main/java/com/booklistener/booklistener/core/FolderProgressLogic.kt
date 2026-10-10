package com.booklistener.core

/**
 * 文件夹播放进度恢复的纯计算逻辑（无 Android / 协程依赖，可单测）：
 * 把记录中的 trackIndex 收敛到当前列表范围内，防止文件被删/改名后越界崩溃。
 */
object FolderProgressLogic {

    /**
     * 由进度记录解析恢复起点。
     * @param itemCount 当前扫描到的可播放条目数
     * @param record    该文件夹的进度记录（null = 无记录）
     * @return (startIndex, startPositionMs)：无记录 → (0, 0)；越界 → clamp 到末尾并保留位置
     */
    fun resolveStart(itemCount: Int, record: FolderProgress?): Pair<Int, Long> {
        if (record == null || itemCount <= 0) return 0 to 0L
        val safeIndex = record.trackIndex.coerceIn(0, itemCount - 1)
        return safeIndex to record.positionMs
    }
}
