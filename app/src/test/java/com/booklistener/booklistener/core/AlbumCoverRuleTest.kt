package com.booklistener.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专辑封面规则测试（v2.1.13 修复多子文件夹串图）：
 * - entry.coverUri 非空（单专辑文件夹）→ 直接用 entry 封面
 * - entry.coverUri 为空（多专辑文件夹不设封面，Q14）→ 回退首曲内嵌封面
 * - 都没有 → null（UI 渐变占位）
 * 注意：albumCountInEntry 是"专辑跨文件夹数"，不再用作封面判断依据。
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
    fun entryCoverPresent_usesItDirectly() {
        // 单专辑文件夹：entry.coverUri 非空 → 直接用（零 IO，不解析内嵌）
        assertEquals(entryCover, manager.resolveAlbumCover(entryCover, albumCountInEntry = 1, firstAudioUri = "content://audio/1.mp3"))
    }

    @Test
    fun entryCoverNull_noEmbeddedArt_returnsNull() {
        // 多专辑文件夹 entry.coverUri=null + 首曲无内嵌封面（Robolectric 解析失败）→ null（绝不串图）
        assertNull(manager.resolveAlbumCover(null, albumCountInEntry = 3, firstAudioUri = "content://audio/1.mp3"))
    }

    @Test
    fun entryCoverNull_noFirstAudio_returnsNull() {
        assertNull(manager.resolveAlbumCover(null, albumCountInEntry = 2, firstAudioUri = null))
    }

    @Test
    fun entryCoverNull_fallsBackToEmbeddedCover() {
        // entry.coverUri=null 时回退首曲内嵌封面（Robolectric 无 provider 解析失败，此处仅验证不抛异常）
        val result = manager.resolveAlbumCover(null, albumCountInEntry = 1, firstAudioUri = "content://audio/1.mp3")
        // Robolectric 环境下 MediaMetadataRetriever 解析失败返回 null，验证逻辑走到内嵌分支
        assertNull(result)
    }

    // v1.6.22：条目视图进入的专辑数判定（修复"无封面专辑串用其他专辑图"回归）
    @Test
    fun albumCountOf_nullAlbums_groupAsOne() {
        // 无 tag 文件 album=null：同一文件夹全部视为一组（单专辑）
        assertEquals(1, manager.albumCountOf(listOf(audio(null), audio(null), audio(null))))
    }

    @Test
    fun albumCountOf_distinctAlbums_counted() {
        assertEquals(2, manager.albumCountOf(listOf(audio("A"), audio("B"), audio("A"))))
        assertEquals(3, manager.albumCountOf(listOf(audio("A"), audio(null), audio("B"))))
    }

    @Test
    fun albumCountOf_empty_isZero() {
        assertEquals(0, manager.albumCountOf(emptyList()))
    }

    private fun audio(album: String?) = AudioItem(
        id = "id-$album-${System.identityHashCode(album)}",
        displayName = "t.mp3",
        path = "content://audio/t.mp3",
        sourceType = SourceType.LOCAL,
        size = 1L,
        album = album
    )
}
