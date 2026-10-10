package com.booklistener.core

import android.net.Uri

/**
 * AudioItem 统一构造（霰弹收敛 A5）：
 * 文件夹扫描 / 打开文件 / MediaStore / WebDAV / 阿里云盘 / 搜索结果 六类条目
 * 统一由此处定义 id 规则与缺省值（size 缺省 0L），禁止在各源中散落构造。
 */
object AudioItemFactory {

    /** SAF 文件夹扫描条目（LOCAL，id 前缀 folder_）；tags 提供标签与时长，rawName 为原始文件名 */
    fun fromFolderFile(
        docId: String,
        displayName: String,
        fileUri: String,
        size: Long,
        tags: TagResolver.AudioTags? = null,
        rawName: String? = null
    ): AudioItem =
        AudioItem(
            id = "folder_$docId",
            displayName = displayName,
            path = fileUri,
            sourceType = SourceType.LOCAL,
            size = size,
            album = tags?.album,
            artist = tags?.artist,
            track = tags?.track,
            disc = tags?.disc,
            durationMs = tags?.durationMs,
            rawName = rawName
        )

    /** SAF 单文件打开条目（LOCAL，id 前缀 open_） */
    fun fromOpenFile(uri: Uri, name: String, size: Long): AudioItem =
        AudioItem(
            id = "open_$uri",
            displayName = name,
            path = uri.toString(),
            sourceType = SourceType.LOCAL,
            size = size
        )

    /** MediaStore 本地曲库条目（LOCAL，id 前缀 local_） */
    fun fromMediaStore(
        id: Long,
        displayName: String,
        uri: Uri,
        size: Long,
        album: String? = null,
        artist: String? = null,
        track: Int? = null,
        durationMs: Long? = null,
        rawName: String? = null
    ): AudioItem =
        AudioItem(
            id = "local_$id",
            displayName = displayName,
            path = uri.toString(),
            sourceType = SourceType.LOCAL,
            size = size,
            album = album,
            artist = artist,
            track = track,
            durationMs = durationMs,
            rawName = rawName
        )

    /** WebDAV 目录条目（WEBDAV，id 前缀 webdav_ + href 哈希）；rawName 保留原始文件名 */
    fun fromWebDav(href: String, displayName: String, size: Long, rawName: String? = null): AudioItem =
        AudioItem(
            id = "webdav_${href.hashCode()}",
            displayName = displayName,
            path = href,
            sourceType = SourceType.WEBDAV,
            size = size,
            rawName = rawName
        )

    /** 阿里云盘目录条目（ALIYUN，id/path 均为 file_id）；rawName 保留原始文件名 */
    fun fromAliyun(fileId: String, displayName: String, size: Long, rawName: String? = null): AudioItem =
        AudioItem(
            id = fileId,
            displayName = displayName,
            path = fileId,
            sourceType = SourceType.ALIYUN,
            size = size,
            rawName = rawName
        )

    /** 搜索结果条目（来源随 hit，id 前缀 search_，无额外元数据 size=0L） */
    fun fromSearch(sourceType: SourceType, displayName: String, uri: String): AudioItem =
        AudioItem(
            id = "search_$uri",
            displayName = displayName,
            path = uri,
            sourceType = sourceType,
            size = 0L
        )
}
