package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import cn.alittlecookie.lutista.tether.protocol.PtpStringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * QA 独立复核（T01）：[PtpStringReader] 的边界与容错。
 *
 * 与工程师的 `PtpStringReaderTest` 不重叠：那份测「正常串 / 空串 / 中文 / 非规范终止 0」，
 * 这份专测**长度字节的极限**（255）、**奇数剩余字节**、**孤立代理项**、
 * **内嵌 NUL 的裁剪语义**、**limit 提前截断**。
 *
 * 关键契约（`PtpStringReader` KDoc）：
 * `count` 含终止 0，消耗 `1 + 2 × count` 字节；声明长度超出可用范围必须抛
 * [PtpParseException]，**不允许静默截断**——否则 `DeviceInfoParser` 的自校验会一路错位。
 */
class QaStringReaderEdgeTest {

    @Test
    fun `count 255 恰好够 - 消耗 511 字节且去掉终止 0`() {
        // 255 个单元（510 字节）：254 个 'A'(0x0041) + 1 个终止 0
        val body = ByteArray(255 * 2) { index -> if (index % 2 == 0) 0x41 else 0x00 }
        body[508] = 0
        body[509] = 0
        val data = byteArrayOf(0xFF.toByte()) + body
        assertEquals(511, data.size)

        val result = PtpStringReader.read(data)
        assertEquals(511, result.bytesConsumed)
        assertEquals(254, result.value.length)
        assertTrue(result.value.all { it == 'A' })
    }

    @Test
    fun `count 255 差 1 字节 - 抛 PtpParseException 而不是截断成 254`() {
        val data = byteArrayOf(0xFF.toByte()) + ByteArray(509)
        try {
            PtpStringReader.read(data)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("越界"))
        }
    }

    @Test
    fun `奇数剩余字节 - 声称 2 个单元只给 3 字节必须报错`() {
        // count=2 需要 1 + 4 = 5 字节，这里只有 4 字节
        val data = byteArrayOf(0x02, 0x41, 0x00, 0x42)
        try {
            PtpStringReader.read(data)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("越界"))
        }
    }

    @Test
    fun `孤立高代理项 - 不抛异常 按替换字符解码`() {
        // 0xD83D 是高代理，后面直接跟终止 0（不是合法的代理对）
        val data = byteArrayOf(0x02, 0x3D, 0xD8.toByte(), 0x00, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals(5, result.bytesConsumed)
        assertEquals(1, result.value.length)
        // JDK 的 UTF-16LE 解码器对残缺代理项给 U+FFFD，不会抛异常
        assertEquals("\uFFFD", result.value)
    }

    @Test
    fun `内嵌 NUL - 只裁尾部 中间的 NUL 保留`() {
        // count=3：'A' '\u0000' 'B'（最后一个单元不是 0 → 按 3 个单元解）
        val data = byteArrayOf(0x03, 0x41, 0x00, 0x00, 0x00, 0x42, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals("A\u0000B", result.value)
        assertEquals(7, result.bytesConsumed)
    }

    @Test
    fun `limit 提前截断 - 不得读过 limit 之外`() {
        // 声称 10 个单元，但 limit 只允许 5 字节
        val data = byteArrayOf(0x0A, 0x41, 0x00, 0x42, 0x00, 0x43, 0x00)
        try {
            PtpStringReader.read(data, offset = 0, limit = 5)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("越界"))
        }
    }

    @Test
    fun `起点与边界非法 - offset 负 或 limit 超 size 或 offset 等于 limit`() {
        val data = byteArrayOf(0x01, 0x00, 0x00)
        val cases = listOf(
            Triple(-1, data.size, "offset<0"),
            Triple(0, data.size + 1, "limit>size"),
            Triple(2, 2, "offset==limit"),
            Triple(3, 3, "offset==limit==size"),
        )
        for ((offset, limit, label) in cases) {
            try {
                PtpStringReader.read(data, offset, limit)
                fail("$label 应当抛 PtpParseException")
            } catch (expected: PtpParseException) {
                assertTrue("$label: ${expected.message}", expected.message!!.contains("起点非法"))
            }
        }
    }

    @Test
    fun `count 为 0 之后不再读任何字节 - 即使后面紧跟垃圾`() {
        val data = byteArrayOf(0x00) + ByteArray(16) { 0x7F }
        val result = PtpStringReader.read(data)
        assertEquals("", result.value)
        assertEquals(1, result.bytesConsumed)
    }

    @Test
    fun `真实机身串 - 31 单元的序列号可往返`() {
        // 用脱敏后的合成值（31 个单元）；真机 fixture 的序列号已按 `sanitized` 标记换成 31 个 '0'，
        // 公开仓库任何地方都不应再出现真实序列号。
        val serial = "SN" + "0".repeat(29)
        assertEquals(31, serial.length)
        val body = (serial + "\u0000").toByteArray(Charsets.UTF_16LE)
        val data = byteArrayOf((body.size / 2).toByte()) + body
        val result = PtpStringReader.read(data)
        assertEquals(serial, result.value)
        assertEquals(31, result.value.length)
        assertEquals(data.size, result.bytesConsumed)
    }

    // ────────── 容错取舍：会不会掩盖真正的解析错误 ──────────

    @Test
    fun `容错分支 - 非规范固件（count 不含终止 0）能解出全部字符`() {
        // "AB" 两个单元、没有终止 0：最后一个单元是 'B'(0x42) → 按 count 个单元解
        val data = byteArrayOf(0x02, 0x41, 0x00, 0x42, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals("AB", result.value)
        assertEquals(5, result.bytesConsumed)
    }

    @Test
    fun `容错分支 - 非规范固件且末字符恰为 NUL 时会少一个字符（已知取舍）`() {
        // 极端组合：固件不写终止 0，而最后一个可见字符恰好是 U+0000。
        // 此时「最后一个单元是 0 ⟹ 去掉」的容错会少解一个字符 —— 这是该容错的固有代价，
        // 不影响字节消耗量（bytesConsumed 仍 = 1 + 2×count），所以不会造成结构错位。
        val data = byteArrayOf(0x03, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00)
        val result = PtpStringReader.read(data)
        assertEquals("A", result.value)
        assertEquals(7, result.bytesConsumed)
    }

    @Test
    fun `容错不掩盖结构错位 - 字符串 count 写小 1 时 DeviceInfoParser 必须报未对齐`() {
        // 关键论证：容错只改变「最后一个单元算不算字符」，**不改变消耗字节数**，
        // 所以它不可能把结构错位藏起来 —— DeviceInfoParser 的「消耗必须等于回包长度」自校验兜住了。
        val payload = Fixtures.hex("device_info").copyOf()
        val manufacturerCountOffset = 115 // 2+4+2+1+2 + (4+19*2) + (4+16*2) + (4+1*2) + (4+0) + (4+6*2)
        assertEquals("fixture 变了？manufacturer 的 count 字节应为 10", 10, payload[manufacturerCountOffset].toInt())
        payload[manufacturerCountOffset] = 9 // 声称 9 个单元（正确是 10：9 字符 + 终止 0）

        try {
            DeviceInfoParser.parse(payload)
            fail("count 写小 1 之后必须被自校验拦下，否则容错就掩盖了真正的错位")
        } catch (expected: PtpParseException) {
            assertTrue(
                "应报未对齐/越界，实际：${expected.message}",
                expected.message!!.contains("未对齐") || expected.message!!.contains("越界"),
            )
        }
    }
}
