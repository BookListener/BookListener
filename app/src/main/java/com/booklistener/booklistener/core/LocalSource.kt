package com.booklistener.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 本地来源（SAF 文件夹）实现。
 *
 * 只负责递归列目录，返回 SourceFile 列表（含相对路径）；
 * 不做标签解析、CUE 收集、索引写入。
 */
class LocalSource(
    context: Context,
    private val maxDepth: Int = 10
) : Source {

    override val type: SourceType = SourceType.LOCAL

    private val resolver = context.applicationContext.contentResolver

    override suspend fun listFiles(rootUri: String): List<SourceFile> {
        val tree = Uri.parse(rootUri)
        val rootDocId = DocumentsContract.getTreeDocumentId(tree)
        val out = mutableListOf<SourceFile>()
        walk(tree, rootDocId, "", 0, out)
        return out
    }

    private fun walk(
        tree: Uri,
        parentDocId: String,
        dirRelativePath: String,
        depth: Int,
        out: MutableList<SourceFile>
    ) {
        if (depth > maxDepth) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                val docId = c.getString(idIdx) ?: continue
                val mime = c.getString(mimeIdx) ?: ""
                val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mime
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                out.add(
                    SourceFile(
                        name = name,
                        path = fileUri.toString(),
                        relativePath = dirRelativePath,
                        isDirectory = isDir,
                        sizeBytes = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null,
                        lastModified = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else null
                    )
                )
                if (isDir) {
                    val childPath = if (dirRelativePath.isEmpty()) name else "$dirRelativePath/$name"
                    walk(tree, docId, childPath, depth + 1, out)
                }
            }
        }
    }
}
