package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.DataPhasePump
import cn.alittlecookie.lutista.tether.transport.RxBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * QA 独立复核（T01）：数据搬运与接收缓冲。
 *
 * 两个必须证明的点：
 * 1. **payload 搬完后，后面的响应容器一个字节都不能少**——这是真机 86 MB 下载路径上
 *    最容易被「多搬一点」毁掉的地方（`rx` 里同时躺着 DATA 容器与紧随其后的 RESPONSE）。
 * 2. [RxBuffer] 的游标语义：残留、compact、扩容、`copyTo` 两侧越界。
 *
 * 分块规则来自定稿 §6 约束 2/3 的实现约定：`min(rx 可用, 剩余长度, 中转缓冲)`。
 */
class QaPumpBufferTest {

    // ─────────────── ① 残留 + 数据容器 + 紧随其后的响应容器 ───────────────

    @Test
    fun `搬运 payload 后 紧随其后的响应容器必须完整保留在 rx 里`() {
        val payload = ByteArray(3000) { (it % 251).toByte() }
        val data = PtpContainerCodec.encodeData(PtpConstants.Op.GET_OBJECT, transactionId = 9L, data = payload)
        val response = PtpContainerCodec.encodeResponse(
            responseCode = PtpConstants.Rc.OK,
            transactionId = 9L,
            params = longArrayOf(0x1219_9F99L),
        )
        val rx = RxBuffer(16 * 1024)
        rx.append(data + response)

        // 复刻 PtpUsbTransport 的姿势：先吃掉 12 B 头，再按声明长度流式搬
        val header = PtpContainerCodec.peekHeader(rx.buffer, rx.readOffset, rx.size)!!
        assertEquals(payload.size, header.payloadLength)
        rx.skip(PtpContainerCodec.HEADER_SIZE)

        val staging = ByteArray(700) // 故意非对齐，逼出多块
        val received = ByteArrayOutputStream()
        var maxChunk = 0
        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = header.payloadLength,
            staging = staging,
            fill = { error("payload 已全在 rx 中，不应再读 USB") },
            sink = { chunk, len ->
                maxChunk = maxOf(maxChunk, len)
                received.write(chunk, 0, len)
            },
        )

        assertEquals(payload.size, moved)
        assertEquals(payload.size, received.size())
        assertArrayEquals(payload, received.toByteArray())
        assertTrue("单块 $maxChunk 超过中转缓冲 ${staging.size}", maxChunk <= staging.size)

