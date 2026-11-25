package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import cn.alittlecookie.lutista.tether.protocol.PtpTransactionIdGenerator
import cn.alittlecookie.lutista.tether.transport.RxBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [PtpContainerCodec] 的编解码往返、粘包、半包、txid 回绕。
 *
 * 纯 JVM，不依赖 Android。
 */
class PtpContainerCodecTest {

    // ────────────────────────── 四种容器往返 ──────────────────────────

    @Test
    fun `命令容器往返 - 带 3 个参数`() {
        val encoded = PtpContainerCodec.encodeCommand(
            opcode = PtpConstants.PanasonicOp.SET_PROP_VALUE,
            transactionId = 42L,
            params = longArrayOf(0x02000031L, 0L, 0xFFFFFFFFL),
        )
        assertEquals(PtpContainerCodec.HEADER_SIZE + 12, encoded.size)

        val decoded = PtpContainerCodec.decodeNext(encoded)
        assertTrue(decoded is PtpContainerCodec.Decoded.Container)
        val container = (decoded as PtpContainerCodec.Decoded.Container).container
        assertEquals(PtpConstants.ContainerType.COMMAND, container.type)
        assertEquals(PtpConstants.PanasonicOp.SET_PROP_VALUE, container.code)
        assertEquals(42L, container.transactionId)
        assertArrayEquals(longArrayOf(0x02000031L, 0L, 0xFFFFFFFFL), container.payloadAsParams())
        assertEquals(encoded.size, decoded.consumed)
    }

    @Test
    fun `命令容器往返 - 无参数`() {
        val encoded = PtpContainerCodec.encodeCommand(PtpConstants.Op.GET_DEVICE_INFO, 1L)
        assertEquals(PtpContainerCodec.HEADER_SIZE, encoded.size)
        val container = (PtpContainerCodec.decodeNext(encoded) as PtpContainerCodec.Decoded.Container).container
        assertEquals(0, container.payload.size)
        assertEquals(0, container.payloadAsParams().size)
    }

    @Test
    fun `数据容器往返 - 空数据段`() {
        val encoded = PtpContainerCodec.encodeData(
            opcode = PtpConstants.Op.GET_OBJECT,
            transactionId = 7L,
            data = ByteArray(0),
        )
        assertEquals(PtpContainerCodec.HEADER_SIZE, encoded.size)
        val container = (PtpContainerCodec.decodeNext(encoded) as PtpContainerCodec.Decoded.Container).container
        assertEquals(PtpConstants.ContainerType.DATA, container.type)
        assertEquals(PtpConstants.Op.GET_OBJECT, container.code)
        assertEquals(0, container.payload.size)
    }

    @Test
    fun `数据容器往返 - offset 与 length`() {
        val source = ByteArray(32) { it.toByte() }
        val encoded = PtpContainerCodec.encodeData(
            opcode = PtpConstants.Op.GET_OBJECT,
            transactionId = 9L,
            data = source,
            offset = 8,
            length = 16,
        )
        val container = (PtpContainerCodec.decodeNext(encoded) as PtpContainerCodec.Decoded.Container).container
        assertArrayEquals(source.copyOfRange(8, 24), container.payload)
    }

    @Test
    fun `响应容器往返`() {
        val encoded = PtpContainerCodec.encodeResponse(
            responseCode = PtpConstants.Rc.INVALID_PARAMETER,
            transactionId = 100L,
            params = longArrayOf(0xDEADBEEFL),
        )
        val container = (PtpContainerCodec.decodeNext(encoded) as PtpContainerCodec.Decoded.Container).container
        assertEquals(PtpConstants.ContainerType.RESPONSE, container.type)
        assertEquals(PtpConstants.Rc.INVALID_PARAMETER, container.code)
        assertEquals(100L, container.transactionId)
        assertArrayEquals(longArrayOf(0xDEADBEEFL), container.payloadAsParams())
    }

