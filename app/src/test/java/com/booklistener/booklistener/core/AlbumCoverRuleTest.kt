package com.booklistener.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专辑封面规则测试（v1.6.3 修复"当前专辑没封面却用了其他专辑图"）：
 * 单专辑条目 → 用文件夹封面（entry.coverUri，零 IO）；
 * 多专辑条目 → 只取该专辑内文件的内嵌封面，解析不到返回 null（绝不回退文件夹封面 = 绝不串图）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumCoverRuleTest {

    private val entryCover = "file:///covers/folder-cover.jpg"
    private val manager = CoverManager(
        ApplicationProvider.getApplicationContext(),
        CoverScanner({ _, _ -> null })
    )

    @Test
    fun singleAlbumEntry_usesFolderCover() {
        // 条目只聚合出 1 个专辑：文件夹封面即专辑封面（零 IO，不解析内嵌）
        assertEquals(entryCover, manager.resolveAlbumCover(entryCover, albumCountInEntry = 1, firstAudioUri = "content://audio/1.mp3"))
    }

    @Test
    fun multiAlbumEntry_neverFallsBackToFolderCover_whenNoEmbeddedArt() {
        // 多专辑条目 + 专辑内无内嵌封面（Robolectric 无 provider 解析失败）：
        // 必须返回 null（UI 渐变占位），绝不能把文件夹封面给该专辑用 = 串图回归点
        assertNull(manager.resolveAlbumCover(entryCover, albumCountInEntry = 3, firstAudioUri = "content://audio/1.mp3"))
    }

    @Test
    fun multiAlbumEntry_noFirstAudio_returnsNull() {
        assertNull(manager.resolveAlbumCover(entryCover, albumCountInEntry = 2, firstAudioUri = null))
    }

    @Test
    fun zeroAlbumsEdge_treatedAsSingle() {
        // 空列表边界：视为单专辑语义（沿用文件夹封面，无害）
        assertEquals(entryCover, manager.resolveAlbumCover(entryCover, albumCountInEntry = 0, firstAudioUri = null))
    }
}
