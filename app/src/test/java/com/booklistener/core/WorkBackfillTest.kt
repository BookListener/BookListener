package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WorkBackfill 作品聚合纯函数测试（v2.2.20 增补）：
 * 「虚拟轨整轨源排除」——被 m4b 章节 / CUE 分轨虚拟轨覆盖的整轨不参与作品聚合，
 * 原始整轨行仍可入库（内容文件列表忠实显示），曲目列表由虚拟轨承担。
 */
class WorkBackfillTest {

    private fun audio(
        uri: String,
        album: String?,
        durationMs: Long? = null,
        rawName: String? = null
    ) = IndexedFile(
        displayName = rawName ?: uri.substringAfterLast('/'),
        uri = uri,
        album = album,
        rawName = rawName ?: uri.substringAfterLast('/'),
        durationMs = durationMs
    )

    @Test
    fun `m4b章节虚拟轨覆盖整本 整轨不参与聚合 曲目只有章节`() {
        val whole = "content://doc/book.m4b"
        val files = listOf(
            audio(whole, "三体", 10_800_000L, "book.m4b"),
            audio("$whole#track_0", "三体", 3_600_000L, "book.m4b#1"),
            audio("$whole#track_3600000", "三体", 3_600_000L, "book.m4b#2"),
            audio("$whole#track_7200000", "三体", 3_600_000L, "book.m4b#3")
        )
        val works = WorkBackfill.buildWorks(files, "e1", "书", "LOCAL")
        assertEquals(1, works.size)
        assertEquals("三体", works[0].title)
        assertEquals("曲目数 = 章节数（整本被覆盖排除）", 3, works[0].songCount)
        assertEquals("总时长 = 章节之和", 10_800_000L, works[0].totalDurationMs)
    }

    @Test
    fun `无虚拟轨时 整轨正常参与聚合`() {
        val files = listOf(audio("content://doc/a.m4a", "专辑A", 100_000L, "a.m4a"))
        val works = WorkBackfill.buildWorks(files, "e1", "文件夹", "LOCAL")
        assertEquals(1, works.size)
        assertEquals("专辑A", works[0].title)
        assertEquals(1, works[0].songCount)
        assertEquals(100_000L, works[0].totalDurationMs)
    }

    @Test
    fun `CUE虚拟轨覆盖整轨同样排除 独立音频不受影响`() {
        val whole = "content://doc/整轨.flac"
        val files = listOf(
            // 模拟整轨也在清单中（实际 CUE 场景已由 CueIndexer 吸收剔除不入库；此处验证聚合兜底）
            audio(whole, "某某专辑", 600_000L, "整轨.flac"),
            audio("$whole#track_0", "某某专辑", 300_000L, "整轨#1"),
            audio("$whole#track_300000", "某某专辑", 300_000L, "整轨#2"),
            audio("content://doc/独立歌.flac", null, 200_000L, "独立歌.flac")
        )
        val works = WorkBackfill.buildWorks(files, "e1", "目录", "LOCAL")
        assertEquals("整轨专辑 + 独立歌 = 2 作品", 2, works.size)
        val albumWork = works.first { it.title == "某某专辑" }
        assertEquals("整轨被覆盖排除，专辑只剩分轨", 2, albumWork.songCount)
        val standalone = works.first { it.title == "目录" }
        assertEquals(1, standalone.songCount)
        assertEquals(200_000L, standalone.totalDurationMs)
    }
}
