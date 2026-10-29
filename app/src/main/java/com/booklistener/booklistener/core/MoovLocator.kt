package com.booklistener.core

/**
 * moov box 区间定位器（spec: m4b-moov-range-read，v2.2.5）。
 *
 * 扫描侧只需 moov——标签（ilst）、章节（iTunes 章节表 / chpl）、时长（mvhd）全部位于 moov box，
 * mdat 音频数据对解析无意义。头部形态（ftyp→moov→mdat，faststart）读文件头即可；
 * 尾部形态（ftyp→mdat→moov，非 faststart）读文件尾定位。定位失败返回 null，由调用方回退整读。
 *
 * 遍历复用 KNOWN_BOX_TYPES 白名单：损坏/越界/未知类型逐字节重对齐（与 Mp4IlstReader/Mp4ChapterReader
 * 的 v2.2.4 容错逻辑一致），可穿透文件头非 box 前导。
 */
object MoovLocator {

    private const val MOOV = 0x6D6F6F76 // 'moov'

    /**
     * 头部定位：在 [headBytes] 内白名单 box 遍历，返回 moov box 的 [start, endExclusive)。
     * 注意 end 允许超出 headBytes.size——moov 头可见但 box 跨出头段（如 moov 从 ~900KB 开始、
     * size 超 1MB）时按 size 给出理论 end，调用方按文件实际长度截断后读取。
     * 头段内无 moov → null（调用方转尾部定位）。
     */
    fun headMoovRange(headBytes: ByteArray): IntRange? {
        val buf = Mp4BoxReader(headBytes)
        var pos = 0
        while (pos + 8 <= buf.size) {
            val size = boxSize(buf, pos, buf.size + 1)
            val type = buf.u32(pos + 4)
            if (size < 8 || pos.toLong() + size > (buf.size + 1).toLong() || type !in KNOWN_BOX_TYPES) {
                pos += 1
                continue
            }
            if (type == MOOV) return pos..(pos + size).toInt()
            pos += size.toInt()
        }
        return null
    }

    /**
     * 尾部定位：在 [tailBytes] 内白名单 box 遍历找 moov（尾段开头多为 mdat 中部，
     * mdat size 越界 → 逐字节重对齐后命中 moov）。返回相对尾段起始的 [start, endExclusive)。
     */
    fun tailMoovRange(tailBytes: ByteArray): IntRange? {
        val buf = Mp4BoxReader(tailBytes)
        var pos = 0
        while (pos + 8 <= buf.size) {
            val size = boxSize(buf, pos, buf.size + 1)
            val type = buf.u32(pos + 4)
            if (size < 8 || pos.toLong() + size > (buf.size + 1).toLong() || type !in KNOWN_BOX_TYPES) {
                pos += 1
                continue
            }
            if (type == MOOV) return pos..(pos + size).toInt()
            pos += size.toInt()
        }
        return null
    }

    /**
     * v2.2.7：流式 box 链遍历定位 moov（文件级，替代头 1MB/尾 16MB 二分猜测）。
     *
     * 从文件头开始逐 box 读取 8 字节头（size+type），白名单类型按 size 跳过（含巨大的 mdat——
     * mdat 是白名单，size 合法时直接跳到其末尾继续），moov 在文件任意位置（头/中/尾）都能命中；
     * 损坏 box 改为块内搜索下一个已知 box 魔数（v2.2.14 替代 v2.2.7 的逐字节 pos+=1）。
     * 全程只读 box 头（每 box 8~16 字节）+ 损坏段按 chunk 读一次，不读音频数据，大文件无 OOM。
     * 返回 moov box 的 [start, endExclusive)；未命中或超预算返回 null（调用方回退整读/记 error）。
     */
    fun streamMoovRange(channel: java.nio.channels.FileChannel, fileLength: Long): LongRange? {
        var pos = 0L
        val hdr = java.nio.ByteBuffer.allocate(16)
        var heads = 0
        while (pos + 8 <= fileLength) {
            // v2.2.14：扫描预算——防止损坏文件重对齐扫大文件导致 CPU 高/卡死。超预算放弃，
            // 调用方回退整读（≤64MB）或记 error。
            if (++heads > MAX_BOX_HEADS) return null
            channel.position(pos)
            hdr.clear()
            hdr.limit(8)
            if (channel.read(hdr) < 8) return null
            hdr.flip()
            val size = hdr.int.toLong() and 0xFFFFFFFFL
            val type = hdr.int
            val boxLen: Long = when {
                size == 1L -> { // 64 位 largesize
                    if (pos + 16 > fileLength) return null
                    hdr.clear()
                    hdr.limit(8)
                    if (channel.read(hdr) < 8) return null
                    hdr.flip()
                    hdr.long
                }
                size == 0L -> fileLength - pos // size=0：延伸到文件尾
                else -> size
            }
            // v2.2.15：按 size 跳过不查 type 白名单——MP4 是严格 box 树，size 合法（8≤boxLen
            // 且 pos+boxLen≤文件尾）则任何 type（含 uuid/pssh/sinf 等自定义/加密 box）都是
            // 合法 box，应直接按 size 跳；只有 size 越界（真损坏）才重对齐。
            // 旧实现 `&& type in KNOWN_BOX_TYPES` 会把合法的未知 box 误判为损坏 → 重对齐 →
            // 大文件逐字节扫卡死（西乙队 m4a 根因）。
            if (boxLen >= 8 && pos + boxLen <= fileLength) {
                if (type == MOOV) return pos..(pos + boxLen)
                pos += boxLen
            } else {
                // 真损坏（size 越界/<8）：块内搜索 moov 魔数，替代逐字节 IO 卡死
                val next = searchMoov(channel, fileLength, pos)
                if (next == null) return null
                pos = next
            }
        }
        return null
    }

    /** v2.2.14：块内扫描 [from, fileLength)，返回下一个 'moov' 魔数的文件偏移；找不到返回 null。
     *  每次读 64KB chunk 在内存遍历 4 字节魔数，避免逐字节 IO；chunk 间重叠 3 字节防跨块漏检。 */
    private fun searchMoov(
        channel: java.nio.channels.FileChannel,
        fileLength: Long,
        from: Long
    ): Long? {
        val CHUNK = 64 * 1024
        var off = from
        val buf = java.nio.ByteBuffer.allocate(CHUNK + 4)
        while (off + 4 <= fileLength) {
            val len = minOf(CHUNK.toLong(), fileLength - off).toInt()
            channel.position(off)
            buf.clear()
            buf.limit(len)
            if (channel.read(buf) < len) return null
            val arr = buf.array()
            for (i in 0 until len - 3) {
                if (arr[i] == 'm'.code.toByte() &&
                    arr[i + 1] == 'o'.code.toByte() &&
                    arr[i + 2] == 'o'.code.toByte() &&
                    arr[i + 3] == 'v'.code.toByte()
                ) return off + i
            }
            off += len - 3
        }
        return null
    }

    /** v2.2.14：流式定位 box 头读取预算（超预算放弃，防损坏文件重对齐卡死） */
    private const val MAX_BOX_HEADS = 200_000
}
