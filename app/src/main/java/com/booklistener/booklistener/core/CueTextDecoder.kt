package com.booklistener.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * v2.1.4：.cue 文本解码——cue 规范无强制编码，中文 cue 常见 Windows ANSI（GBK/GB18030）。
 * 固定 UTF-8 读取会把中文解析成乱码（并连带破坏 CUE 结构与整轨定位）。
 * 策略：BOM 优先 → 严格 UTF-8（REPORT 校验，成功即用）→ GB18030（GBK/GB2312 超集）→ 原样兜底。
 */
object CueTextDecoder {

    /** 解码 .cue 文本（bytes → String，自动编码探测） */
    fun decode(bytes: ByteArray): String {
        // UTF-8 BOM：剥离后按 UTF-8
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
        // 严格 UTF-8 校验（任何非法序列即拒绝，避免把 GBK 中文当 UTF-8 乱码）
        runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()?.let { return it }
        // GB18030（覆盖 GBK/GB2312/GB18030 全系，中文 Windows 常见）
        runCatching {
            Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()?.let { return it }
        // 兜底：原样 UTF-8（replace 模式）
        return bytes.toString(Charsets.UTF_8)
    }
}
