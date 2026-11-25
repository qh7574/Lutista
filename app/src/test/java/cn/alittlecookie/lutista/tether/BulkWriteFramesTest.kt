package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.BulkWriteFrames
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BulkWriteFrames]：事务**发送阶段**的帧构造与写序列。
 *
 * 为什么需要它：`bulkTransfer` 在 JVM 单测里跑不起来（`UsbDeviceConnection` 是 android.jar 的 stub），
 * 但「写什么、按什么顺序写、要不要补 ZLP、短写怎么办」这四件事直接决定通道会不会错位，
 * 必须可断言。`PtpUsbTransport.transaction()` 生产路径就调这里。
 *
 * 覆盖 QA 复核报告里的阻塞项：传输层原先只有 IN 方向，`0x9403` / `0x9415` / `0x1016` 发不出去。
 */
class BulkWriteFramesTest {

    private val setProp = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L))
    private val payload = byteArrayOf(0x31, 0x00, 0x00, 0x02, 0x04, 0x00, 0x00, 0x00, 0xA0.toByte(), 0x0F, 0x00, 0x00)

    /** 记录每次写入 `(offset, length)` 与「实际接受」字节数，可选地限制单次写入上限以模拟短写。 */
    private class RecordingWriter(private val maxPerCall: Int = Int.MAX_VALUE) {
        val calls = ArrayList<Pair<Int, Int>>()
        val accepted = ArrayList<Int>()
        val buffers = ArrayList<ByteArray>()

        fun write(buffer: ByteArray, offset: Int, length: Int): Int {
            val take = if (length == 0) 0 else minOf(length, maxPerCall)
            calls += offset to length
            accepted += take
            buffers += buffer.copyOfRange(offset, offset + take)
            return take
        }

        val lengths: List<Int> get() = calls.map { it.second }
    }

    // ────────────────────────── 帧构造 ──────────────────────────

    @Test
    fun `带 dataOut - 两帧且顺序为 命令 然后 数据`() {
        val frames = BulkWriteFrames.build(setProp, transactionId = 42L, dataOut = payload)
        assertEquals(2, frames.size)

        val command = requireContainer(frames[0])
        val data = requireContainer(frames[1])
        assertEquals(PtpConstants.ContainerType.COMMAND, command.type)
        assertEquals(PtpConstants.PanasonicOp.SET_PROP_VALUE, command.code)
        assertEquals(PtpConstants.ContainerType.DATA, data.type)
        // 数据容器回显 opcode
        assertEquals(PtpConstants.PanasonicOp.SET_PROP_VALUE, data.code)
        assertEquals(payload.size, data.payload.size)
        assertTrue(payload.contentEquals(data.payload))
    }

    @Test
    fun `同一个事务里 命令与数据的 txid 必须相等`() {
        val frames = BulkWriteFrames.build(setProp, transactionId = 0x1234_5678L, dataOut = payload)
        val command = requireContainer(frames[0])
        val data = requireContainer(frames[1])
        assertEquals(0x1234_5678L, command.transactionId)
        assertEquals(command.transactionId, data.transactionId)
    }

    @Test
    fun `dataOut 为空数组 - 不发数据容器（空 DATA 阶段视为不存在）`() {
        assertEquals(1, BulkWriteFrames.build(setProp, 7L, ByteArray(0)).size)
    }

    @Test
    fun `dataOut 为 null - 只有命令容器`() {
        val frames = BulkWriteFrames.build(setProp, 7L, null)
        assertEquals(1, frames.size)
        assertEquals(PtpConstants.ContainerType.COMMAND, requireContainer(frames[0]).type)
    }

    @Test
    fun `命令参数为空数组时 仍能正常构造`() {
        val frames = BulkWriteFrames.build(PtpOp(PtpConstants.Op.CLOSE_SESSION), 9L, null)
        assertEquals(1, frames.size)
        assertEquals(PtpContainerCodec.HEADER_SIZE, frames[0].size)
        assertEquals(0, requireContainer(frames[0]).payload.size)
    }

    // ────────────────────────── 写序列 ──────────────────────────

    @Test
    fun `写序列 - 命令在前 数据在后 且长度正确`() {
        val frames = BulkWriteFrames.build(setProp, 1L, payload)
        val writer = RecordingWriter()
        val total = BulkWriteFrames.writeAll(frames, maxPacketSize = 512, write = writer::write)

        assertEquals(2, writer.calls.size)
        assertEquals(frames[0].size, writer.lengths[0])
        assertEquals(frames[1].size, writer.lengths[1])
        assertEquals(frames[0].size + frames[1].size, total)
        // 第一帧必须是命令容器（前 12 字节里 type == 1）
        assertEquals(PtpConstants.ContainerType.COMMAND, PtpContainerCodec.readU16(writer.buffers[0], 4))
        assertEquals(PtpConstants.ContainerType.DATA, PtpContainerCodec.readU16(writer.buffers[1], 4))
    }

    @Test
    fun `ZLP - 帧长度恰为 maxPacketSize 整数倍时补 0 长写`() {
        // 命令容器 12 B，参数 125 个 → 12 + 500 = 512 B，正好一个包
        val op = PtpOp(PtpConstants.Op.GET_DEVICE_INFO, LongArray(125) { it.toLong() })
        val frames = BulkWriteFrames.build(op, 3L, null)
        assertEquals(512, frames[0].size)

        val writer = RecordingWriter()
        BulkWriteFrames.writeAll(frames, maxPacketSize = 512, write = writer::write)
        assertEquals(listOf(512, 0), writer.lengths)
    }

    @Test
    fun `ZLP - 不是整数倍时不补 0 长写`() {
        val frames = BulkWriteFrames.build(setProp, 3L, payload) // 16 B + 24 B，都不是 512 的倍数
        val writer = RecordingWriter()
        BulkWriteFrames.writeAll(frames, maxPacketSize = 512, write = writer::write)
        assertTrue("不应出现 0 长写：${writer.lengths}", writer.lengths.none { it == 0 })
    }

    @Test
    fun `短写 - 必须循环写完 不允许只写一半就去读响应`() {
        val frames = BulkWriteFrames.build(setProp, 5L, payload)
        val writer = RecordingWriter(maxPerCall = 5) // 每次最多写 5 字节
        val total = BulkWriteFrames.writeAll(frames, maxPacketSize = 512, write = writer::write)

        val expected = frames.sumOf { it.size }
        assertEquals(expected, total)
        assertEquals("实际写入字节数必须等于帧总长", expected, writer.accepted.sum())
        assertTrue("必须发生多次写（模拟短写）", writer.calls.size > frames.size)
    }

    @Test
    fun `写入返回 0 - 抛 PtpTimeoutException 而不是死循环`() {
        val frames = BulkWriteFrames.build(setProp, 5L, null)
        try {
            BulkWriteFrames.writeAll(frames, 512) { _, _, _ -> 0 }
            fail("返回 0 必须抛 PtpTimeoutException")
        } catch (expected: PtpTimeoutException) {
            assertTrue(expected.message!!.contains("bulk OUT"))
        }
    }

    @Test
    fun `写入返回 -1 - 抛 PtpTimeoutException`() {
        val frames = BulkWriteFrames.build(setProp, 5L, null)
        try {
            BulkWriteFrames.writeAll(frames, 512) { _, _, _ -> -1 }
            fail("返回 -1 必须抛 PtpTimeoutException")
        } catch (expected: PtpTimeoutException) {
            assertTrue(expected.message!!.contains("bulk OUT"))
        }
    }

    @Test
    fun `maxPacketSize 非法 - 抛 IllegalArgumentException`() {
        try {
            BulkWriteFrames.writeAll(emptyList(), maxPacketSize = 0) { _, _, _ -> 0 }
            fail("maxPacketSize=0 必须抛")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("maxPacketSize"))
        }
    }

    // ────────────────────────── 辅助 ──────────────────────────

    private fun requireContainer(frame: ByteArray): cn.alittlecookie.lutista.tether.protocol.PtpContainer {
        val decoded = PtpContainerCodec.decodeNext(frame)
        if (decoded !is PtpContainerCodec.Decoded.Container) {
            fail("帧不是完整容器：$decoded")
            error("unreachable")
        }
        return decoded.container
    }
}
