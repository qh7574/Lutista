package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec.Decoded
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * QA 独立复核（T01）：[PtpContainerCodec] 的**异常路径**与边界。
 *
 * 与工程师的 `PtpContainerCodecTest` 刻意不重叠：那份测「正常往返 + 粘包/半包」，
 * 这份只测「坏输入」——目标是证明解析器**不会**：
 * - 泄漏 `IndexOutOfBoundsException` / `NegativeArraySizeException`；
 * - 因超大 `length` 触发超大内存分配；
 * - 死循环；
 * - 静默截断（payload 比声明短却当成功）。
 *
 * 判定口径来自 `.docs/联机模式-PTP结论-定稿-2026-11-25.md` §1.4（容器头 12 B、全小端）
 * 与 `PtpTransport` 的 KDoc（结构非法必须抛 `PtpProtocolException` → 通道判死）。
 */
class QaContainerFuzzTest {

    /** 造一个只有头的容器。 */
    private fun header(length: Long, type: Int = 2, code: Int = 0x1009, txid: Long = 7L): ByteArray {
        val out = ByteArray(PtpContainerCodec.HEADER_SIZE)
        PtpContainerCodec.writeU32(out, 0, length)
        PtpContainerCodec.writeU16(out, 4, type)
        PtpContainerCodec.writeU16(out, 6, code)
        PtpContainerCodec.writeU32(out, 8, txid)
        return out
    }

    // ────────────────────────── length 的两个关键边界 ──────────────────────────

    @Test
    fun `length 恰为 12 - 空 payload 容器必须能解出且 consumed 为 12`() {
        val decoded = PtpContainerCodec.decodeNext(header(12))
        assertTrue("空 payload 容器应当可解", decoded is Decoded.Container)
        val container = (decoded as Decoded.Container).container
        assertEquals(0, container.payload.size)
        assertEquals(0, container.header.payloadLength)
        assertEquals(12, decoded.consumed)
        assertEquals(0x1009, container.code)
        assertEquals(7L, container.transactionId)
    }

    @Test
    fun `length 小于 12 - 0 到 11 全部判 Corrupt 不允许静默通过`() {
        for (length in 0L..11L) {
            val decoded = PtpContainerCodec.decodeNext(header(length))
            assertTrue("length=$length 应当判 Corrupt，实际 $decoded", decoded is Decoded.Corrupt)
        }
    }

    @Test
    fun `length 用 u32 - 0x80000000 与 0xFFFFFFFF 判 Corrupt 而不是负下标`() {
        listOf(0x8000_0000L, 0xFFFF_FFFFL).forEach { raw ->
            val decoded = PtpContainerCodec.decodeNext(header(raw))
            assertTrue("length=$raw 应当判 Corrupt，实际 $decoded", decoded is Decoded.Corrupt)
        }
    }

    @Test
    fun `length 超出可用字节 - 返回 Incomplete 且不分配内存`() {
        // 头部声明 200 B，实际只给了 112 B
        val buffer = header(200) + ByteArray(100)
        assertEquals(Decoded.Incomplete, PtpContainerCodec.decodeNext(buffer))
        assertEquals(Decoded.Incomplete, PtpContainerCodec.decodeNext(buffer, available = 12))

        // 0x7FFFFFFF 若被当真去 copyOfRange 会 OOM；必须只是 Incomplete
        val huge = header(0x7FFF_FFFFL)
        assertEquals(Decoded.Incomplete, PtpContainerCodec.decodeNext(huge))
        assertEquals(Decoded.Incomplete, PtpContainerCodec.decodeNext(huge, available = 12))
    }

    // ────────────────────────── 空 buffer / offset 越界 ──────────────────────────

    @Test
    fun `空 buffer 与不足 12 字节 - 一律 Incomplete 且 peekHeader 为 null`() {
        assertEquals(Decoded.Incomplete, PtpContainerCodec.decodeNext(ByteArray(0)))
        assertNull(PtpContainerCodec.peekHeader(ByteArray(0)))
        assertNull(PtpContainerCodec.peekHeader(ByteArray(11)))
        assertEquals(Decoded.Incomplete, PtpContainerCodec.decodeNext(ByteArray(11)))
    }

