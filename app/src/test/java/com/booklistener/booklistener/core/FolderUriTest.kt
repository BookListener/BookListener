package com.booklistener.core

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 文件夹名提取测试（v1.7.0 修复）：
 * 无 tag 时专辑名兜底用文件夹名而非路径——tree uri 的 lastPathSegment 解码后
 * 只取最后一段目录名；根目录回退卷标前缀。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderUriTest {

    private fun tree(encoded: String): Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/$encoded")

    @Test
    fun nestedPath_usesLastSegmentOnly() {
        // 截图场景：BOOK/哈利·波特与魔法石 → 只取文件夹名
        val uri = tree("primary%3ABOOK%2F%E5%93%88%E5%88%A9%C2%B7%E6%B3%A2%E7%89%B9%E4%B8%8E%E9%AD%94%E6%B3%95%E7%9F%B3")
        assertEquals("哈利·波特与魔法石", folderDisplayName(uri))
    }

    @Test
    fun singleSegment_folderName() {
        assertEquals("Download", folderDisplayName(tree("primary%3ADownload")))
    }

    @Test
    fun nonPrimaryVolume_keepsVolumePrefixSegment() {
        assertEquals("Music", folderDisplayName(tree("sd%3A%2FMusic")))
    }

    @Test
    fun rootFolder_fallsBackToVolumeLabel() {
        assertEquals("primary", folderDisplayName(tree("primary%3A")))
    }

    @Test
    fun threeLevelPath_keepsLeafOnly() {
        val uri = tree("primary%3Aouter%2Fmid%2Fleaf")
        assertEquals("leaf", folderDisplayName(uri))
    }
}
