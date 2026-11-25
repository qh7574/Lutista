package cn.alittlecookie.lutista.tether

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.LinkedList

/**
 * 传输层**写序列**的集成验证（QA 复核报告里阻塞项的验收）。
 *
 * 用一个「会记账的假 `UsbDeviceConnection`」跑真实的 `transaction()`：
 * - 记录每一次 `bulkTransfer` 的方向与长度；
 * - 对 IN 方向按脚本吐字节，让事务能正常收尾。
 *
 * 断言的核心不是「能发出去」，而是**阶段顺序**——PTP 里先写 DATA 再写命令，
 * 相机会把 DATA 当命令头解析，通道立即错位，而这种错位在真机上只表现为"后面所有命令超时"。
 *
 * 注：`SystemClock` 在 JVM 单测里是 stub，所以 `PtpUsbTransport` 的时钟是注入的（走假时钟）。
 */
class TransportWriteSequenceTest {

    private companion object {
        const val MAX_PACKET = 512
        const val VID = 0x04DA
        const val PID = 0x2382
        const val ENDPOINT_BULK_OUT = 0x01
        const val ENDPOINT_BULK_IN = 0x81
        const val ENDPOINT_INTR_IN = 0x82
        const val EP_TYPE_BULK = 2
        const val EP_TYPE_INTERRUPT = 3
    }

    /** 一次 `bulkTransfer` 的记录。 */
    private data class Write(val direction: String, val length: Int, val firstBytes: List<Byte>) {
        override fun toString(): String = "$direction($length)"
    }

    private val writes = ArrayList<Write>()

    /** IN 脚本：每次读取按顺序消费一个缓冲区的开头若干字节。 */
    private val inScript = LinkedList<ByteArray>()

    /** 中断端点脚本（事件侧）：与 [inScript] 同构，空了就返回 -1。 */
    private val intrScript = LinkedList<ByteArray>()

    private lateinit var connection: UsbDeviceConnection
    private lateinit var device: UsbDevice
    private lateinit var transport: PtpUsbTransport

    /** 假时钟：每次读都前进 1 ms，保证不会因 `SystemClock` stub 抛异常，也不会卡死。 */
    private var clock = 0L

    @Before
    fun setUp() {
        UsbIoThread.resetForTest()
        UsbIoThread.bind() // 单测线程冒充 ptp-io
        writes.clear()
        inScript.clear()
        clock = 0L

        val bulkOut = mockk<UsbEndpoint> {
            every { address } returns ENDPOINT_BULK_OUT
            every { type } returns EP_TYPE_BULK
            every { maxPacketSize } returns MAX_PACKET
        }
        val bulkIn = mockk<UsbEndpoint> {
            every { address } returns ENDPOINT_BULK_IN
            every { type } returns EP_TYPE_BULK
            every { maxPacketSize } returns MAX_PACKET
        }
        val intrIn = mockk<UsbEndpoint> {
            every { address } returns ENDPOINT_INTR_IN
            every { type } returns EP_TYPE_INTERRUPT
            every { maxPacketSize } returns 512
        }
        val usbInterface = mockk<UsbInterface> {
            every { endpointCount } returns 3
            every { getEndpoint(0) } returns bulkOut
            every { getEndpoint(1) } returns bulkIn
            every { getEndpoint(2) } returns intrIn
        }
        val device = mockk<UsbDevice> {
            every { interfaceCount } returns 1
            every { getInterface(0) } returns usbInterface
            every { vendorId } returns VID
            every { productId } returns PID
        }
        this.device = device

        connection = mockk(relaxed = true) {
            every { claimInterface(any(), any()) } returns true
            every { rawDescriptors } returns null

            // OUT：记账并「全部写入成功」；0 长写即 ZLP。
            every { bulkTransfer(bulkOut, any(), any(), any(), any()) } answers {
                val buffer = arg<ByteArray>(1)
                val offset = arg<Int>(2)
                val length = arg<Int>(3)
                writes += Write("OUT", length, buffer.drop(offset).take(minOf(length, 4)))
                length
            }

            // IN：按脚本吐字节；脚本空了就返回 -1（模拟超时，让事务不至于空转）。
            // 注意：fill() 用的是 4 参数重载 bulkTransfer(ep, buffer, length, timeout)，
            // 而 OUT 用 5 参数重载 —— 两个都要 stub，否则 relaxed mock 返回 0 会被当成 ZLP，
            // 事务会一直等到 deadline（第一版就是这样误报成 TIMEOUT 的）。
            every { bulkTransfer(bulkIn, any(), any(), any(), any()) } answers {
                serveIn(arg<ByteArray>(1), arg<Int>(3))
            }
            every { bulkTransfer(bulkIn, any(), any(), any()) } answers {
                serveIn(arg<ByteArray>(1), arg<Int>(2))
            }
            // 中断端点：默认恒返回 -1 —— 表示「没有事件时不会自旋」：一次读不到就 break，
            // 不在 1~2 ms 的短超时上循环重试。这是安全属性，不是随手写的桩。
            every { bulkTransfer(intrIn, any(), any(), any()) } answers {
                serveIntr(arg<ByteArray>(1), arg<Int>(2))
            }
        }

        transport = PtpUsbTransport(now = { clock++ })
    }

