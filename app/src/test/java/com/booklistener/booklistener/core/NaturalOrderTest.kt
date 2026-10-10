package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自然排序核心测试（v1.6.0）：
 * 参照文档2 的正反例 + 本项目边界（全角数字按文本段、空串、前导零、"01" 形式）。
 * 断言 compare 与预分词 bySortKey 结果一致（双入口等价）。
 */
class NaturalOrderTest {

    private fun assertOrder(expected: List<String>, shuffled: List<String>) {
        val byCompare = shuffled.sortedWith(NaturalOrder.byName)
        val byKey = shuffled.sortedWith(NaturalOrder.bySortKey)
        assertEquals(expected, byCompare)
        assertEquals("bySortKey 与 compare 结果不一致", expected, byKey)
    }

    private fun assertLess(a: String, b: String) {
        assertTrue("expected '$a' < '$b'", NaturalOrder.compare(a, b) < 0)
    }

    @Test
    fun naturalOrder_document2_cases() {
        // 文档2 典型正例
        assertOrder(listOf("1.mp3", "2.mp3", "10.mp3"), listOf("10.mp3", "2.mp3", "1.mp3"))
        assertOrder(listOf("a1.mp3", "a2.mp3", "a10.mp3"), listOf("a10.mp3", "a2.mp3", "a1.mp3"))
        assertOrder(listOf("file1.mp3", "file2.mp3", "file10.mp3"), listOf("file10.mp3", "file2.mp3", "file1.mp3"))
        assertOrder(listOf("第1章", "第2章", "第10章"), listOf("第10章", "第2章", "第1章"))
    }

    @Test
    fun naturalOrder_mixedTokens() {
        assertLess("a1b", "a2b")
        assertLess("a1b", "a10b")
        assertLess("1a", "1b") // 数字相等后比文本
        assertLess("1a", "2a") // 数字段在前优先
    }

    @Test
    fun naturalOrder_digitFirst_beforeText() {
        // 数字段恒在文本段前（资源管理器语义）
        assertLess("2a", "a")
        assertOrder(listOf("2.mp3", "a.mp3"), listOf("a.mp3", "2.mp3"))
    }

    @Test
    fun naturalOrder_prefixShortFirst() {
        assertLess("abc", "abcd")
        assertLess("01", "010")
    }

    @Test
    fun naturalOrder_leadingZeros_fewerFirstWhenEqual() {
        // 数值相等时前导零少者在前
        assertLess("2", "02")
        assertOrder(listOf("2.mp3", "02.mp3"), listOf("02.mp3", "2.mp3"))
    }

    @Test
    fun naturalOrder_ignoreCase() {
        assertEquals(0, NaturalOrder.compare("ABC.mp3", "abc.mp3"))
        assertLess("a", "B") // ignoreCase 下 a==A < B
        assertOrder(listOf("A.mp3", "b.mp3"), listOf("b.mp3", "A.mp3"))
    }

    @Test
    fun naturalOrder_largeNumbers_noOverflow() {
        // 20 位数字：比位数/字面量，不经 toLong
        assertLess("12345678901234567890", "12345678901234567891")
        assertLess("9.mp3", "123456789012345678901.mp3") // 位数少在前
    }

    @Test
    fun naturalOrder_fullWidthDigits_treatedAsText() {
        // 全角数字按文本段处理（本项目取舍），ASCII 数字段恒在前
        assertLess("1", "１")
    }

    @Test
    fun naturalOrder_blankAndEmpty_safe() {
        assertEquals(0, NaturalOrder.compare("", ""))
        assertLess("", "a")
        assertTrue(NaturalOrder.compare("a", "") > 0)
    }

    @Test
    fun naturalOrder_dotFile_extensionStrippedHandling() {
        // ".mp3" 形（去扩展名后为空）：按文本段处理不崩溃
        assertEquals(0, NaturalOrder.compare(".mp3", ".mp3"))
        assertLess(".mp3", "a")
    }

    @Test
    fun audioSortKey_discThenTrackThenName() {
        val list = listOf(
            AudioSortKey("B", disc = 1, track = 2),
            AudioSortKey("C", disc = 1, track = 3),
            AudioSortKey("A", disc = 1, track = 1),
            AudioSortKey("Z", disc = 2, track = 1)
        )
        val sorted = list.sortedWith(AudioSortKey.byName)
        assertEquals(listOf("A", "B", "C", "Z"), sorted.map { it.displayName })
    }

    @Test
    fun audioSortKey_missingDiscTrack_sortedAfter() {
        val list = listOf(
            AudioSortKey("NoTag2", disc = null, track = null),
            AudioSortKey("Tagged", disc = 1, track = 1),
            AudioSortKey("NoTag1", disc = null, track = null)
        )
        val sorted = list.sortedWith(AudioSortKey.byName)
        assertEquals(listOf("Tagged", "NoTag1", "NoTag2"), sorted.map { it.displayName })
    }

    @Test
    fun audioSortKey_trackOnly_noDisc() {
        val list = listOf(
            AudioSortKey("T10", track = 10),
            AudioSortKey("T2", track = 2),
            AudioSortKey("NoTrack", track = null)
        )
        val sorted = list.sortedWith(AudioSortKey.byName)
        assertEquals(listOf("T2", "T10", "NoTrack"), sorted.map { it.displayName })
    }

    @Test
    fun audioSortKey_sameDiscTrack_nameNaturalOrder() {
        val list = listOf(
            AudioSortKey("track2.mp3", disc = 1, track = 2),
            AudioSortKey("track10.mp3", disc = 1, track = 2)
        )
        val sorted = list.sortedWith(AudioSortKey.byName)
        assertEquals(listOf("track2.mp3", "track10.mp3"), sorted.map { it.displayName })
    }
}
