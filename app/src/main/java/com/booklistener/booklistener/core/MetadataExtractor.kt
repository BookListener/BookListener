package com.booklistener.core

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.ChapterTocFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.io.EOFException

/**
 * Media3 1.4.0 提取器直驱元数据读取器（spec: tag-fields-full）。
 *
 * 不拉起播放器：纯 JVM 直驱具体提取器（Mp3/Flac/Ogg），收集
 * TrackOutput.format() 携带的元数据条目，输出统一 [TagEntry] 键值流。
 *
 * - ID3 文本帧：标准帧按帧 id 作 key；TXXX 用 description 作 key（规范 \0 帧）；
 *   ffmpeg 冒号风格（desc 空白/整体进 value 且形如 `KEY:VALUE`）按首个冒号拆键值。
 * - 图片帧（APIC）：只记存在性 + mime，不提取字节。
 * - 章节（CHAP/CTOC）：计数为 `CHAP`/`CTOC` 条目（副产品，供有声书）。
 * - 时长：Format.durationUs（µs→ms；未知返回 null）。
 *
 * 解析失败/格式不支持 → 空条目 + durationMs=null（调用方按文件名显示，不降级到其它解析器）。
 */
class MetadataExtractor {

    /** @param bytes 完整文件字节（本地 SAF 扫描读全；mp3/flac/ogg 元数据在头部，顺序读即可） */
    fun extract(bytes: ByteArray, extension: String): Extracted {
        val extractor = when (extension.lowercase()) {
            "mp3" -> androidx.media3.extractor.mp3.Mp3Extractor()
            "flac" -> androidx.media3.extractor.flac.FlacExtractor()
            "ogg" -> androidx.media3.extractor.ogg.OggExtractor()
            // m4a/mp4/alac：Media3 仅输出 iTunes 标准原子映射帧 + 时长；
            // 扩展原子（©ser/©sno/stik）由 Mp4IlstReader 补齐（调用侧合并）
            "m4a", "mp4", "alac" -> androidx.media3.extractor.mp4.Mp4Extractor()
            else -> return Extracted(emptyList(), null)
        }
        return runCatching {
            val out = CollectingOutput()
            val input = ByteArrayExtractorInput(bytes)
            extractor.init(out)
            val holder = PositionHolder()
            var result = extractor.read(input, holder)
            var guard = 0
            while (result != Extractor.RESULT_END_OF_INPUT && guard++ < 5000) {
                if (result == Extractor.RESULT_SEEK) input.seekTo(holder.position)
                result = extractor.read(input, holder)
            }
            Extracted(out.entries.toList(), out.durationUs?.takeIf { it > 0 }?.div(1000L))
        }.getOrElse { Extracted(emptyList(), null) }
    }

    /** 解析结果：键值条目 + 时长毫秒（未知=null） */
    data class Extracted(
        val entries: List<TagEntry>,
        val durationMs: Long?
    )

    private class CollectingOutput : ExtractorOutput {
        val entries = mutableListOf<TagEntry>()
        var durationUs: Long? = null
        private val seen = HashSet<String>()

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) {
                val md = format.metadata ?: return
                for (i in 0 until md.length()) addEntry(md.get(i))
            }

            override fun sampleData(
                reader: androidx.media3.common.DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleFlags: Int
            ): Int {
                // 必须真实消费样本数据以推进提取器（返回 0 会死循环）
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

        /** 时长来自提取器 SeekMap（µs；未知 = C.TIME_UNSET） */
        override fun seekMap(seekMap: SeekMap) {
            val d = seekMap.getDurationUs()
            if (d != androidx.media3.common.C.TIME_UNSET) durationUs = d
        }

        /**
         * 元数据条目 → TagEntry。去重：结构化语义键取首次（ID3v1+v2、重复帧）；
         * TXXX 不同 desc 视为不同键（全部保留）。
         */
        private fun addEntry(e: Metadata.Entry) {
            when (e) {
                is TextInformationFrame -> {
                    if (e.id == "TXXX") {
                        // 规范 \0 帧：desc 非空（且非占位）→ 键=desc
                        val desc = e.description?.trim().orEmpty()
                        val value = e.values?.firstOrNull().orEmpty().trim()
                        // ffmpeg 冒号风格：desc 为占位（空 或 "TXXX"）且 value 形如 `KEY:VALUE` → 按首冒号拆键值
                        val placeholder = desc.isEmpty() || desc == "TXXX"
                        val sep = if (placeholder) value.indexOf(':') else -1
                        when {
                            value.isEmpty() -> {}
                            sep > 0 -> add(value.substring(0, sep).trim(), value.substring(sep + 1).trim(), TagFields.SRC_ID3)
                            desc.isNotEmpty() -> add(desc, value, TagFields.SRC_ID3)
                            else -> add("TXXX", value, TagFields.SRC_ID3)
                        }
                    } else {
                        val value = e.values?.firstOrNull().orEmpty().trim()
                        if (value.isNotEmpty()) add(e.id, value, TagFields.SRC_ID3)
                    }
                }
                is CommentFrame -> {
                    val text = e.text?.trim().orEmpty()
                    if (text.isNotEmpty()) add("COMM", text, TagFields.SRC_ID3)
                }
                is ApicFrame -> add("APIC", "image/${e.mimeType?.substringAfter('/') ?: "jpeg"}", TagFields.SRC_ID3)
                is ChapterTocFrame -> add("CTOC", "present", TagFields.SRC_ID3)
                is ChapterFrame -> add("CHAP", "present", TagFields.SRC_ID3)
                is VorbisComment -> {
                    if (e.key.isNotBlank() && e.value.isNotBlank()) add(e.key, e.value, TagFields.SRC_ID3)
                }
                is PictureFrame -> add("PICTURE", "image/${e.mimeType?.substringAfter('/') ?: "jpeg"}", TagFields.SRC_ID3)
                is Id3Frame -> add(e.id, "present", TagFields.SRC_ID3)
                else -> {} // 其余条目（时间戳等）不产出
            }
        }

        private fun add(key: String, value: String, source: String) {
            if (seen.add("$key\u0000$value")) entries += TagEntry(key, value, source)
        }
    }

    private companion object {
        const val C_VALUE_UNSET = Long.MIN_VALUE
    }
}

/** 内存 ExtractorInput：支持 seek（随机访问约束，mp3/flac/ogg 元数据在头部，正常顺序读）。 */
internal class ByteArrayExtractorInput(private val data: ByteArray) : ExtractorInput {
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