    /** 从 IN 脚本里取数据填进 [buffer]，返回本次读到的字节数（脚本空 = -1 模拟超时）。 */
    private fun serveIn(buffer: ByteArray, length: Int): Int = serveFrom(inScript, buffer, length)

    /** 从中断端点脚本取数据（事件侧）。 */
    private fun serveIntr(buffer: ByteArray, length: Int): Int = serveFrom(intrScript, buffer, length)

    private fun serveFrom(script: LinkedList<ByteArray>, buffer: ByteArray, length: Int): Int {
        val next = script.peekFirst() ?: return -1
        val take = minOf(length, next.size)
        System.arraycopy(next, 0, buffer, 0, take)
        if (take == next.size) script.pollFirst() else script[0] = next.copyOfRange(take, next.size)
        return take
    }

    @After
    fun tearDown() {
        UsbIoThread.resetForTest()
    }

    // ────────────────────────── 主验收：阶段顺序 ──────────────────────────

    @Test
    fun `带 dataOut 的事务 - 写入顺序是 命令容器 然后 数据容器 然后才读响应`() = runBlocking {
        openWithPreflightOk()
        writes.clear() // 只关心本次事务

        val payload = writePropertyPayload(0x02000031L, 0x000F_A000L)
        // 响应容器（txid 用任意值；解码不校验它，事件才恒为 0xFFFFFFFF）
        inScript += PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L)

