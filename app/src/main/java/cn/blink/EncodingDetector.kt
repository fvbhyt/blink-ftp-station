package cn.blink

import java.nio.ByteBuffer
import java.nio.charset.*

object EncodingDetector {

    data class Result(val charsetName: String, val charset: Charset)

    /**
     * 简化版检测：
     * 1. 纯 ASCII → UTF-8
     * 2. 严格 UTF-8 解码成功（无 U+FFFD）→ UTF-8
     * 3. 严格 GBK 解码成功 → GBK
     * 4. 兜底 UTF-8（含替换字符）
     */
    fun detect(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result("UTF-8", StandardCharsets.UTF_8)

        // 快速路径：全 ASCII 直接返回
        // 注意: Kotlin 的 Byte 是有符号的, 0xE4 == -28, "it < 0x80" 恒为 true,
        // 必须用 (it.toInt() and 0x80) 做无符号判断
        if (bytes.all { (it.toInt() and 0x80) == 0 }) {
            return Result("ASCII", StandardCharsets.US_ASCII)
        }

        // 严格 UTF-8
        try {
            val dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = dec.decode(ByteBuffer.wrap(bytes)).toString()
            if (!text.contains('\uFFFD')) return Result("UTF-8", StandardCharsets.UTF_8)
        } catch (_: CharacterCodingException) { }

        // 严格 GBK
        try {
            val gbk = Charset.forName("GBK")
            gbk.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            return Result("GBK", gbk)
        } catch (_: Exception) { }

        return Result("UTF-8*", StandardCharsets.UTF_8)
    }
}
