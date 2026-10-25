package com.booklistener.booklistener.core

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.DefaultExtractorsFactory
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.IOException

/**
 * Media3 提取器直驱最小验证探针：
 * 不拉起播放器，直接驱动 DefaultExtractorsFactory 的提取器读取真实测试音频，
 * 收集 TrackOutput.format() 里携带的元数据条目（ID3 帧 / MP4 ilst MdtaMetadataEntry），
 * 验证 Media3 1.4.0 对 TXXX、©ser/©sno、stik 的真实可达性与键名拼写。
 *
 * 测试资源：
 *   tagprobe/probe_txxx.mp3  —— ID3v2.3，含 TIT2/TPE1/TPE2/TRCK/TPOS/TCON/TXXX(SERIES)/TXXX(SERIESNUMBER)
 *   tagprobe/probe_custom2.m4a —— M4A，含 ©nam/©ART/©alb/aART/trkn/dis/©gen + 手工注入 ©ser/©sno/stik
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaMetadataProbeTest {

    @Test
    fun dumpMp3Metadata() {
        val lines = probeResource("tagprobe/probe_txxx.mp3")
        println("===== MP3 METADATA DUMP (Media3 1.4.0 extractor direct) =====")
        lines.forEach { println("  $it") }
        assertTrue("mp3 提取器未产出任何元数据条目", lines.isNotEmpty())

        // TXXX：真实帧 id 与 description/value 拆分
        val txxx = lines.filter { it.startsWith("ID3[TXXX]") }
        assertTrue("TXXX 帧未达（帧类或解码路径错误）", txxx.isNotEmpty())
        println("  [TXXX 全量观察] " + txxx.joinToString(" | "))
        // 规范 \0 分隔的 TXXX：desc 与 value 完整拆分（本项目 CUE/系列字段依赖此语义）
        assertTrue("TXXX(SERIES) desc/value 拆分失败，实际：$txxx",
            txxx.any { it.contains("desc=SERIES") && it.contains("哈利·波特系列") })

        // 标准文本帧
        assertTrue("TIT2 缺失", lines.any { it.startsWith("ID3[TIT2]") && it.contains("大难不死的男孩") })
        assertTrue("TPE2（专辑艺术家）缺失", lines.any { it.startsWith("ID3[TPE2]") && it.contains("J.K. Rowling") })
        assertTrue("TRCK 缺失", lines.any { it.startsWith("ID3[TRCK]") && it.contains("1/17") })
        assertTrue("TPOS 缺失", lines.any { it.startsWith("ID3[TPOS]") && it.contains("1/7") })
        assertTrue("TCON 缺失", lines.any { it.startsWith("ID3[TCON]") && it.contains("Audiobook") })
    }

    @Test
    fun dumpM4aMetadata() {
        val lines = probeResource("tagprobe/probe_custom2.m4a")
        println("===== M4A METADATA DUMP (Media3 1.4.0 extractor direct) =====")
        lines.forEach { println("  $it") }
        assertTrue("m4a 提取器未产出任何元数据条目", lines.isNotEmpty())

        // iTunes 标准文本原子：Media3 1.4.0 映射为 ID3 等价帧（TIT2/TPE1/TPE2/TALB 等）
        assertTrue("©nam 缺失（应映射为 ID3[TIT2]）", lines.any { it.startsWith("ID3[TIT2]") && it.contains("大难不死的男孩") })
        assertTrue("©ART 缺失（应映射为 ID3[TPE1]）", lines.any { it.startsWith("ID3[TPE1]") && it.contains("朗读者甲") })
        assertTrue("©alb 缺失（应映射为 ID3[TALB]）", lines.any { it.startsWith("ID3[TALB]") && it.contains("哈利·波特与魔法石") })
        assertTrue("aART 缺失（应映射为 ID3[TPE2]）", lines.any { it.startsWith("ID3[TPE2]") && it.contains("J.K. Rowling") })

        // 文档关注的自定义/扩展原子
        val ser = lines.filter { it.startsWith("MP4[©ser]") }
        // 关键验证结论（Media3 1.4.0 行为，锁死为断言）：
        // Mp4Extractor 只映射 iTunes 标准原子（©nam/©ART/©alb/aART/trkn/dis/©gen），
        // ©ser/©sno/stik 等自定义/扩展原子不输出——文档"只有认你键名的播放器才生效"在此成立。
        println("  [验证结论] ©ser/©sno/stik 在 Media3 1.4.0 提取器直驱下不可达：" +
            ser.joinToString(" | ") + " / " +
            lines.filter { it.startsWith("MP4[©sno]") }.joinToString(" | ") + " / " +
            lines.filter { it.startsWith("MP4[stik]") }.joinToString(" | "))
        assertTrue("©ser 意外可达（Media3 行为已变，需重新评估）", lines.none { it.contains("©ser") })
        assertTrue("©sno 意外可达（Media3 行为已变，需重新评估）", lines.none { it.contains("©sno") })
        assertTrue("stik 意外可达（Media3 行为已变，需重新评估）", lines.none { it.contains("stik]") })
    }

    // ---------- 直驱实现 ----------

    private fun probeResource(name: String): List<String> {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
        return probeWithTimeout(bytes)
    }

    /** 独立线程 + 超时保险：提取器与探针输入交互异常时避免测试死循环。 */
    private fun probeWithTimeout(bytes: ByteArray, timeoutMs: Long = 30_000): List<String> {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            executor.submit<List<String>> { probeBytes(bytes) }
                .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            listOf("PROBE_TIMEOUT after ${timeoutMs}ms")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeBytes(bytes: ByteArray): List<String> {
        // 纯 JVM 直驱：直接用具体提取器（DefaultExtractorsFactory 无参工厂需 Uri，1.4.0 在纯 JVM 不可用）
        val extractors: List<Extractor> = listOf(
            androidx.media3.extractor.mp3.Mp3Extractor(),
            androidx.media3.extractor.mp4.Mp4Extractor()
        )
        var lastError: Exception? = null
        for (extractor in extractors) {
            val out = ProbeOutput()
            val input = ByteArrayExtractorInput(bytes)
            try {
                extractor.init(out)
                val holder = PositionHolder()
                var result = extractor.read(input, holder)
                var guard = 0
                while (result != Extractor.RESULT_END_OF_INPUT && guard++ < 5000) {
                    if (result == Extractor.RESULT_SEEK) {
                        input.seekTo(holder.position)
                    }
                    result = extractor.read(input, holder)
                }
                if (out.hit) return out.dump()
            } catch (e: Exception) {
                lastError = e // 嗅探不匹配的提取器会抛，逐个换
            }
        }
        return listOf("NO_EXTRACTOR_HIT; last=${lastError?.message}")
    }

    private class ProbeOutput : ExtractorOutput {
        val entries = mutableListOf<String>()
        var hit = false

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) {
                val md = format.metadata ?: return
                hit = true
                for (i in 0 until md.length()) entries.add(describe(md.get(i)))
            }

            override fun sampleData(
                reader: androidx.media3.common.DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleFlags: Int
            ): Int {
                // 丢弃样本数据（音频/元数据 sample），必须真实消费以推进提取器
                val buf = ByteArray(minOf(length, 8192))
                var remaining = length
                while (remaining > 0) {
                    val n = reader.read(buf, 0, minOf(buf.size, remaining))
                    if (n <= 0) break
                    remaining -= n
                }
                return length - remaining
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleFlags: Int) {
                data.skipBytes(length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?
            ) {}
        }

        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
        fun dump(): List<String> = entries.toList()
    }


    /** 内存 ExtractorInput：支持 seek（m4a moov 在尾部时提取器必须回跳，验证"可随机访问"约束）。 */
    private class ByteArrayExtractorInput(private val data: ByteArray) : ExtractorInput {
        var position: Int = 0
            private set
        private var peekPosition: Int = 0

        fun seekTo(p: Long) {
            position = p.toInt().coerceIn(0, data.size)
            peekPosition = position
        }

        override fun getPosition(): Long = position.toLong()
        override fun getLength(): Long = data.size.toLong()
        override fun getPeekPosition(): Long = peekPosition.toLong()

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - position)
            System.arraycopy(data, position, target, offset, n)
            position += n
            peekPosition = position
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            if (position + length > data.size) throw EOFException()
            System.arraycopy(data, position, target, offset, length)
            position += length
            peekPosition = position
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (position + length <= data.size) {
                readFully(target, offset, length)
                return true
            }
            if (allowEndOfInput && position <= data.size) {
                val n = data.size - position
                System.arraycopy(data, position, target, offset, n)
                position = data.size
                peekPosition = position
                return false
            }
            throw EOFException()
        }

        override fun skip(length: Int): Int {
            val n = minOf(length, data.size - position)
            position += n
            peekPosition = position
            return n
        }

        override fun skipFully(length: Int) {
            if (position + length > data.size) throw EOFException()
            position += length
            peekPosition = position
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (position + length <= data.size) {
                skipFully(length)
                return true
            }
            if (allowEndOfInput && position <= data.size) {
                position = data.size
                peekPosition = position
                return false
            }
            throw EOFException()
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peekPosition >= data.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - peekPosition)
            System.arraycopy(data, peekPosition, target, offset, n)
            peekPosition += n
            return n
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            if (peekPosition + length > data.size) throw EOFException()
            System.arraycopy(data, peekPosition, target, offset, length)
            peekPosition += length
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPosition + length <= data.size) {
                peekFully(target, offset, length)
                return true
            }
            if (allowEndOfInput && peekPosition <= data.size) {
                val n = data.size - peekPosition
                System.arraycopy(data, peekPosition, target, offset, n)
                peekPosition = data.size
                return false
            }
            throw EOFException()
        }

        override fun advancePeekPosition(length: Int) {
            if (peekPosition + length > data.size) throw EOFException()
            peekPosition += length
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPosition + length <= data.size) {
                advancePeekPosition(length)
                return true
            }
            if (allowEndOfInput && peekPosition <= data.size) {
                peekPosition = data.size
                return false
            }
            throw EOFException()
        }

        override fun resetPeekPosition() {
            peekPosition = position
        }

        override fun <E : Throwable> setRetryPosition(position: Long, error: E) {
            this.position = position.toInt().coerceIn(0, data.size)
            peekPosition = this.position
        }
    }
}

private fun describe(e: Metadata.Entry): String = when (e) {
    is TextInformationFrame -> "ID3[${e.id}] desc=${e.description} values=${e.values}"
    is CommentFrame -> "ID3[COMM] desc=${e.description} text=${e.text}"
    is ApicFrame -> "ID3[APIC] mime=${e.mimeType} desc=${e.description} bytes=${e.pictureData?.size ?: 0}"
    is ChapterFrame -> "ID3[CHAP] id=${e.id} start=${e.startTimeMs} end=${e.endTimeMs}"
    is Id3Frame -> "ID3[${e.id}]"
    is MdtaMetadataEntry -> "MP4[${e.key}] type=${e.typeIndicator} value=${String(e.value, Charsets.UTF_8)} bytes=${e.value.size}"
    else -> "${e.javaClass.simpleName}: $e"
}