    @Test
    fun `offset 越界 - 不抛下标异常 只返回 Incomplete 或空结果`() {
        val buffer = header(12)
        assertEquals(
            Decoded.Incomplete,
            PtpContainerCodec.decodeNext(buffer, offset = buffer.size, available = 0),
        )
        assertNull(PtpContainerCodec.peekHeader(buffer, offset = buffer.size, available = 0))
        assertEquals(Decoded.Incomplete, PtpContainerCodec.decodeNext(buffer, offset = -1))
        assertNull(PtpContainerCodec.peekHeader(buffer, offset = -1))

        val result = PtpContainerCodec.decodeAll(buffer, offset = buffer.size, available = 0)
        assertTrue(result.containers.isEmpty())
        assertEquals(0, result.consumed)
    }

    @Test
    fun `offset 后仍有完整容器 - decodeAll 的 consumed 不包含前缀`() {
        val prefix = ByteArray(5)
        val container = header(16, code = 0x1001) + ByteArray(4)
        val buffer = prefix + container

        val result = PtpContainerCodec.decodeAll(
            buffer,
            offset = prefix.size,
            available = container.size,
        )
        assertEquals(1, result.containers.size)
        assertEquals(container.size, result.consumed)
        assertEquals(0x1001, result.containers[0].code)
    }

    // ────────────────────────── 静默截断 ──────────────────────────

    @Test
    fun `不静默截断 - payload 长度恒等于 length 减 12 且内容逐字节一致`() {
        for (payloadLength in listOf(0, 1, 3, 4, 11, 12, 13, 100)) {
            val payload = ByteArray(payloadLength) { (it and 0xFF).toByte() }
            val buffer = header(12L + payloadLength) + payload
            val decoded = PtpContainerCodec.decodeNext(buffer)
            assertTrue("payloadLength=$payloadLength 应当可解", decoded is Decoded.Container)
            val container = (decoded as Decoded.Container).container
            assertEquals(payloadLength, container.payload.size)
            assertArrayEquals(payload, container.payload)
            assertEquals(12 + payloadLength, decoded.consumed)
        }
    }

    // ────────────────────────── 随机 fuzz ──────────────────────────

    @Test
    fun `随机字节 fuzz - 只允许 PtpParseException 不允许下标越界或死循环`() {
        val random = Random(seed = 20261125)

        repeat(2000) { round ->
            val size = random.nextInt(0, 65)
            val buffer = ByteArray(size).also { random.nextBytes(it) }

            val startedAt = System.nanoTime()
            try {
                val result = PtpContainerCodec.decodeAll(buffer)
                assertTrue(
                    "round=$round consumed=${result.consumed} size=$size",
                    result.consumed in 0..size,
                )
                assertEquals(
                    "consumed 必须等于各容器长度之和",
                    result.consumed,
                    result.containers.sumOf { it.header.length },
                )

                // 带 offset 的变体同样不得越界
                val half = size / 2
                val withOffset = PtpContainerCodec.decodeAll(buffer, offset = half, available = size - half)
                assertTrue(withOffset.consumed in 0..(size - half))
            } catch (expected: PtpParseException) {
                // 允许：结构非法时上抛（调用方据此判通道不可信）
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("round=$round 单次解码耗时 ${elapsedMs}ms，疑似死循环", elapsedMs < 1_000)
        }
    }

    @Test
    fun `随机长度字段 - decodeNext 永不抛非 PtpParseException`() {
        val random = Random(seed = 4242)
        repeat(500) {
            val buffer = ByteArray(64).also { random.nextBytes(it) }
            try {
                PtpContainerCodec.decodeNext(buffer)
                PtpContainerCodec.decodeNext(buffer, offset = 8, available = 56)
            } catch (expected: PtpParseException) {
                fail("decodeNext 契约是返回 Decoded，不应抛 PtpParseException：${expected.message}")
            }
        }
    }
}
