package com.booklistener.core

/**
 * 封面选择纯逻辑：在文件夹条目清单中找专辑封面图片。
 * 优先级（计划确认）：文件夹内常见命名图片（cover/folder/front/AlbumArt*）
 * → 根目录任意图片 → 无封面（由调用方再尝试内嵌封面/占位）。
 * 查询由调用方注入（SAF DocumentsContract），可单测。
 */
class CoverScanner(
    private val listChildren: (treeUri: String, parentDocId: String) -> List<FolderEntry>?,
    private val treeDocumentId: (treeUri: String) -> String = { FolderScanner.defaultTreeDocumentId(it) },
    private val fileUriBuilder: (treeUri: String, docId: String) -> String =
        { tree, doc -> FolderScanner.defaultFileUri(tree, doc) }
) {

    /** 返回封面图片 uri；无则返回 null（只扫根目录，避免深层遍历开销） */
    fun findCover(treeUri: String): String? {
        val children = listChildren(treeUri, treeDocumentId(treeUri)) ?: return null
        var anyImage: String? = null
        for (entry in children) {
            if (entry.isDirectory) continue
            val name = entry.displayName.lowercase()
            if (name in PREFERRED_NAMES) return fileUriBuilder(treeUri, entry.docId)
            if (isImageName(name) && anyImage == null) anyImage = fileUriBuilder(treeUri, entry.docId)
        }
        return anyImage
    }

    private fun isImageName(name: String): Boolean =
        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".bmp")

    companion object {
        /** 常见专辑封面命名（小写） */
        val PREFERRED_NAMES = setOf(
            "cover.jpg", "cover.png", "cover.jpeg", "cover.webp",
            "folder.jpg", "folder.png", "folder.jpeg",
            "front.jpg", "front.png",
            "albumart.jpg", "albumart.png", "album.jpg", "album.png",
            "artwork.jpg", "artwork.png"
        )
    }
}
