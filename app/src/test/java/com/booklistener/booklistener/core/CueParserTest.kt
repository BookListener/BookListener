package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CueParser 单测（spec: cue-virtual-tracks · CUE 解析）：
 * 标准专辑 / 多 FILE 段 / 畸形容错 / 时间换算（75 帧）/ INDEX 00 兜底 / 轨级覆盖。
 */
class CueParserTest {

    private val standardCue = """
        PERFORMER "某某乐队"
        TITLE "某某专辑"
        FILE "专辑名.flac" WAVE
          TRACK 01 AUDIO
            TITLE "第一首"
            PERFORMER "乐队A"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "第二首"
            INDEX 01 04:32:15
          TRACK 03 AUDIO
            TITLE "第三首"
            INDEX 01 09:08:40
    """.trimIndent()

    @Test
    fun standardCue_parsesThreeTracksWithDerivedEnds() {
        val sheet = CueParser.parse(standardCue)
        assertEquals("某某专辑", sheet.album)
        assertEquals("某某乐队", sheet.performer)
        assertEquals("专辑名.flac", sheet.referencedFile)
        assertEquals(3, sheet.tracks.size)

        val t1 = sheet.tracks[0]
        assertEquals(1, t1.number)
        assertEquals("第一首", t1.title)
        assertEquals("乐队A", t1.performer)   // 轨级覆盖全局
        assertEquals(0L, t1.startTimeMs)
        assertEquals(272_200L, t1.endTimeMs)  // 04:32:15 = 4*60000 + 32*1000 + 15*1000/75 = 272200

        val t2 = sheet.tracks[1]
        assertEquals("第二首", t2.title)
        assertEquals("某某乐队", t2.performer) // 继承全局
        assertEquals(272_200L, t2.startTimeMs)
        assertEquals(548_533L, t2.endTimeMs)  // 09:08:40 = 9*60000+8*1000+40*1000/75 = 548533

        val t3 = sheet.tracks[2]
        assertEquals("第三首", t3.title)
        assertEquals(548_533L, t3.startTimeMs)
        assertNull(t3.endTimeMs)              // 末轨播到文件尾
    }

    @Test
    fun multiFileSections_tracksBelongToTheirFile() {
        val cue = """
            TITLE "双CD合集"
            FILE "CD1.flac" WAVE
              TRACK 01 AUDIO
                TITLE "A面一"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "A面二"
                INDEX 01 03:00:00
            FILE "CD2.flac" WAVE
              TRACK 03 AUDIO
                TITLE "B面一"
                INDEX 01 00:00:00
        """.trimIndent()
        val sheet = CueParser.parse(cue)
        assertEquals(3, sheet.tracks.size)
        assertEquals("CD1.flac", sheet.tracks[0].referencedFile)
        assertEquals("CD1.flac", sheet.tracks[1].referencedFile)
        assertEquals("CD2.flac", sheet.tracks[2].referencedFile)  // 不串挂到 CD1
        assertEquals(0L, sheet.tracks[2].startTimeMs)             // 新 FILE 段内 0 起
        // 跨段：CD1 段末轨 end=null（播到 CD1 文件尾），不以 CD2 起始回填
        assertNull(sheet.tracks[1].endTimeMs)
        // 末 FILE 段为 referencedFile 兜底
        assertEquals("CD2.flac", sheet.referencedFile)
    }

    @Test
    fun malformedCue_skipsTrackWithoutIndex_ignoresPregap() {
        val cue = """
            TITLE "畸形专辑"
            FILE "x.flac" WAVE
              TRACK 01 AUDIO
                TITLE "正常轨"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "无索引轨"
              TRACK 03 AUDIO
                PREGAP 00:00:02
                TITLE "间隙轨"
                INDEX 01 01:00:00
        """.trimIndent()
        val sheet = CueParser.parse(cue)
        assertEquals(2, sheet.tracks.size)       // 无 INDEX 的 TRACK 02 被跳过
        assertEquals("正常轨", sheet.tracks[0].title)
        assertEquals("间隙轨", sheet.tracks[1].title)
        assertEquals(60_000L, sheet.tracks[1].startTimeMs)  // PREGAP 不影响起始
    }

    @Test
    fun cueTime_convertsWith75FramesPerSecond() {
        assertEquals(0L, CueParser.parseCueTime("00:00:00"))
        assertEquals(272_200L, CueParser.parseCueTime("04:32:15"))
        assertEquals(60_000L, CueParser.parseCueTime("01:00:00"))
        assertEquals(60_013L, CueParser.parseCueTime("01:00:01"))  // 1 帧 = 1000/75 ≈ 13ms
        assertNull(CueParser.parseCueTime("12:34"))                 // 非 3 段
        assertNull(CueParser.parseCueTime("ab:cd:ef"))              // 非数字
    }

    @Test
    fun trackWithOnlyIndex00_usesZeroAsStart() {
        val cue = """
            TITLE "间隙专辑"
            FILE "g.flac" WAVE
              TRACK 01 AUDIO
                TITLE "仅00轨"
                INDEX 00 00:00:00
                INDEX 01 00:02:00
              TRACK 02 AUDIO
                TITLE "纯00轨"
                INDEX 00 01:00:00
        """.trimIndent()
        val sheet = CueParser.parse(cue)
        assertEquals(2, sheet.tracks.size)
        assertEquals(2_000L, sheet.tracks[0].startTimeMs)  // INDEX 01 优先
        assertEquals(60_000L, sheet.tracks[1].startTimeMs) // 无 01 用 INDEX 00 兜底
    }

    @Test
    fun emptyOrGarbage_returnsEmptyTracks() {
        assertTrue(CueParser.parse("").tracks.isEmpty())
        assertTrue(CueParser.parse("REM 注释\nFILE \"a.flac\" WAVE\n").tracks.isEmpty())
    }
}
