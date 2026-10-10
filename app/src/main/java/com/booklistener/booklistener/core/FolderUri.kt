package com.booklistener.core

import android.net.Uri

/**
 * SAF 文件夹 tree uri 的可读名称提取。
 * 形如 content://com.android.externalstorage.documents/tree/primary%3ABOOK%2F哈利·波特与魔法石，
 * 取 lastPathSegment 解码后剥离 provider 前缀（primary: 等）并**只保留最后一段目录名**：
 * "BOOK/哈利·波特与魔法石" → "哈利·波特与魔法石"（专辑名兜底用文件夹名，不用路径）。
 */
fun folderDisplayName(uri: Uri): String {
    val segment = uri.lastPathSegment ?: return uri.toString()
    val decoded = Uri.decode(segment)
    val afterColon = decoded.substringAfter(':').trim('/')
    if (afterColon.isBlank()) {
        // 根目录（primary:）：回退卷标/前缀
        return decoded.substringBefore(':').ifBlank { decoded }
    }
    return afterColon.substringAfterLast('/')
}
