package com.booklistener.core

import android.net.Uri

/**
 * 把外部传入的音频 Uri（SAF 选择器 / ACTION_VIEW / ACTION_SEND）构造成 AudioItem。
 * 元数据经注入的查询函数获取（生产环境用 ContentResolver 查询 OpenableColumns，
 * 不依赖 MediaStore、无需存储权限）；查询函数可注入，便于单元测试覆盖。
 */
class OpenFileItemFactory(
    private val queryMeta: (Uri) -> Pair<String?, Long>?
) {

    fun build(uri: Uri): AudioItem {
        val meta = queryMeta(uri)
        val name = meta?.first?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "audio"
        return AudioItemFactory.fromOpenFile(
            uri = uri,
            name = name,
            size = meta?.second ?: 0L
        )
    }
}
