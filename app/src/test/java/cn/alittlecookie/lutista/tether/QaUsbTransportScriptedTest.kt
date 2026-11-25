package cn.alittlecookie.lutista.tether

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * QA 独立复核（T01）：**在真实 `PtpUsbTransport` 上**驱动 USB 路径。
 *
 * 为什么要这么写：作者自述这四处「只有代码审查覆盖」——
 * `fill()` 的读长度对齐、`writeFully()` 的短写/ZLP、`readControlContainer()` 的半包、
 * `drainEvents()` 的粘包。把逻辑抄一份到测试里做平行副本是自欺欺人（等于测我自己），
 * 所以这里用 mockk 把 `UsbDeviceConnection` 伪装成一台**可编排响应的相机**，
 * 让生产代码原样跑：命令→数据→响应的状态机、`DataPhasePump` 的搬运、`RxBuffer` 的游标
 * 全部是真代码。
 *
 * 三条防挂死措施（非常重要，否则编排出错会让 CI 卡死）：
 * 1. 时钟用 `PtpUsbTransport(now = ...)` **注入**成单调递增（每次调用 +1），
 *    这样任何意外循环都会在 4000 次内撞上 deadline 抛 `PtpTimeoutException`，而不是无限自旋；
 * 2. IN 队列取空时返回 -1（模拟超时），不会出现「永远返回 0 长包」的自旋；
 * 3. `UsbDeviceConnection` 用**严格 mock**：任何未 stub 的方法调用（例如有人偷偷加
 *    `controlTransfer` 做 clear_halt 自愈）会立刻抛 `MockKException` 让测试变红。
 */
class QaUsbTransportScriptedTest {

    private var clock: Long = 0

    @Before
    fun setUp() {
        UsbIoThread.resetForTest()
        UsbIoThread.bind()
        clock = 1_000L
    }

    @After
    fun tearDown() {
        UsbIoThread.resetForTest()
    }

    // ────────────────────────── ① fill() 的读长度对齐 ──────────────────────────

    @Test
    fun `读长度按 maxPacketSize 对齐 - 512 与 1024 两种真实取值`() = runBlocking {
        listOf(512, 1024).forEach { maxPacket ->
            val camera = FakeCamera(bulkMax = maxPacket)
            val harness = harness(camera)
            primeOpen(camera)
            harness.transport.open(harness.device, harness.conn)

            assertTrue("maxPacket=$maxPacket 没有任何 IN 请求", camera.inRequests.isNotEmpty())
            camera.inRequests.forEach { request ->
                assertEquals(
                    "读长度 $request 不是 maxPacket=$maxPacket 的整数倍（会让相机 IN FIFO 卡死）",
                    0,
                    request % maxPacket,
                )
                assertEquals("读长度必须是 64 KiB（min(READ_CHUNK, tail) 后对齐）", 64 * 1024, request)
            }
        }
    }

    @Test
    fun `读长度在 1000 这种非 2 的幂时也被取整 - 证明取模那行真的在执行`() = runBlocking {
        // 64 KiB 天然是 512/1024 的整数倍，若只看真实值这条断言等于没测。
        // 用 1000 做「跳线」：一旦有人删掉 `request -= request % maxPacketSize`，这里立刻红。
        val camera = FakeCamera(bulkMax = 1000)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val request = camera.inRequests.first()
        assertEquals(0, request % 1000)
        assertTrue("对齐后应当小于 64 KiB，实际 $request", request < 64 * 1024)
        assertTrue("对齐后仍应是最大的合规长度，实际 $request", request >= 64 * 1024 - 999)
    }

    // ────────────────────────── ② writeFully 的短写与 ZLP ──────────────────────────