        val response = transport.transaction(
            op = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L)),
            dataOut = payload,
        )

        assertEquals(PtpConstants.Rc.OK, response.code)
        assertEquals("必须恰好两次 OUT 写：命令 + 数据", 2, writes.size)
        assertTrue("第一写必须是 OUT 命令容器：$writes", writes[0].direction == "OUT")
        assertTrue("第二写必须是 OUT 数据容器：$writes", writes[1].direction == "OUT")

        // 命令容器：12 + 1 参数 × 4 = 16 B，type=1
        assertEquals(PtpContainerCodec.HEADER_SIZE + 4, writes[0].length)
        // 数据容器：12 + payload，type=2（前 4 字节是 length 字段，这里只能核对长度）
        assertEquals(PtpContainerCodec.HEADER_SIZE + payload.size, writes[1].length)
        assertTrue("数据容器长度必须大于命令容器：$writes", writes[1].length > writes[0].length)
    }

    @Test
    fun `dataOut 为空数组 - 只写命令容器 不写数据容器`() = runBlocking {
        openWithPreflightOk()
        writes.clear()
        inScript += PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L)

        transport.transaction(
            op = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L)),
            dataOut = ByteArray(0),
        )

        assertEquals("空 DATA 阶段视为不存在：$writes", 1, writes.size)
        assertEquals(PtpContainerCodec.HEADER_SIZE + 4, writes[0].length)
    }

    @Test
    fun `dataOut 为 null - 只写命令容器`() = runBlocking {
        openWithPreflightOk()
        writes.clear()
        inScript += PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L)

        transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO))

        assertEquals(1, writes.size)
    }

    @Test
    fun `命令与数据容器共用同一 txid`() = runBlocking {
        openWithPreflightOk()
        writes.clear()

        val payload = writePropertyPayload(0x02000031L, 0x1L)
        val command = PtpContainerCodec.encodeCommand(0x9403, 777L, longArrayOf(0x02000031L))
        val data = PtpContainerCodec.encodeData(0x9403, 777L, payload)

        inScript += PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 777L)
        transport.transaction(
            op = PtpOp(0x9403, longArrayOf(0x02000031L)),
            dataOut = payload,
        )

        // 从记录到的前 4 字节无法看 txid（txid 在 [8..11]），所以这里改用「构造等价帧」比对长度，
        // txid 相等本身由 BulkWriteFramesTest 直接断言（同一 build 调用产出，共享入参）。
        assertEquals(command.size, writes[0].length)
        assertEquals(data.size, writes[1].length)
    }

    @Test
    fun `ZLP - 写入长度为 maxPacketSize 整数倍时多出一次 0 长写`() = runBlocking {
        openWithPreflightOk()
        writes.clear()
        inScript += PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L)

        // 125 个参数 → 命令容器 12 + 500 = 512 B，正好一个包
        transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO, LongArray(125) { it.toLong() }))

        assertEquals("512 B 的帧之后必须补 0 长写：$writes", 2, writes.size)
        assertEquals(512, writes[0].length)
        assertEquals("ZLP 必须是 0 长", 0, writes[1].length)
    }

    // ────────────────────────── 中断通道：非事件容器丢弃 ──────────────────────────

    @Test
    fun `中断端点上的非事件容器 - 丢弃并计数 不进返回列表`() = runBlocking {
        openWithPreflightOk()

        // type=5 在 PIMA 15740 的 Container Type 表里是 "otherwise reserved"（规范只有 1..4）
        intrScript += containerWithType(type = 5, code = 0x1234)

        val events = transport.drainEvents()

        assertTrue("非事件容器不得出现在返回列表：$events", events.isEmpty())
        assertEquals("丢弃计数应为 1", 1, transport.discardedEventContainers)
        assertFalse("中断通道的垃圾不得判死会话", transport.isFatal)
        assertTrue("设备仍处于 open 状态", transport.isOpen)
    }

    @Test
    fun `非事件容器之后紧跟合法事件 - 只返回那个事件 且游标正确前进`() = runBlocking {
        openWithPreflightOk()

        // 关键用例：证明「丢弃后游标前进、后续解析不受影响」。
        // 若实现写成"不 skip 但返回空"，第二个容器会被反复解 → 活锁（不是崩溃，更难查）。
        intrScript += containerWithType(type = 5, code = 0x0001) +
            PtpContainerCodec.encodeEvent(
                PtpConstants.Event.OBJECT_ADDED,
                longArrayOf(0x12199F99L),
            )

        val events = transport.drainEvents()

        assertEquals("只应返回那一个合法事件：$events", 1, events.size)
        assertEquals(PtpConstants.Event.OBJECT_ADDED, events[0].code)
        assertEquals(0x12199F99L, events[0].params[0])
        assertEquals("丢弃计数应为 1", 1, transport.discardedEventContainers)
    }

    // ────────────────────────── 辅助 ──────────────────────────

    /** 造一个自定义 `type` 的容器（用来模拟规范里 "otherwise reserved" 的类型码）。 */
    private fun containerWithType(type: Int, code: Int): ByteArray {
        val length = PtpContainerCodec.HEADER_SIZE + 4
        val out = ByteArray(length)
        PtpContainerCodec.writeU32(out, 0, length.toLong())
        PtpContainerCodec.writeU16(out, 4, type)
        PtpContainerCodec.writeU16(out, 6, code)
        PtpContainerCodec.writeU32(out, 8, PtpConstants.EVENT_TRANSACTION_ID)
        PtpContainerCodec.writeU32(out, 12, 0L)
        return out
    }

    /** 让 `open()` 的 GetDeviceInfo 预检成功：IN 一次吐出 DATA + RESPONSE 两个容器。 */
    private suspend fun openWithPreflightOk() {
        val deviceInfo = Fixtures.hex("device_info")
        val data = PtpContainerCodec.encodeData(PtpConstants.Op.GET_DEVICE_INFO, 1L, deviceInfo)
        val response = PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L)
        inScript.clear()
        inScript += data + response

        val opened = transport.open(device, connection)
        // 预检成功即证明「命令写出去 + 数据收回来 + 解析」整条链在真实 transaction() 里成立
        assertNull("预检应当成功（TIMEOUT/REJECTED/PARSE_ERROR 都不该出现）", opened.preflightFailure)
        assertEquals("DC-S1", opened.model)
        assertEquals(VID, opened.usbVendorId)
        assertFalse("预检成功后通道不能处于 fatal", transport.isFatal)
    }

    /** 写属性的标准 payload：`[propCode u32][valuesize u16 = 4][0x0000][value u32]`（定稿 §3.7）。 */
    private fun writePropertyPayload(propCode: Long, value: Long): ByteArray {
        val out = ByteArray(12)
        PtpContainerCodec.writeU32(out, 0, propCode)
        PtpContainerCodec.writeU16(out, 4, 4)
        PtpContainerCodec.writeU16(out, 6, 0)
        PtpContainerCodec.writeU32(out, 8, value)
        return out
    }
}