    @Test
    fun `事件容器往返 - txid 恒为 0xFFFFFFFF`() {
        val encoded = PtpContainerCodec.encodeEvent(
            eventCode = PtpConstants.Event.OBJECT_ADDED,
            params = longArrayOf(0x12199F99L),
        )
        val container = (PtpContainerCodec.decodeNext(encoded) as PtpContainerCodec.Decoded.Container).container
        assertEquals(PtpConstants.ContainerType.EVENT, container.type)
        assertEquals(PtpConstants.Event.OBJECT_ADDED, container.code)
        assertEquals(0xFFFFFFFFL, container.transactionId)
        assertArrayEquals(longArrayOf(0x12199F99L), container.payloadAsParams())
    }

    // ────────────────────────── 大 payload 分片拼接 ──────────────────────────

    @Test
    fun `数据段超过 64 KB - 按 64 KB 分片喂入后 payload 逐字节一致`() {
        val payload = ByteArray(100 * 1024) { (it % 251).toByte() }
        val encoded = PtpContainerCodec.encodeData(
            opcode = PtpConstants.Op.GET_OBJECT,
            transactionId = 1234L,
            data = payload,
        )
        assertEquals(PtpContainerCodec.HEADER_SIZE + payload.size, encoded.size)

        // 复刻 PtpUsbTransport 的流式搬运：64 KB 一片喂 rx，按容器头声明长度边读边落盘。
        val rx = RxBuffer(128 * 1024)
        val sink = ByteArrayOutputStream()
        var fed = 0
        val chunk = 64 * 1024

        fun feed(): Boolean {
            if (fed >= encoded.size) return false
            val n = minOf(chunk, encoded.size - fed)
            rx.append(encoded, fed, n)
            fed += n
            return true
        }

        while (sink.size() < payload.size) {
            if (rx.size < PtpContainerCodec.HEADER_SIZE && !feed()) fail("数据提前耗尽")
            val header = PtpContainerCodec.peekHeader(rx.buffer, rx.readOffset, rx.size)!!
            assertEquals(PtpConstants.Op.GET_OBJECT, header.code)
            assertEquals(payload.size, header.payloadLength)
            rx.skip(PtpContainerCodec.HEADER_SIZE)
            var remaining = header.payloadLength
            while (remaining > 0) {
                if (rx.isEmpty) {
                    if (!feed()) fail("payload 提前耗尽，remaining=$remaining")
                }
                val take = minOf(rx.size, remaining)
                val slice = rx.read(take)
                sink.write(slice, 0, slice.size)
                remaining -= take
            }
        }
        assertArrayEquals(payload, sink.toByteArray())
        assertEquals(encoded.size, fed)
    }

    @Test
    fun `decodeNext 对大容器返回 Incomplete 而不是崩`() {
        val encoded = PtpContainerCodec.encodeData(
            opcode = PtpConstants.Op.GET_OBJECT,
            transactionId = 5L,
            data = ByteArray(100 * 1024),
        )
        // 只给 64 KB，payload 远未到齐
        val partial = encoded.copyOfRange(0, 64 * 1024)
        assertEquals(PtpContainerCodec.Decoded.Incomplete, PtpContainerCodec.decodeNext(partial))
    }

    // ────────────────────────── 粘包 / 半包 ──────────────────────────

    @Test
    fun `粘包 - 两个容器连在一起能依次解出`() {
        val first = PtpContainerCodec.encodeCommand(PtpConstants.Op.OPEN_SESSION, 1L, longArrayOf(1L))
        val second = PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L)
        val merged = first + second

