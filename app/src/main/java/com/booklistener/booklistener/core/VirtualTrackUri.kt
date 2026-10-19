package com.booklistener.core

/**
 * 虚拟轨 uri 解析（v2.2.0，spec: cue-virtual-tracks）：
 * 虚拟轨 uri = `<整轨uri>#track_<startMs>`，同时充当 mediaId 与进度内容引用（D2）。
 * 播放/解析时拆出整轨 uri 与裁剪起点；纯函数，JVM 可测。
 */
object VirtualTrackUri {

    const val PREFIX = "#track_"

    /**
     * 解析虚拟轨 uri → (整轨 uri, 起始毫秒)；非虚拟轨返回 null。
     * WebDAV 整轨 uri 可能含查询参数（?xxx），取最后一个 "#track_" 前为整轨 uri，
     * 后缀必须是纯数字（startMs），否则视为普通 uri。
     */
    fun parse(uri: String): Pair<String, Long>? {
        val idx = uri.lastIndexOf(PREFIX)
        if (idx < 0) return null
        val start = uri.substring(idx + PREFIX.length).toLongOrNull() ?: return null
        if (start < 0) return null
        return uri.substring(0, idx) to start
    }

    /** 是否虚拟轨 uri */
    fun isVirtual(uri: String): Boolean = parse(uri) != null
}
