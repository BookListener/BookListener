package com.booklistener.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CoverManager 封面解析策略测试：
 * 文件夹图片优先（不触碰内嵌封面提取）；无文件夹图片 → 尝试首曲内嵌封面（Robolectric 无
 * content provider 时自然返回 null，覆盖"无封面回退"）；作者解析同理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverManagerTest {

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    private fun scannerWith(vararg entries: FolderEntry): CoverScanner =
        CoverScanner({ _, _ -> entries.toList() })

    @Test
    fun resolveFolderCover_prefersFolderImage() {
        val scanner = scannerWith(
            FolderEntry("song.mp3", "audio/mpeg", "doc1", false, 100L),
            FolderEntry("cover.jpg", "image/jpeg", "doc2", false, 10L)
        )
        val manager = CoverManager(ApplicationProvider.getApplicationContext(), scanner)
        val cover = manager.resolveFolderCover(treeUri, "content://audio/1.mp3")
        assertEquals(
            "存在 cover.jpg 时应直接返回文件夹图片，不再走内嵌提取",
            "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/doc2",
            cover
        )
    }

    @Test
    fun resolveFolderCover_anyImageInRoot_asFallback() {
        val scanner = scannerWith(
            FolderEntry("song.mp3", "audio/mpeg", "doc1", false, 100L),
            FolderEntry("random.png", "image/png", "doc2", false, 10L)
        )
        val manager = CoverManager(ApplicationProvider.getApplicationContext(), scanner)
        assertEquals(
            "根目录任意图片作为回退封面",
            "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/doc2",
            manager.resolveFolderCover(treeUri, "content://audio/1.mp3")
        )
    }

    @Test
    fun resolveFolderCover_noImage_triesEmbeddedAndFallsBackNull() {
        val scanner = scannerWith(
            FolderEntry("song.mp3", "audio/mpeg", "doc1", false, 100L)
        )
        val manager = CoverManager(ApplicationProvider.getApplicationContext(), scanner)
        // 无文件夹图片 → 尝试内嵌封面（测试环境无 provider → null → 回退占位）
        assertNull(manager.resolveFolderCover(treeUri, "content://audio/1.mp3"))
    }

    @Test
    fun resolveArtist_noProvider_returnsNull() {
        val manager = CoverManager(
            ApplicationProvider.getApplicationContext(),
            scannerWith(FolderEntry("song.mp3", "audio/mpeg", "doc1", false, 100L))
        )
        assertNull(manager.resolveArtist("content://audio/1.mp3"))
    }
}