        val result = PtpContainerCodec.decodeAll(merged)
        assertEquals(2, result.containers.size)
        assertEquals(merged.size, result.consumed)
        assertEquals(PtpConstants.Op.OPEN_SESSION, result.containers[0].code)
        assertEquals(PtpConstants.Rc.OK, result.containers[1].code)
    }

    @Test
    fun `粘包 - 完整容器后面跟半个容器时只消费完整的那一个`() {
        val first = PtpContainerCodec.encodeCommand(PtpConstants.Op.GET_DEVICE_INFO, 11L)
        val second = PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 11L, longArrayOf(1L, 2L))
        val merged = first + second.copyOfRange(0, 6)

        val result = PtpContainerCodec.decodeAll(merged)
        assertEquals(1, result.containers.size)
        assertEquals(first.size, result.consumed)
    }

    @Test
    fun `半包 - 头部不足 12 字节返回 Incomplete 且 consumed 为 0`() {
        val encoded = PtpContainerCodec.encodeCommand(PtpConstants.Op.GET_STORAGE_IDS, 3L, longArrayOf(1L))
        val partial = encoded.copyOfRange(0, 8)
        assertEquals(PtpContainerCodec.Decoded.Incomplete, PtpContainerCodec.decodeNext(partial))
        assertNull(PtpContainerCodec.peekHeader(partial))

        val result = PtpContainerCodec.decodeAll(partial)
        assertTrue(result.containers.isEmpty())
        assertEquals(0, result.consumed)
    }

    @Test
    fun `半包 - payload 差一个字节仍是 Incomplete`() {
        val encoded = PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 8L, longArrayOf(7L))
        val partial = encoded.copyOfRange(0, encoded.size - 1)
        assertEquals(PtpContainerCodec.Decoded.Incomplete, PtpContainerCodec.decodeNext(partial))
    }

    @Test
    fun `非法容器 - 长度小于 12 字节判 Corrupt`() {
        val bogus = ByteArray(12)
        PtpContainerCodec.writeU32(bogus, 0, 4L)
        PtpContainerCodec.writeU16(bogus, 4, PtpConstants.ContainerType.RESPONSE)
        val decoded = PtpContainerCodec.decodeNext(bogus)
        assertTrue(decoded is PtpContainerCodec.Decoded.Corrupt)
    }

    @Test
    fun `decodeAll 遇到非法容器抛 PtpParseException`() {
        val bogus = ByteArray(16)
        PtpContainerCodec.writeU32(bogus, 0, 2L)
        try {
            PtpContainerCodec.decodeAll(bogus)
            fail("应当抛 PtpParseException")
        } catch (expected: PtpParseException) {
            assertTrue(expected.message!!.contains("容器结构非法"))
        }
    }

    @Test
    fun `容器长度字段用 u32 - 0xFFFFFFFF 判 Corrupt 而不是负下标`() {
        val bogus = ByteArray(12)
        PtpContainerCodec.writeU32(bogus, 0, 0xFFFFFFFFL)
        assertTrue(PtpContainerCodec.decodeNext(bogus) is PtpContainerCodec.Decoded.Corrupt)
    }

    // ────────────────────────── txid ──────────────────────────

    @Test
    fun `txid 从 1 开始自增`() {
        val ids = PtpTransactionIdGenerator()
        assertEquals(1L, ids.next())
        assertEquals(2L, ids.next())
        assertEquals(3L, ids.next())
        assertEquals(4L, ids.peek())
    }

    @Test
    fun `txid 回绕 - 0xFFFFFFFE 之后回到 1 且跳过 0xFFFFFFFF`() {
        val ids = PtpTransactionIdGenerator(0xFFFFFFFEL)
        assertEquals(0xFFFFFFFEL, ids.next())
        assertEquals(1L, ids.next())
        assertEquals(2L, ids.next())
    }

    @Test
    fun `txid 起始值非法时被纠正`() {
        assertEquals(1L, PtpTransactionIdGenerator(0L).next())
        assertEquals(1L, PtpTransactionIdGenerator(0xFFFFFFFFL).next())
        assertEquals(1L, PtpTransactionIdGenerator(0x1_0000_0001L).next())
    }

    @Test
    fun `txid reset 之后重新从 1 开始`() {
        val ids = PtpTransactionIdGenerator(50L)
        assertEquals(50L, ids.next())
        ids.reset()
        assertEquals(1L, ids.next())
    }
}
