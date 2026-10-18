package com.booklistener.core

/**
 * 播放进度恢复的纯计算逻辑（无 Android / 协程依赖，可单测）。
 * v4.0：进度锚点从"位置序号"改为"内容引用 (workId, fileUri)"——文件增删/改名/排序变化后，
 * uri 命中则精确定位（matchMode=1）；uri 失配按 orderIndex 近似（matchMode=0，UI 可提示"可能定位不准"），
 * 保留钳位兜底防止越界崩溃。
 */
object FolderProgressLogic {

    /**
     * 由作品进度记录解析恢复起点。
     * @param items  当前可播放条目列表
     * @param record 该作品的进度记录（null = 无记录）
     * @return (startIndex, startPositionMs)：无记录 → (0, 0)；
     *         fileUri 命中 → 精确位置；失配 → orderIndex 近似（clamp 到列表内）并保留位置
     */
    fun resolveStart(items: List<AudioItem>, record: WorkProgress?): Pair<Int, Long> {
        if (record == null || items.isEmpty()) return 0 to 0L
        val exact = items.indexOfFirst { it.path == record.fileUri }
        if (exact >= 0) return exact to record.positionMs
        val safeIndex = record.orderIndex.coerceIn(0, items.lastIndex)
        return safeIndex to record.positionMs
    }
}
