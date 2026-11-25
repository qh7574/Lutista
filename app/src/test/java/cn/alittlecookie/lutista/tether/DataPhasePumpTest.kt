package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.DataPhasePump
import cn.alittlecookie.lutista.tether.transport.RxBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [DataPhasePump]：DATA 容器 payload 的流式搬运。
 *
 * 核心回归用例来自一次自查发现的真 bug：`rx` 里可能同时有「上一个容器的残留」+「新容器」，
 * 分块若只按 `min(rx 可用, 剩余长度)` 算，会一次性拷出超过中转缓冲的字节 → 数组越界。
 * 因此分块必须是 `min(rx 可用, 剩余长度, 中转缓冲大小)` 三重约束。
 */
class DataPhasePumpTest {

    // ────────────────────────── 分块大小 ──────────────────────────

    @Test
    fun `chunkSize - 三重约束取最小`() {
        assertEquals(100, DataPhasePump.chunkSize(available = 100, remaining = 500, stagingSize = 4096))
        assertEquals(500, DataPhasePump.chunkSize(available = 900, remaining = 500, stagingSize = 4096))
        assertEquals(4096, DataPhasePump.chunkSize(available = 65535, remaining = 65535, stagingSize = 4096))
    }

    @Test
    fun `chunkSize - 参数非法直接抛`() {
        try {
            DataPhasePump.chunkSize(available = -1, remaining = 10, stagingSize = 16)
            org.junit.Assert.fail("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("chunkSize 参数非法"))
        }
    }

    // ────────────────────────── 回归：残留 + 新容器 ──────────────────────────

    @Test
    fun `rx 可用量大于中转缓冲 - 总搬运量等于 payloadLength 且单块不越界`() {
        // 复刻真机场景：一次 URB 灌进 5 KB（== 上一个容器的残留 + 本容器前半截），
        // 而中转缓冲只有 512 B。若分块只取 min(rx, remaining)，这里就会越界。
        val staging = ByteArray(512)
        val rx = RxBuffer(8 * 1024)
        val payload = ByteArray(5 * 1024) { (it % 251).toByte() }
        rx.append(payload)
        assertTrue("本用例要求 rx 可用量 > 中转缓冲", rx.size > staging.size)

        val received = ByteArrayOutputStream()
        var chunkCalls = 0
        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = payload.size,
            staging = staging,
            fill = { error("用例里 payload 已全部在 rx 中，不应再读 USB") },
            sink = { chunk, len ->
                chunkCalls++
                assertTrue("单块不得超过中转缓冲：len=$len", len <= staging.size)
                received.write(chunk, 0, len)
            },
        )

        assertEquals(payload.size, moved)
        assertEquals(payload.size, received.size())
        assertEquals(10, chunkCalls) // 5120 / 512
        assertArrayEquals(payload, received.toByteArray())
        assertTrue("payload 搬完后 rx 必须为空", rx.isEmpty)
    }

    @Test
    fun `fill 在 rx 空时被调用 - 模拟跨 URB 的大 payload`() {
        val staging = ByteArray(1024)
        val rx = RxBuffer(4 * 1024)
        val total = 10 * 1024
        val payload = ByteArray(total) { (it % 199).toByte() }

        // 分成 3 段模拟 3 个 URB
        val segments = listOf(
            payload.copyOfRange(0, 4096),
            payload.copyOfRange(4096, 8192),
            payload.copyOfRange(8192, total),
        )
        var next = 0
        var fillCalls = 0
        val received = ByteArrayOutputStream()

        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = total,
            staging = staging,
            fill = {
                fillCalls++
                // 第一次 fill：先给一个完整容器头 + 第一段？不 —— transport 已经把 12 B 头吃掉，
                // 这里只灌 payload 字节，正好对应 pump 的契约。
                val seg = segments[next++]
                rx.append(seg)
            },
            sink = { chunk, len -> received.write(chunk, 0, len) },
        )

        assertEquals(total, moved)
        assertEquals(3, fillCalls)
        assertArrayEquals(payload, received.toByteArray())
    }

    @Test
    fun `sink 为 null - 仍然整包搬完（约束 3 的落点）`() {
        val rx = RxBuffer(8 * 1024)
        val payload = ByteArray(3 * 1024)
        rx.append(payload)
        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = payload.size,
            staging = ByteArray(1024),
            fill = { error("不应读 USB") },
            sink = null,
        )
        assertEquals(payload.size, moved)
        assertTrue(rx.isEmpty)
    }

    @Test
    fun `payloadLength 为 0 - 不调用 fill 也不调用 sink`() {
        val rx = RxBuffer(64)
        var fillCalls = 0
        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = 0,
            staging = ByteArray(64),
            fill = { fillCalls++ },
            sink = { _, _ -> error("空数据段不应回调 sink") },
        )
        assertEquals(0, moved)
        assertEquals(0, fillCalls)
    }

    @Test
    fun `真实容器头之后再 pump - 与 PtpUsbTransport 的调用顺序一致`() {
        val payload = ByteArray(2048) { it.toByte() }
        val encoded = PtpContainerCodec.encodeData(
            opcode = PtpConstants.Op.GET_OBJECT,
            transactionId = 77L,
            data = payload,
        )
        val rx = RxBuffer(8 * 1024)
        rx.append(encoded)

        val header = PtpContainerCodec.peekHeader(rx.buffer, rx.readOffset, rx.size)!!
        assertEquals(payload.size, header.payloadLength)
        rx.skip(PtpContainerCodec.HEADER_SIZE)

        val received = ByteArrayOutputStream()
        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = header.payloadLength,
            staging = ByteArray(700), // 故意不给整数倍，逼出非对齐分块
            fill = { error("不应读 USB") },
            sink = { chunk, len -> received.write(chunk, 0, len) },
        )

        assertEquals(payload.size, moved)
        assertArrayEquals(payload, received.toByteArray())
    }
}
