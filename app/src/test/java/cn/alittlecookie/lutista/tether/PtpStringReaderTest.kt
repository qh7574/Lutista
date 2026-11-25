package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import cn.alittlecookie.lutista.tether.protocol.PtpStringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [PtpStringReader]：正常串 / 空串 / 单字符 / 中文（含代理对）/ 越界 / 非规范终止 0。
 */
class PtpStringReaderTest {

    /** 真机 `GetObjectInfo` 里的 DCIM：`05 4400 4300 4900 4d00 0000`（定稿 §4.2 实测 hex）。 */
    private val dcim = byteArrayOf(0x05, 0x44, 0x00, 0x43, 0x00, 0x49, 0x00, 0x4D, 0x00, 0x00, 0x00)

    @Test
    fun `正常串 - DCIM 真机布局`() {
        val result = PtpStringReader.read(dcim)
        assertEquals("DCIM", result.value)
        assertEquals(dcim.size, result.bytesConsumed)
    }

    @Test
    fun `空串 - count 为 0 只消耗 1 字节`() {
        val data = byteArrayOf(0x00, 0x11, 0x22)
        val result = PtpStringReader.read(data)
        assertEquals("", result.value)
        assertEquals(1, result.bytesConsumed)
    }

    @Test
    fun `单字符`() {
        val data = byteArrayOf(0x02, 0x41, 0x00, 0x00, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals("A", result.value)
        assertEquals(5, result.bytesConsumed)
    }

    @Test
    fun `仅有终止 0 的串`() {
        val data = byteArrayOf(0x01, 0x00, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals("", result.value)
        assertEquals(3, result.bytesConsumed)
    }

    @Test
    fun `含中文`() {
        val data = ptpString("中文机身")
        val result = PtpStringReader.read(data)
        assertEquals("中文机身", result.value)
        assertEquals(data.size, result.bytesConsumed)
    }

    @Test
    fun `含代理对 - emoji 落在 UTF-16 代理对边界`() {
        val value = "DC-S1\uD83D\uDCF7X" // 📷 是 U+1F4F7，占两个 UTF-16 单元
        val data = ptpString(value)
        val result = PtpStringReader.read(data)
        assertEquals(value, result.value)
        assertEquals(data.size, result.bytesConsumed)
    }

    @Test
    fun `带 offset 与 limit - 只读指定区间`() {
        val prefix = byteArrayOf(0x7F, 0x7E)
        val data = prefix + ptpString("DPT") + byteArrayOf(0x01, 0x02)
        val result = PtpStringReader.read(data, offset = prefix.size, limit = data.size - 2)
        assertEquals("DPT", result.value)
        assertEquals(9, result.bytesConsumed)
    }

    @Test
    fun `长度越界 - 声称的字符数超出可用字节`() {
        val data = byteArrayOf(0x0A, 0x41, 0x00, 0x42, 0x00) // 声称 10 字符，只给了 2 字符
        try {
            PtpStringReader.read(data)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("越界"))
        }
    }

    @Test
    fun `起点非法 - offset 等于 limit`() {
        try {
            PtpStringReader.read(byteArrayOf(0x01), offset = 1, limit = 1)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("起点非法"))
        }
    }

    @Test
    fun `非规范固件 - count 不含终止 0 时也能正确解析`() {
        // count=2 但内容是两个真实字符，没有终止 0
        val data = byteArrayOf(0x02, 0x41, 0x00, 0x42, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals("AB", result.value)
        assertEquals(5, result.bytesConsumed)
    }

    @Test
    fun `尾部残留 NUL 被裁掉`() {
        val data = byteArrayOf(0x03, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals("A", result.value)
        assertEquals(7, result.bytesConsumed)
    }

    /** 按 PTP 规范（count 含终止 0）构造字符串。 */
    private fun ptpString(value: String): ByteArray {
        if (value.isEmpty()) return byteArrayOf(0x00)
        val body = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
        val count = body.size / 2
        require(count <= 255) { "PTP string 最多 255 个字符单元：$count" }
        return byteArrayOf(count.toByte()) + body
    }
}
