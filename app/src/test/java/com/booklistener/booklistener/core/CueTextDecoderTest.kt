package com.booklistener.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * v2.1.4 .cue 文本解码测试：
 * 1) GBK/GB18030 编码中文 cue → 正确还原（不再乱码）
 * 2) UTF-8 cue → 原样
 * 3) UTF-8 BOM → 剥离后还原
 * 4) 纯 ASCII → 原样
 */
class CueTextDecoderTest {

    private val gbk = Charset.forName("GB18030")

    @Test
    fun gbkEncodedChineseCue_decodesCorrectly() {
        val cue = """
            PERFORMER "某某乐队"
            TITLE "某某专辑"
            FILE "整轨.flac" WAVE
              TRACK 01 AUDIO
                TITLE "第一首"
                INDEX 01 00:00:00
        """.trimIndent()
        val bytes = cue.toByteArray(gbk)
        val decoded = CueTextDecoder.decode(bytes)
        assertEquals("GBK 中文 cue 必须完整还原", cue, decoded)
        assertTrue(decoded.contains("某某乐队"))
        assertTrue(decoded.contains("整轨.flac"))
    }

    @Test
    fun utf8EncodedCue_passesThrough() {
        val cue = "TITLE \"某专辑\"\nFILE \"whole.flac\" WAVE\n"
        assertEquals(cue, CueTextDecoder.decode(cue.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun utf8BomCue_strippedAndDecoded() {
        val cue = "PERFORMER \"歌手\"\n"
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + cue.toByteArray(Charsets.UTF_8)
        assertEquals("UTF-8 BOM 必须剥离", cue, CueTextDecoder.decode(withBom))
    }

    @Test
    fun asciiCue_passesThrough() {
        val cue = "TITLE \"Best Hits\"\nFILE \"whole.flac\" WAVE\n"
        assertEquals(cue, CueTextDecoder.decode(cue.toByteArray(Charsets.US_ASCII)))
    }
}
