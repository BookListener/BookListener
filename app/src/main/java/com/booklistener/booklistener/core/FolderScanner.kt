package com.booklistener.core

import android.net.Uri
import android.provider.DocumentsContract

/** SAF 目录下的一个条目（文件夹或文件） */
data class FolderEntry(
    val displayName: String,
    val mimeType: String,
    val docId: String,
    val isDirectory: Boolean,
    val size: Long
)

/**
 * 扫描 SAF 文件夹中的可播放音频（纯逻辑，查询与 Uri 构造由调用方注入，可单测）。
 * Android 规范：先以 getTreeDocumentId 取根文档 id，再逐层
 * buildChildDocumentsUriUsingTree 列举子项；目录递归（深度上限防爆炸），
 * 文件按 mimeType 以 "audio/" 开头过滤。
 *
 * v1.6.0：支持标签解析注入（[tagResolver]，默认不解析）——displayName 优先标签 title，
 * 回退去扩展名文件名；扫描出口统一按「disc → track → 名称自然序」排序
 * （列表顺序 == 播放顺序，单点排序结构性避免两处不一致）。
 */
class FolderScanner(
    private val listChildren: (treeUri: String, parentDocId: String) -> List<FolderEntry>?,
    private val treeDocumentId: (treeUri: String) -> String = { defaultTreeDocumentId(it) },
    private val fileUriBuilder: (treeUri: String, docId: String) -> String =
        { tree, doc -> defaultFileUri(tree, doc) },
    private val tagResolver: (uri: String, fileName: String) -> TagResolver.AudioTags? = { _, _ -> null },
    /** v5.0（spec: cue-virtual-tracks）：读 .cue 文件文本（SAF ContentResolver 等），null = 读取失败/无权限 */
    private val readText: (uri: String) -> String? = { null }
) {

    fun scan(treeUri: String, maxDepth: Int = 3): List<AudioItem> {
        val collected = mutableListOf<Pair<AudioItem, AudioSortKey>>()
        walk(treeUri, treeDocumentId(treeUri), 0, maxDepth, collected)
        // 出口统一自然排序（disc → track → 名称），播放顺序与列表一致；稳定排序保留扫描原序
        return collected.sortedWith(compareBy(AudioSortKey.byName) { it.second }).map { it.first }
    }

    /** v5.0：扫描目录内 .cue 文件候选（文本已读取；读取失败的文件不产出） */
    fun scanCues(treeUri: String, maxDepth: Int = 3): List<CueIndexer.CueCandidate> {
        val cues = mutableListOf<CueIndexer.CueCandidate>()
        walkCues(treeUri, treeDocumentId(treeUri), 0, maxDepth, cues)
        return cues
    }

    private fun walkCues(
        treeUri: String,
        parentDocId: String,
        depth: Int,
        maxDepth: Int,
        out: MutableList<CueIndexer.CueCandidate>
    ) {
        if (depth > maxDepth) return
        val children = listChildren(treeUri, parentDocId) ?: return
        for (entry in children) {
            if (entry.isDirectory) {
                walkCues(treeUri, entry.docId, depth + 1, maxDepth, out)
            } else if (isCueFile(entry.displayName)) {
                val fileUri = fileUriBuilder(treeUri, entry.docId)
                val text = readText(fileUri)
                if (text != null) {
                    out += CueIndexer.CueCandidate(
                        cueUri = fileUri,
                        displayName = entry.displayName,
                        text = text
                    )
                }
            }
        }
    }

    private fun walk(
        treeUri: String,
        parentDocId: String,
        depth: Int,
        maxDepth: Int,
        out: MutableList<Pair<AudioItem, AudioSortKey>>
    ) {
        if (depth > maxDepth) return
        val children = listChildren(treeUri, parentDocId) ?: return
        for (entry in children) {
            if (entry.isDirectory) {
                walk(treeUri, entry.docId, depth + 1, maxDepth, out)
            } else if (isPlayableAudio(entry)) {
                val fileUri = fileUriBuilder(treeUri, entry.docId)
                val tags = tagResolver(fileUri, entry.displayName)
                val display = displayNameFor(tags, entry.displayName)
                out.add(
                    AudioItemFactory.fromFolderFile(
                        docId = entry.docId,
                        displayName = display,
                        fileUri = fileUri,
                        size = entry.size,
                        tags = tags,
                        rawName = entry.displayName
                    ) to AudioSortKey(
                        displayName = display,
                        disc = tags?.disc,
                        track = tags?.track
                    )
                )
            }
        }
    }

    /** 显示名：标签 title 优先，否则去扩展名文件名（".mp3" 形式保留原名） */
    private fun displayNameFor(tags: TagResolver.AudioTags?, fileName: String): String {
        val title = tags?.title?.takeIf { it.isNotBlank() }
        if (title != null) return title
        val nameWithoutExt = fileName.substringBeforeLast('.')
        return nameWithoutExt.takeUnless { it.isEmpty() } ?: fileName
    }

    /**
     * 可播放音频判定：mimeType 以 "audio/" 开头即算；
     * SAF 提供方对未知类型常返回 application/octet-stream 或空 mime，
     * 此时按文件扩展名兜底（Android 播放器常见做法），避免漏列。
     */
    private fun isPlayableAudio(entry: FolderEntry): Boolean {
        if (entry.displayName.isBlank()) return false
        if (entry.mimeType.startsWith(AUDIO_MIME_PREFIX)) return true
        val ext = entry.displayName.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    /** .cue 索引文件判定（大小写不敏感） */
    private fun isCueFile(name: String): Boolean =
        name.isNotBlank() && name.substringAfterLast('.', "").lowercase() == "cue"

    companion object {
        private const val AUDIO_MIME_PREFIX = "audio/"
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr"
        )

        fun defaultTreeDocumentId(treeUri: String): String =
            DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))

        /** SAF 树内文件的播放 Uri（Android 规范：buildDocumentUriUsingTree） */
        fun defaultFileUri(treeUri: String, docId: String): String =
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId).toString()
    }
}