        // 关键断言：响应容器必须原封不动
        assertEquals(response.size, rx.size)
        assertArrayEquals(response, rx.peek(response.size))
    }

    @Test
    fun `空 payload 容器 - 不消耗字节 后续容器不受影响`() {
        val data = PtpContainerCodec.encodeData(PtpConstants.Op.GET_OBJECT, 3L, ByteArray(0))
        val response = PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 3L)
        val rx = RxBuffer(1024)
        rx.append(data + response)

        val header = PtpContainerCodec.peekHeader(rx.buffer, rx.readOffset, rx.size)!!
        assertEquals(0, header.payloadLength)
        rx.skip(PtpContainerCodec.HEADER_SIZE)

        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = 0,
            staging = ByteArray(64),
            fill = { error("不应读 USB") },
            sink = { _, _ -> error("空 payload 不应回调 sink") },
        )
        assertEquals(0, moved)
        assertEquals(response.size, rx.size)
        assertArrayEquals(response, rx.peek(response.size))
    }

    @Test
    fun `残留场景 - rx 里已有半截 payload 时 fill 只在真正空的时候被调用`() {
        val total = 4096
        val payload = ByteArray(total) { (it % 197).toByte() }
        val rx = RxBuffer(8 * 1024)
        // 先灌 1000 字节（模拟上一次 URB 的残留）
        rx.append(payload, 0, 1000)
        var fed = 1000
        var fillCalls = 0
        val received = ByteArrayOutputStream()

        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = total,
            staging = ByteArray(512),
            fill = {
                fillCalls++
                val n = minOf(1500, total - fed)
                assertTrue("fill 被调用时 rx 必须为空", rx.isEmpty)
                rx.append(payload, fed, n)
                fed += n
            },
            sink = { chunk, len -> received.write(chunk, 0, len) },
        )

        assertEquals(total, moved)
        assertArrayEquals(payload, received.toByteArray())
        // 1000 + 1500 + 1500 + 96 → 3 次 fill
        assertEquals(3, fillCalls)
        assertTrue(rx.isEmpty)
    }

    @Test
    fun `payloadLength 小于 rx 可用量 - 多余字节留在 rx 且不被改写`() {
        val rx = RxBuffer(1024)
        val body = ByteArray(100) { it.toByte() }
        val tail = ByteArray(37) { (200 + it).toByte() }
        rx.append(body + tail)

        val out = ByteArrayOutputStream()
        val moved = DataPhasePump.pump(
            source = rx,
            payloadLength = body.size,
            staging = ByteArray(64),
            fill = { error("不应读 USB") },
            sink = { chunk, len -> out.write(chunk, 0, len) },
        )
        assertEquals(body.size, moved)
        assertArrayEquals(body, out.toByteArray())
        assertEquals(tail.size, rx.size)
        assertArrayEquals(tail, rx.peek(tail.size))
    }

    // ─────────────── ② RxBuffer 游标与容量 ───────────────

    @Test
    fun `compact 后字节序不变且游标归零`() {
        val rx = RxBuffer(64)
        val source = ByteArray(10) { (10 + it).toByte() }
        rx.append(source)
        rx.skip(4)
        assertEquals(4, rx.readOffset)

        rx.compact()
        assertEquals(0, rx.readOffset)
        assertEquals(6, rx.size)
        assertArrayEquals(source.copyOfRange(4, 10), rx.peek(6))
    }

    @Test
    fun `多次 compact 幂等 - 已归零时再 compact 不改变内容`() {
        val rx = RxBuffer(64)
        val source = ByteArray(9) { it.toByte() }
        rx.append(source)
        rx.compact()
        rx.compact()
        assertEquals(0, rx.readOffset)
        assertArrayEquals(source, rx.peek(source.size))
    }

    @Test
    fun `扩容后既有字节不丢不多且顺序不变`() {
        val rx = RxBuffer(16)
        val first = ByteArray(16) { it.toByte() }
        val second = ByteArray(40) { (100 + it).toByte() }
        rx.append(first)
        assertEquals(16, rx.capacity)
        rx.append(second)

        assertTrue("容量必须够放下 56 字节，实际 ${rx.capacity}", rx.capacity >= 56)
        assertEquals(56, rx.size)
        assertArrayEquals(first + second, rx.read(56))
        assertTrue(rx.isEmpty)
    }

    @Test
    fun `reserveTail 返回真实尾部空间且不破坏已有字节`() {
        val rx = RxBuffer(32)
        rx.append(ByteArray(8) { 1 })
        val tail = rx.reserveTail(16)
        assertTrue("tail=$tail", tail >= 16)
        assertEquals(24, tail)
        assertEquals("\$rx 内容不应被 reserveTail 改动", 8, rx.size)
        assertArrayEquals(ByteArray(8) { 1 }, rx.peek(8))
    }

    @Test
    fun `容量参数为 0 或负数时提升到最小容量而不是崩`() {
        listOf(0, -1).forEach { capacity ->
            val rx = RxBuffer(capacity)
            assertTrue("capacity=$capacity → ${rx.capacity}", rx.capacity >= 16)
            rx.append(byteArrayOf(1, 2, 3))
            assertEquals(3, rx.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), rx.read(3))
        }
    }

    @Test
    fun `skip 0 与 skip 全部 - 游标语义`() {
        val rx = RxBuffer(16)
        rx.append(byteArrayOf(1, 2, 3))
        rx.skip(0)
        assertEquals(3, rx.size)
        assertEquals(0, rx.readOffset)

        rx.skip(3)
        assertEquals(0, rx.size)
        assertEquals("skip 到空后游标必须归零，否则下次 append 会浪费前段空间", 0, rx.readOffset)
        assertTrue(rx.isEmpty)
    }

    @Test
    fun `copyTo 目标越界必须被拦下`() {
        val rx = RxBuffer(32)
        rx.append(ByteArray(8) { 5 })
        try {
            rx.copyTo(ByteArray(4), destOffset = 2, count = 4)
            fail("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("copyTo 目标范围非法"))
        }
    }

    @Test
    fun `copyTo 源越界必须被拦下`() {
        val rx = RxBuffer(32)
        rx.append(ByteArray(8) { 5 })
        try {
            rx.copyTo(ByteArray(8), destOffset = 0, count = 9)
            fail("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("copyTo 源范围非法"))
        }
    }

    @Test
    fun `buffer 加 readOffset 才是有效区间 - 直接索引 buffer 下标 0 是错的`() {
        val rx = RxBuffer(32)
        rx.append(ByteArray(6) { it.toByte() })
        rx.skip(2)
        assertEquals(4, rx.size)
        assertEquals(2, rx.readOffset)
        assertEquals("有效区间起点必须是 readOffset", 2, rx.buffer[rx.readOffset].toInt())
        // 用有效区间读出的内容必须等于 peek
        assertArrayEquals(rx.peek(rx.size), rx.buffer.copyOfRange(rx.readOffset, rx.readOffset + rx.size))
    }

    @Test
    fun `随机块追加后整体读出顺序一致`() {
        val random = Random(seed = 77)
        val rx = RxBuffer(64)
        val expected = ByteArrayOutputStream()
        repeat(200) {
            val size = random.nextInt(1, 40)
            val chunk = ByteArray(size) { random.nextInt(0, 256).toByte() }
            expected.write(chunk)
            rx.append(chunk)
        }
        val all = expected.toByteArray()
        assertEquals(all.size, rx.size)
        assertArrayEquals(all, rx.read(all.size))
        assertTrue(rx.isEmpty)
    }

    // ─────────────── ③ 倍增扩容的上界（作者自述未覆盖） ───────────────

    @Test
    fun `倍增扩容 - 1 MiB 级别的 required 仍能正确扩容且不丢字节`() {
        val rx = RxBuffer(16)
        val head = ByteArray(16) { it.toByte() }
        rx.append(head)

        val required = 1 shl 20
        val tail = rx.reserveTail(required)
        assertTrue("tail=$tail 必须 >= required", tail >= required)
        assertTrue("容量必须跟着长上去，实际 ${rx.capacity}", rx.capacity >= head.size + required)
        assertEquals("扩容不能改动已有字节", 16, rx.size)
        assertArrayEquals(head, rx.peek(16))

        val extra = ByteArray(required) { 7 }
        rx.append(extra)
        assertEquals(16 + required, rx.size)
        assertArrayEquals(head, rx.peek(16))
    }

    @Test
    fun `倍增扩容溢出 - 极大的 required 必须抛异常而不是死循环`() {
        // 这条曾经只能靠静态推导（写用例会挂死 CI）。现在实现里加了
        // `if (newCapacity > Int.MAX_VALUE / 2) throw IllegalStateException(...)`，可以真跑了。
        // 没有这道闸，2^30 再 *2 会溢出成负数 → 再 *2 成 0 → 之后永远 0 < required → 静默死循环烧 CPU。
        val rx = RxBuffer(16)
        val head = ByteArray(16) { it.toByte() }
        rx.append(head)

        val startedAt = System.nanoTime()
        try {
            rx.reserveTail(Int.MAX_VALUE)
            fail("必须抛 IllegalStateException，不能静默死循环")
        } catch (expected: IllegalStateException) {
            assertTrue("异常信息应指明扩容溢出：${expected.message}", expected.message!!.contains("扩容溢出"))
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("耗时 ${elapsedMs}ms，疑似循环次数异常", elapsedMs < 2_000)

        // 抛异常后不得破坏既有内容
        assertEquals(16, rx.size)
        assertArrayEquals(head, rx.peek(16))
    }
}
