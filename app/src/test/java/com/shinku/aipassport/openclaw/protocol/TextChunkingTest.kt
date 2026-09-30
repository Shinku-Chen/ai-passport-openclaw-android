package com.shinku.aipassport.openclaw.protocol

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上行 TEXT 帧的 UTF-8 安全分片。
 * 语音原文 + 追加的中文提示词一起上屏时会变长,分片不能切坏汉字/emoji,也不能丢字节。
 */
class TextChunkingTest {

    private val bodyMax = VbFrame.TEXT_PAYLOAD_MAX - 1

    /** 按分片顺序拼接字节后再整体解码(与固件「合并成一条再显示」一致)。 */
    private fun decode(chunks: List<ByteArray>): String {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    @Test
    fun empty_body_yields_no_chunks() {
        assertTrue(splitTextPayload(ByteArray(0), bodyMax).isEmpty())
    }

    @Test
    fun short_body_is_a_single_chunk() {
        val body = "hello".toByteArray(Charsets.UTF_8)
        val chunks = splitTextPayload(body, bodyMax)
        assertEquals(1, chunks.size)
        assertEquals(body.toList(), chunks[0].toList())
    }

    @Test
    fun long_multibyte_body_is_not_split_inside_a_character() {
        // 语音原文 + 默认附加提示词一起超过一片(2047B)
        val text = "你好世界".repeat(700) + "\n请用不超过 200 字回复，并且不要使用 emoji 表情。"
        val body = text.toByteArray(Charsets.UTF_8)
        val chunks = splitTextPayload(body, bodyMax)
        assertTrue("长文本必须分成多片", chunks.size > 1)
        chunks.forEach {
            assertTrue("每片不超过上限", it.size <= bodyMax)
            // 单字节解码切片不应出现 U+FFFD 替换字符(出现即说明切坏了多字节)
            assertFalse("分片切坏了多字节字符", String(it, Charsets.UTF_8).contains('\uFFFD'))
        }
        assertEquals(text, decode(chunks))
    }

    @Test
    fun four_byte_emoji_is_kept_whole() {
        val text = "a".repeat(2045) + "😀😀"   // 2045 + 4 + 4 = 2053B,首片须在 emoji 前回退
        val chunks = splitTextPayload(text.toByteArray(Charsets.UTF_8), bodyMax)
        assertEquals(2, chunks.size)
        assertFalse(String(chunks[0], Charsets.UTF_8).contains('\uFFFD'))
        assertFalse(String(chunks[1], Charsets.UTF_8).contains('\uFFFD'))
        assertEquals(text, decode(chunks))
    }

    @Test
    fun every_chunk_respects_the_max_size_and_reassembles() {
        val body = "字".repeat(3000).toByteArray(Charsets.UTF_8)
        val chunks = splitTextPayload(body, bodyMax)
        assertTrue(chunks.all { it.size <= bodyMax })
        // 不丢字节:分片字节数之和 == 原字节数
        assertEquals(body.size, chunks.sumOf { it.size })
        assertEquals(body.toList(), decode(chunks).toByteArray(Charsets.UTF_8).toList())
    }
}