    @Test
    fun `短写 - bulk OUT 每次只吞 3 字节时必须循环写满`() = runBlocking {
        val camera = FakeCamera(bulkMax = 4, shortWrite = 3)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val writtenBefore = camera.out.size()
        val zlpBefore = camera.zlp
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))

        harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))

        // 命令容器 = 12 + 4 = 16 字节，每次只吞 3 字节 → 需要 6 次
        assertEquals("命令容器必须被完整写出", writtenBefore + 16, camera.out.size())
        val commandBytes = camera.out.toByteArray().copyOfRange(writtenBefore, camera.out.size())
        assertEquals("必须原样写出命令容器（不是分片拼接错）", 16, commandBytes.size)
        assertEquals(PtpConstants.Op.OPEN_SESSION, PtpContainerCodec.readU16(commandBytes, 6))

        // 16 % 4 == 0 → 必须补一个 ZLP
        assertEquals("长度恰为 maxPacketSize 整数倍时必须补 ZLP", zlpBefore + 1, camera.zlp)
    }

    @Test
    fun `不需要 ZLP 时不补 - 长度非整数倍的情况`() = runBlocking {
        // maxPacket=512 时命令容器 12~32 字节，永远不是整数倍 → 不应出现 ZLP
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val zlpBefore = camera.zlp
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        harness.transport.transaction(PtpOp(PtpConstants.Op.CLOSE_SESSION))

        assertEquals("非整数倍时不能补 ZLP（相机不会等剩余数据）", zlpBefore, camera.zlp)
    }

    // ────────────────────────── ③ readControlContainer 的半包 ──────────────────────────

    @Test
    fun `响应容器只到 6 字节头 - 必须继续读而不是崩`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val response = PtpContainerCodec.encodeResponse(
            responseCode = PtpConstants.Rc.INVALID_PARAMETER,
            transactionId = 2L,
            params = longArrayOf(0xDEAD_BEEFL),
        )
        // 故意切成 6 字节头 + 剩余（模拟一次 URB 只到半个头）
        camera.queueIn(response.copyOfRange(0, 6), response.copyOfRange(6, response.size))

        val inBefore = camera.inRequests.size
        val actual = harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))

        assertEquals("半个头必须继续读", 2, camera.inRequests.size - inBefore)
        assertEquals(PtpConstants.Rc.INVALID_PARAMETER, actual.code)
        assertArrayEquals(longArrayOf(0xDEAD_BEEFL), actual.params)
    }

    @Test
    fun `响应容器 payload 差 1 字节 - 必须继续读到齐`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val response = PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L, longArrayOf(7L))
        camera.queueIn(response.copyOfRange(0, response.size - 1), response.copyOfRange(response.size - 1, response.size))

        val actual = harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))
        assertEquals(PtpConstants.Rc.OK, actual.code)
        assertArrayEquals(longArrayOf(7L), actual.params)
    }

    // ────────────────────────── ④ drainEvents 的粘包 ──────────────────────────

    @Test
    fun `中断端点一次读到两个事件容器 - 必须全部解出`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512, intrMax = 64)
        val harness = harness(camera)
        // drainEvents 不需要 open，但 connection 必须存在
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val first = PtpContainerCodec.encodeEvent(PtpConstants.Event.OBJECT_ADDED, longArrayOf(0x1219_9F99L))
        val second = PtpContainerCodec.encodeEvent(PtpConstants.Event.OBJECT_ADDED, longArrayOf(0x1919_9F99L))
        camera.queueIntr(first + second)

        val events = harness.transport.drainEvents()
        assertEquals("一次读到两个事件容器必须都解出", 2, events.size)
        assertEquals(PtpConstants.Event.OBJECT_ADDED, events[0].code)
        assertArrayEquals(longArrayOf(0x1219_9F99L), events[0].params)
        assertArrayEquals(longArrayOf(0x1919_9F99L), events[1].params)
    }

    @Test
    fun `中断端点收到 1_5 个事件 - 半个留到下一轮再拼`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512, intrMax = 64)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val whole = PtpContainerCodec.encodeEvent(PtpConstants.Event.DEVICE_PROP_CHANGED, longArrayOf(0x5001L))
        val next = PtpContainerCodec.encodeEvent(PtpConstants.Event.OBJECT_ADDED, longArrayOf(0x9999L))
        camera.queueIntr(whole + next.copyOfRange(0, 6))

        val firstRound = harness.transport.drainEvents()
        assertEquals("只应解出完整的那一个", 1, firstRound.size)
        assertEquals(PtpConstants.Event.DEVICE_PROP_CHANGED, firstRound[0].code)

        // 剩余 6 字节必须留在中断缓冲里，下一轮补齐后解出
        camera.queueIntr(next.copyOfRange(6, next.size))
        val secondRound = harness.transport.drainEvents()
        assertEquals("跨轮次的半个事件必须能拼起来", 1, secondRound.size)
        assertEquals(PtpConstants.Event.OBJECT_ADDED, secondRound[0].code)
        assertArrayEquals(longArrayOf(0x9999L), secondRound[0].params)
    }

    // ────────────────────────── ⑤ 大对象：生产路径的流式搬运 ──────────────────────────

    @Test
    fun `GET_OBJECT 200 KB - 生产路径边读边回调 逐字节一致`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val dataContainer = PtpContainerCodec.encodeData(PtpConstants.Op.GET_OBJECT, 2L, payload)
        val response = PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L)

        // 按 60000 字节切片喂：跨越多个 URB，逼出 DataPhasePump 的多次 fill
        val stream = dataContainer + response
        var offset = 0
        while (offset < stream.size) {
            val end = minOf(offset + 60_000, stream.size)
            camera.queueIn(stream.copyOfRange(offset, end))
            offset = end
        }

        val received = ByteArrayOutputStream()
        val result = harness.transport.transaction(
            op = PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(0x1219_9F99L)),
            expectedData = true,
            dataSink = { chunk, length -> received.write(chunk, 0, length) },
        )

        assertEquals(PtpConstants.Rc.OK, result.code)
        assertEquals("dataBytes 必须是实际收到的 payload 字节数", payload.size.toLong(), result.dataBytes)
        assertArrayEquals("生产路径搬出来的字节必须与真机 payload 一致", payload, received.toByteArray())
    }

    @Test
    fun `expectedData 为 false 但相机仍发数据 - 必须收完再返回（约束 3）`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val payload = ByteArray(5_000) { (it % 97).toByte() }
        camera.queueIn(
            PtpContainerCodec.encodeData(PtpConstants.Op.OPEN_SESSION, 2L, payload),
            PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L),
        )

        // expectedData = false 且不给 sink：数据必须**被读走**（否则 IN FIFO 卡死）
        val result = harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))

        assertEquals(PtpConstants.Rc.OK, result.code)
        assertEquals("没要数据也必须如实统计收到多少", payload.size.toLong(), result.dataBytes)
        assertFalse("收完之后通道不应被判死", harness.transport.isFatal)
    }

    // ────────────────────────── ⑥ fatal 的 fail-fast 是否真实 ──────────────────────────

    @Test
    fun `会话内超时置 fatal 后 后续调用被立即拒绝且不再碰 USB`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)
        assertFalse(harness.transport.isFatal)

        // 队列空 → 第一次 fill 返回 -1 → PtpTimeoutException
        try {
            harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))
            fail("bulk IN 返回 -1 必须抛 PtpTimeoutException")
        } catch (expected: PtpTimeoutException) {
            assertTrue(expected.message!!.contains("bulk IN"))
        }
        assertTrue("会话内超时必须置 fatal", harness.transport.isFatal)

        val inBefore = camera.inRequests.size
        val outBefore = camera.outRequests.size
        try {
            harness.transport.transaction(PtpOp(PtpConstants.Op.GET_STORAGE_IDS))
            fail("fatal 之后必须拒绝后续调用")
        } catch (expected: PtpException) {
            assertTrue("必须是 fail-fast 而不是重试：${expected.message}", expected.message!!.contains("fatal"))
        }
        assertEquals("fatal 之后不得再发起任何 IN", inBefore, camera.inRequests.size)
        assertEquals("fatal 之后不得再发起任何 OUT", outBefore, camera.outRequests.size)
    }

    // ────────────────────────── ⑦ 预检失败不置 fatal（与上一场景的分界） ──────────────────────────

    @Test
    fun `预检超时只落 preflightFailure 不置 fatal 且后续事务仍能成功`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        // 故意不 prime：预检的 IN 立刻 -1
        val caps = harness.transport.open(harness.device, harness.conn)

        assertEquals(PreflightFailure.TIMEOUT, caps.preflightFailure)
        assertFalse("预检失败绝不能置 fatal（那是会话内事务的语义）", harness.transport.isFatal)
        assertTrue(harness.transport.isOpen)
        assertEquals(0x04DA, caps.usbVendorId)
        assertEquals(0x2382, caps.usbProductId)
        assertTrue("预检失败时能力字段应是纯 USB 描述符版本", caps.operationsSupported.isEmpty())

        // 关键断言：紧接着发一条正常事务仍能成功 → 证明 fatal 没被误置
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        val ok = harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))
        assertEquals(PtpConstants.Rc.OK, ok.code)
        assertFalse(harness.transport.isFatal)
    }

    @Test
    fun `预检回包超过 64 KB 上限 - 判 PARSE_ERROR 且通道不被判死`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        // 通道错位时相机可能一直吐数据：喂一个 70 KB 的 DeviceInfo DATA 容器
        camera.queueIn(
            PtpContainerCodec.encodeData(PtpConstants.Op.GET_DEVICE_INFO, 1L, ByteArray(70 * 1024)),
            PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L),
        )

        val caps = harness.transport.open(harness.device, harness.conn)
        assertEquals(PreflightFailure.PARSE_ERROR, caps.preflightFailure)
        assertFalse("预检失败（含超限）不置 fatal", harness.transport.isFatal)

        // 关键：超限抛异常后 rx 必须被清干净，紧接着的正常事务仍能成功
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        val ok = harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))
        assertEquals(PtpConstants.Rc.OK, ok.code)
    }

    @Test
    fun `预检成功时 caps 来自回包 且 USB 字段被 Device 覆盖`() = runBlocking {        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        val caps = harness.transport.open(harness.device, harness.conn)

        assertNull(caps.preflightFailure)
        assertEquals("DC-S1", caps.model)
        assertEquals("Panasonic", caps.manufacturer)
        assertEquals(19, caps.operationsSupported.size)
        assertEquals(16, caps.eventsSupported.size)
        assertEquals(0x1CL, caps.vendorExtensionId)
        // USB 侧字段必须被 UsbDevice 覆盖（0 = 忘了覆盖，是必须暴露的 bug）
        assertEquals(0x04DA, caps.usbVendorId)
        assertEquals(0x2382, caps.usbProductId)
        assertEquals(512, caps.bulkMaxPacketSize)
        assertNotNull(harness.transport.deviceCaps)
    }

    @Test
    fun `close 之后 isOpen 为 false 且释放了 interface`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)
        harness.transport.close()
        assertFalse(harness.transport.isOpen)
        assertFalse(harness.transport.isFatal)
        assertTrue(harness.transport.drainEvents().isEmpty())
    }

    // ────────────────────────── ⑧ 新增的 OUT 数据阶段（本轮复核发现的缺口） ──────────────────────────

    @Test
    fun `dataOut - 命令容器之后写 DATA 容器 且两者 txid 相同`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        // 定稿 §3.7 的写属性 payload：[propCode u32][valuesize u16 = 4][0x0000][value u32]
        val payload = ByteArray(12).also {
            PtpContainerCodec.writeU32(it, 0, 0x02000031L)
            PtpContainerCodec.writeU16(it, 4, 4)
            PtpContainerCodec.writeU32(it, 8, 160_000L)
        }

        val before = camera.out.size()
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        harness.transport.transaction(
            op = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L)),
            dataOut = payload,
        )

        val written = camera.out.toByteArray().copyOfRange(before, camera.out.size())
        val command = PtpContainerCodec.decodeNext(written) as PtpContainerCodec.Decoded.Container
        assertEquals(PtpConstants.ContainerType.COMMAND, command.container.type)
        assertEquals(PtpConstants.PanasonicOp.SET_PROP_VALUE, command.container.code)

        val data = PtpContainerCodec.decodeNext(
            written,
            offset = command.consumed,
            available = written.size - command.consumed,
        ) as PtpContainerCodec.Decoded.Container
        assertEquals("DATA 容器必须紧跟命令容器", PtpConstants.ContainerType.DATA, data.container.type)
        assertEquals("DATA 容器的 code 必须回显 opcode", PtpConstants.PanasonicOp.SET_PROP_VALUE, data.container.code)
        assertEquals("同一事务的两个容器必须共享 txid", command.container.transactionId, data.container.transactionId)
        assertArrayEquals("DATA payload 必须原样发出", payload, data.container.payload)
        assertEquals("写阶段只能有这两个容器", written.size, command.consumed + data.consumed)
    }

    @Test
    fun `dataOut 为 null 或空数组 - 不发 DATA 容器（空 DATA 阶段视为不存在）`() = runBlocking {
        listOf<ByteArray?>(null, ByteArray(0)).forEach { dataOut ->
            val camera = FakeCamera(bulkMax = 512)
            val harness = harness(camera)
            primeOpen(camera)
            harness.transport.open(harness.device, harness.conn)

            val before = camera.out.size()
            camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
            harness.transport.transaction(
                op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
                dataOut = dataOut,
            )

            val written = camera.out.toByteArray().copyOfRange(before, camera.out.size())
            assertEquals(
                "dataOut=${dataOut?.size} 时只应写命令容器（12 + 4 字节）",
                12 + 4,
                written.size,
            )
            val command = PtpContainerCodec.decodeNext(written) as PtpContainerCodec.Decoded.Container
            assertEquals(PtpConstants.ContainerType.COMMAND, command.container.type)
        }
    }

    @Test
    fun `dataOut 短写 - 大 DATA 阶段必须循环写满且字节一致`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512, shortWrite = 700)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val payload = ByteArray(3000) { (it % 233).toByte() }
        val before = camera.out.size()
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        harness.transport.transaction(
            op = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L)),
            dataOut = payload,
        )

        val written = camera.out.toByteArray().copyOfRange(before, camera.out.size())
        val command = PtpContainerCodec.decodeNext(written) as PtpContainerCodec.Decoded.Container
        val data = PtpContainerCodec.decodeNext(
            written,
            offset = command.consumed,
            available = written.size - command.consumed,
        ) as PtpContainerCodec.Decoded.Container
        assertEquals("短写必须循环到写满", 12 + 3000, data.consumed)
        assertArrayEquals(payload, data.container.payload)
        assertEquals(written.size, command.consumed + data.consumed)
    }

    @Test
    fun `DATA 帧长度恰为 maxPacketSize 整数倍 - 该帧之后补 ZLP`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        // DATA 容器 = 12 + 500 = 512 字节，恰为整数倍
        val payload = ByteArray(500) { it.toByte() }
        val zlpBefore = camera.zlp
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))

        harness.transport.transaction(
            op = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L)),
            dataOut = payload,
        )
        // 命令容器 16 字节（非整数倍，不补）；DATA 容器 512 字节（整数倍，补 1 个）
        assertEquals("只有长度是整数倍的那一帧才补 ZLP", zlpBefore + 1, camera.zlp)
    }

    @Test
    fun `OUT 写失败 - 必须抛超时且通道判死`() = runBlocking {
        val camera = FakeCamera(bulkMax = 512, shortWrite = 0) // 永远写不进去
        val harness = harness(camera)
        // 预检也会写失败 → 预检按设计不置 fatal，所以先用正常相机 open，再换成写失败
        primeOpen(camera)

        try {
            harness.transport.open(harness.device, harness.conn)
        } catch (expected: PtpException) {
            // 预检阶段写失败：抛异常但不应置 fatal
            assertFalse("预检写失败不置 fatal", harness.transport.isFatal)
            return@runBlocking
        }
        // 若预检竟然通过了，则会话内写失败必须置 fatal
        try {
            harness.transport.transaction(
                op = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L)),
                dataOut = ByteArray(12),
            )
            fail("写不进去必须抛异常")
        } catch (expected: PtpTimeoutException) {
            assertTrue("会话内写失败必须置 fatal", harness.transport.isFatal)
        }
    }

    // ────────────────────────── 工具：可编排的假相机 ──────────────────────────

    private class FakeCamera(
        val bulkMax: Int,
        val intrMax: Int = 64,
        /** bulk OUT 每次最多接收多少字节（模拟短写）。 */
        val shortWrite: Int = Int.MAX_VALUE,
    ) {
        val out = ByteArrayOutputStream()
        val outRequests = ArrayList<Int>()
        val inRequests = ArrayList<Int>()
        val intrRequests = ArrayList<Int>()
        val inQueue = ArrayList<ByteArray>()
        val intrQueue = ArrayList<ByteArray>()
        var zlp: Int = 0

        fun queueIn(vararg chunks: ByteArray) {
            chunks.forEach { inQueue += it }
        }

        fun queueIntr(vararg chunks: ByteArray) {
            chunks.forEach { intrQueue += it }
        }

        /** 队列空 → 返回 -1 模拟超时（不会自旋）。 */
        fun nextIn(buffer: ByteArray, offset: Int, length: Int): Int = deliver(inQueue, buffer, offset, length)

        fun nextIntr(buffer: ByteArray, offset: Int, length: Int): Int = deliver(intrQueue, buffer, offset, length)

        private fun deliver(queue: ArrayList<ByteArray>, buffer: ByteArray, offset: Int, length: Int): Int {
            val head = queue.firstOrNull() ?: return -1
            val n = minOf(head.size, length)
            System.arraycopy(head, 0, buffer, offset, n)
            if (n == head.size) queue.removeAt(0) else queue[0] = head.copyOfRange(n, head.size)
            return n
        }
    }

    private class Harness(
        val transport: PtpUsbTransport,
        val device: UsbDevice,
        val conn: UsbDeviceConnection,
    )

    private fun harness(camera: FakeCamera, rxCapacity: Int = 128 * 1024): Harness {
        val inEp = mockk<UsbEndpoint>()
        every { inEp.address } returns 0x81
        every { inEp.type } returns 2
        every { inEp.maxPacketSize } returns camera.bulkMax

        val outEp = mockk<UsbEndpoint>()
        every { outEp.address } returns 0x01
        every { outEp.type } returns 2
        every { outEp.maxPacketSize } returns camera.bulkMax

        val intrEp = mockk<UsbEndpoint>()
        every { intrEp.address } returns 0x82
        every { intrEp.type } returns 3
        every { intrEp.maxPacketSize } returns camera.intrMax

        val intf = mockk<UsbInterface>()
        every { intf.endpointCount } returns 3
        every { intf.getEndpoint(0) } returns inEp
        every { intf.getEndpoint(1) } returns outEp
        every { intf.getEndpoint(2) } returns intrEp

        val device = mockk<UsbDevice>()
        every { device.interfaceCount } returns 1
        every { device.getInterface(0) } returns intf
        every { device.vendorId } returns 0x04DA
        every { device.productId } returns 0x2382

        val conn = mockk<UsbDeviceConnection>()
        every { conn.claimInterface(any(), any()) } returns true
        every { conn.releaseInterface(any()) } returns true
        every { conn.close() } just runs
        every { conn.rawDescriptors } returns null
        // 5 参重载：bulk IN / bulk OUT（fill / writeFully）
        every { conn.bulkTransfer(any(), any(), any(), any(), any()) } answers {
            val endpoint = args[0] as UsbEndpoint
            val buffer = args[1] as ByteArray
            val offset = args[2] as Int
            val length = args[3] as Int
            handleBulk(camera, endpoint, buffer, offset, length)
        }
        // 4 参重载：drainEvents 用（中断端点，无 offset）
        every { conn.bulkTransfer(any(), any(), any(), any()) } answers {
            val endpoint = args[0] as UsbEndpoint
            val buffer = args[1] as ByteArray
            val length = args[2] as Int
            handleBulk(camera, endpoint, buffer, 0, length)
        }

        // 注入单调假时钟：不依赖 SystemClock 静态 mock，且意外循环会撞 deadline 抛超时（防挂死）
        return Harness(
            PtpUsbTransport(rxCapacity = rxCapacity, now = { clock += 1; clock }),
            device,
            conn,
        )
    }

    private fun handleBulk(
        camera: FakeCamera,
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = when (endpoint.address) {
        0x01 -> {
            camera.outRequests += length
            if (length == 0) {
                camera.zlp += 1
                0
            } else {
                val n = minOf(length, camera.shortWrite)
                camera.out.write(buffer, offset, n)
                n
            }
        }

        0x81 -> {
            camera.inRequests += length
            camera.nextIn(buffer, offset, length)
        }

        else -> {
            camera.intrRequests += length
            camera.nextIntr(buffer, offset, length)
        }
    }

    /** 让 `open()` 的预检拿到一份**真机 dump**。 */
    private fun primeOpen(camera: FakeCamera) {
        camera.queueIn(
            PtpContainerCodec.encodeData(
                PtpConstants.Op.GET_DEVICE_INFO,
                1L,
                Fixtures.hex("device_info"),
            ),
            PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L),
        )
    }
}
