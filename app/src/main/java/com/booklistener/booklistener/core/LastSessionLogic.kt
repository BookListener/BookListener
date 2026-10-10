package com.booklistener.core

/**
 * 杀后台重启恢复：从已添加条目中定位"最近会话"的条目。
 * 纯逻辑，可单测；恢复行为（回到条目视图、就绪不自动播放）见 PlayerViewModel.restoreLastSession。
 */
object LastSessionLogic {

    /**
     * 最近会话条目：
     * 1. 优先最近播放（lastPlayedAt > 0 且最大，代表真正在播放的会话）；
     * 2. 无播放记录时回退最近打开（lastOpenedAt > 0 且最大，浏览过的会话也恢复为就绪态）；
     * 3. 从未播放/从未打开则返回 null（不恢复）。
     */
    fun pickRecent(entries: List<SavedEntry>): SavedEntry? {
        val played = entries.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }
        if (played != null) return played
        return entries.filter { it.lastOpenedAt > 0 }.maxByOrNull { it.lastOpenedAt }
    }
}
