package cn.alittlecookie.lutista.tether

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.PtpDiagFrame
import cn.alittlecookie.lutista.tether.transport.PtpDiagSink
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
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
 * T01b 诊断钩子的 JVM 单测：**在真实 `PtpUsbTransport` 上**驱动 USB 路径。
 *
 * 沿用 `QaUsbTransportScriptedTest.kt` 的三条防挂死措施：
 * 1. `PtpUsbTransport(now = ...)` 注入单调递增假时钟；
 * 2. IN 队列取空返回 -1（模拟超时），不会「永远返回 0 长包」自旋；
 * 3. `UsbDeviceConnection` 用严格 mock，任何未 stub 的调用立刻抛 `MockKException`。
 *
 * 覆盖的任务判据：
 * - ① diag 非空时收到 bulk IN 的对齐 `request` 与真实 `read`；
 * - ② 带 `dataOut` 时收到 `[CMD][DATA]` 两帧、顺序固定、txid 相同；
 * - ③ 短写脚本（每次只吞 3 字节）→ 多次 OUT 回调、`written` 累加、整数倍帧补 ZLP；
 * - ④ `diag == null` 时同一脚本跑两遍，写出内容与事务结果逐字节一致；
 * - ⑤ `read == 0`（ZLP）与 `read < 0`（超时）两条分支各有回调。
 */
class PtpDiagSinkTest {

    private var clock: Long = 0
    private var clockCalls: Int = 0

    @Before
    fun setUp() {
        UsbIoThread.resetForTest()
        UsbIoThread.bind()
        clock = 1_000L
        clockCalls = 0
    }

    @After
    fun tearDown() {
        UsbIoThread.resetForTest()
    }

    // ────────────────────── ① bulk IN 的对齐 request / 真实 read ──────────────────────

    @Test
    fun `diag 非空时能收到 bulk IN 的对齐 request 与真实 read`() = runBlocking {
        listOf(512, 1024).forEach { maxPacket ->
            val sink = RecordingSink()
            val camera = FakeCamera(bulkMax = maxPacket)
            val harness = harness(camera, sink)
            primeOpen(camera)
            harness.transport.open(harness.device, harness.conn)

            assertTrue("maxPacket=$maxPacket 未收到任何 bulk IN 回调", sink.bulkIn.isNotEmpty())
            val first = sink.bulkIn.first()
            assertEquals("request 必须是 64 KiB 且按 maxPacket 对齐", 64 * 1024, first.request)
            assertEquals("request 必须是 maxPacket=$maxPacket 的整数倍", 0, first.request % maxPacket)
            assertEquals(maxPacket, first.maxPacketSize)
            assertEquals("endpointAddress 必须是 bulk IN 端点 0x81", 0x81, first.endpointAddress)
            assertEquals(
                "read 必须是 bulkTransfer 的原始返回值（DeviceInfo DATA 容器长度）",
                deviceInfoDataContainer().size,
                first.read,
            )
            assertFalse(first.isZlp)
            assertFalse(first.isTimeout)
        }
    }

    @Test
    fun `bulk IN 的 request 直出 maxPacketSize 对齐后的真值 - 非 2 的幂也一样`() = runBlocking {
        val sink = RecordingSink()
        val camera = FakeCamera(bulkMax = 1000)
        val harness = harness(camera, sink)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val first = sink.bulkIn.first()
        assertEquals("65536 向下取整到 1000 的整数倍应为 65000", 65_000, first.request)
        assertEquals(0, first.request % 1000)
        assertEquals(1000, first.maxPacketSize)
        assertTrue("对齐后必须小于 64 KiB，实际 ${first.request}", first.request < 64 * 1024)
    }

    // ────────────────────── ② [CMD][DATA] 顺序与 txid 相同 ──────────────────────

    @Test
    fun `带 dataOut 时 diag 收到 CMD DATA 两帧 顺序固定且 txid 相同`() = runBlocking {
        val sink = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, sink)
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

        val event = sink.frames.lastOrNull { it.opcode == PtpConstants.PanasonicOp.SET_PROP_VALUE }
        assertNotNull("未收到写事务的 onFrames 回调", event)
        val frames = event!!.frames
        assertEquals("有 dataOut 时必须恰好两帧", 2, frames.size)
        assertEquals(0, frames[0].frameIndex)
        assertEquals(1, frames[1].frameIndex)
        assertEquals("[CMD] 必须是命令容器", PtpConstants.ContainerType.COMMAND, frames[0].type)
        assertEquals("[DATA] 必须是数据容器", PtpConstants.ContainerType.DATA, frames[1].type)
        assertEquals("[CMD][DATA] 必须共享同一个 txid", frames[0].transactionId, frames[1].transactionId)
        assertEquals("预检预占 txid=1，本事务应为 2", 2L, frames[0].transactionId)
        assertEquals(PtpConstants.PanasonicOp.SET_PROP_VALUE, frames[0].code)
        assertEquals("DATA 容器 code 必须回显 opcode", PtpConstants.PanasonicOp.SET_PROP_VALUE, frames[1].code)
        assertEquals(12 + 4, frames[0].length)
        assertEquals(12 + payload.size, frames[1].length)

        // 与实际写出的字节交叉复核，证明钩子给的是 build() 真值而不是镜像重建值。
        val written = camera.out.toByteArray().copyOfRange(before, camera.out.size())
        val decoded = PtpContainerCodec.decodeNext(written) as PtpContainerCodec.Decoded.Container
        assertEquals(PtpConstants.ContainerType.COMMAND, decoded.container.type)
        assertEquals(frames[0].transactionId, decoded.container.transactionId)
        assertEquals(frames[0].length, decoded.consumed)
    }

    @Test
    fun `dataOut 为 null 时 diag 只收到一帧命令容器`() = runBlocking {
        val sink = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, sink)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        harness.transport.transaction(
            op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
            dataOut = null,
        )

        val event = sink.frames.last()
        assertEquals(PtpConstants.Op.OPEN_SESSION, event.opcode)
        assertEquals("无 dataOut 时只发命令容器", 1, event.frames.size)
        assertEquals(PtpConstants.ContainerType.COMMAND, event.frames[0].type)
        assertEquals(0, event.frames[0].frameIndex)
    }

    // ────────────────────── ③ 短写循环 / ZLP 的每次 OUT 真值 ──────────────────────

    @Test
    fun `短写每次只吞 3 字节 - 多次 OUT 回调 written 累加 且整数倍帧补 ZLP`() = runBlocking {
        val sink = RecordingSink()
        val camera = FakeCamera(bulkMax = 4, shortWrite = 3)
        val harness = harness(camera, sink)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val before = sink.bulkOut.size
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))

        val events = sink.bulkOut.subList(before, sink.bulkOut.size)
        val writes = events.filter { !it.isZlp }
        assertEquals("命令容器 16 字节、每次吞 3 字节 → 6 次写", 6, writes.size)
        assertEquals(listOf(0, 3, 6, 9, 12, 15), writes.map { it.offset })
        assertEquals(listOf(16, 13, 10, 7, 4, 1), writes.map { it.length })
        assertEquals(listOf(3, 3, 3, 3, 3, 1), writes.map { it.written })
        assertEquals("written 逐次累加必须写满整帧", 16, writes.sumOf { it.written })
        assertEquals("writeSequence 必须在本次事务内从 1 单调递增", (1..7).toList(), events.map { it.writeSequence })
        assertTrue("所有写都应归属同一帧 frameIndex=0", writes.all { it.frameIndex == 0 })

        val zlps = events.filter { it.isZlp }
        assertEquals("帧长 16 % 4 == 0，必须补 1 个 ZLP", 1, zlps.size)
        assertEquals(0, zlps[0].length)
        assertEquals(0, zlps[0].written)
        assertEquals(0, zlps[0].frameIndex)
        assertEquals("整条脚本共 2 个 ZLP（预检 1 + 本事务 1）", 2, camera.zlp)
    }

    // ────────────────────── ④ diag == null 的零行为变更 ──────────────────────

    @Test
    fun `diag 为 null 时行为与改动前完全一致 - 同一脚本跑两遍逐字节比对`() {
        val withDiag = runScript(RecordingSink())
        val withoutDiag = runScript(null)

        assertArrayEquals("写出内容必须逐字节一致", withDiag.outBytes, withoutDiag.outBytes)
        assertEquals("事务结果必须一致", withDiag.response, withoutDiag.response)
        assertEquals("isFatal 必须一致", withDiag.isFatal, withoutDiag.isFatal)
        assertEquals("bulk IN 请求序列必须一致", withDiag.inRequests, withoutDiag.inRequests)
        assertEquals("bulk OUT 请求序列必须一致", withDiag.outRequests, withoutDiag.outRequests)
        assertEquals("ZLP 次数必须一致", withDiag.zlp, withoutDiag.zlp)
        // 启用 diag 时只在两条事务的 finally 里各多读一次时钟算耗时；关闭时一次都不多读。
        assertEquals(
            "diag 启用时只能多 2 次时钟读取（两条事务各一次），不得有其它额外开销",
            withoutDiag.clockCalls + 2,
            withDiag.clockCalls,
        )
    }

    // ────────────────────── ⑤ read == 0（ZLP）与 read < 0（超时） ──────────────────────

    @Test
    fun `read 等于 0 的 ZLP 分支有回调且继续读`() = runBlocking {
        val sink = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, sink)
        camera.queueIn(
            ByteArray(0), // 整包边界上的 0 长包（ZLP）
            deviceInfoDataContainer(),
            PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L),
        )
        harness.transport.open(harness.device, harness.conn)

        val zlp = sink.bulkIn.firstOrNull { it.isZlp }
        assertNotNull("read == 0 必须产生 onBulkIn 回调", zlp)
        assertEquals(0, zlp!!.read)
        assertEquals(64 * 1024, zlp.request)
        assertEquals(512, zlp.maxPacketSize)
        assertEquals(0x81, zlp.endpointAddress)
        assertFalse(zlp.isTimeout)
        assertTrue(
            "ZLP 之后必须继续读到 DeviceInfo 数据",
            sink.bulkIn.any { !it.isZlp && !it.isTimeout && it.read > 0 },
        )
        assertNull(harness.transport.deviceCaps?.preflightFailure)

        val preflightTx = sink.transactions.first { it.isPreflight }
        assertEquals(PtpConstants.Op.GET_DEVICE_INFO, preflightTx.opcode)
        assertEquals(PtpConstants.Rc.OK, preflightTx.responseCode!!)
        assertEquals(223L, preflightTx.dataBytes)
        assertFalse(preflightTx.isFatal)
        assertNull(preflightTx.exceptionType)
        assertNull(preflightTx.exceptionMessage)
        assertTrue("耗时 ms 必须非负", preflightTx.elapsedMs >= 0)
    }

    @Test
    fun `read 小于 0 的超时分支有回调且事务级回调带完整异常真值`() = runBlocking {
        val sink = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, sink)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        try {
            harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))
            fail("bulk IN 返回 -1 必须抛 PtpTimeoutException")
        } catch (expected: PtpTimeoutException) {
            assertTrue(expected.message!!.contains("bulk IN 超时/失败"))
        }

        val timeout = sink.bulkIn.lastOrNull { it.isTimeout }
        assertNotNull("read < 0 必须产生 onBulkIn 回调", timeout)
        assertEquals(-1, timeout!!.read)
        assertEquals(64 * 1024, timeout.request)
        assertEquals(512, timeout.maxPacketSize)
        assertEquals(0x81, timeout.endpointAddress)
        assertFalse(timeout.isZlp)
        assertTrue(harness.transport.isFatal)

        val tx = sink.transactions.last()
        assertEquals(PtpConstants.Op.OPEN_SESSION, tx.opcode)
        assertNull(tx.responseCode)
        assertEquals(0L, tx.dataBytes)
        assertTrue(tx.isFatal)
        assertFalse(tx.isPreflight)
        assertEquals("PtpTimeoutException", tx.exceptionType)
        assertNotNull(tx.exceptionMessage)
        assertTrue(
            "异常 message 必须完整（含 ep / request / timeout）",
            tx.exceptionMessage!!.contains("bulk IN 超时/失败（ep=0x81 request=65536"),
        )
    }

    @Test
    fun `诊断采集器抛错不改变真实USB收发或事务结果`() {
        val baseline = runScript(null)
        for (callback in listOf("frames", "bulkIn", "bulkOut", "transaction")) {
            for (failure in listOf(IllegalStateException("diag"), PtpTimeoutException("diag"), AssertionError("diag"))) {
                val actual = runScript(ThrowingSink(callback, failure))
                assertArrayEquals(callback, baseline.outBytes, actual.outBytes)
                assertEquals(callback, baseline.response, actual.response)
                assertEquals(callback, baseline.inRequests, actual.inRequests)
                assertEquals(callback, baseline.outRequests, actual.outRequests)
                assertEquals(callback, baseline.zlp, actual.zlp)
                assertFalse(callback, actual.isFatal)
                assertEquals(callback, baseline.clockCalls + 2, actual.clockCalls)
            }
        }
    }

    @Test
    fun `诊断抛错不能覆盖原始超时异常与fatal真值`() = runBlocking {
        val recording = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, ThrowingSink("all", IllegalStateException("diag"), recording))
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)
        var original: PtpTimeoutException? = null
        try {
            harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)))
            fail("真实 bulk IN 超时必须保留")
        } catch (expected: PtpTimeoutException) {
            original = expected
        }
        assertTrue(harness.transport.isFatal)
        val tx = recording.transactions.last()
        assertTrue(tx.isFatal)
        assertEquals(original!!.message, tx.exceptionMessage)
        assertEquals("PtpTimeoutException", tx.exceptionType)
    }

    @Test
    fun `数据阶段中途超时只统计已完整完成的容器`() = runBlocking {
        val recording = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, recording)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)
        val opcode = PtpConstants.Op.GET_DEVICE_INFO
        val full = PtpContainerCodec.encodeData(opcode, 2L, byteArrayOf(1, 2, 3))
        val partial = PtpContainerCodec.encodeData(opcode, 2L, ByteArray(10)).copyOfRange(0, 16)
        camera.queueIn(full, partial)
        var delivered = 0
        try {
            harness.transport.transaction(PtpOp(opcode), expectedData = true, dataSink = { _, count -> delivered += count })
            fail("未收满声明长度必须超时")
        } catch (_: PtpTimeoutException) {
            assertEquals("完整3字节加部分4字节确已交付消费方", 7, delivered)
        }
        val tx = recording.transactions.last()
        assertEquals("未完成容器的部分字节不计入事务累计", 3L, tx.dataBytes)
        assertNull(tx.responseCode)
        assertTrue(tx.isFatal)
    }

    @Test
    fun `预检失败原因从open返回值取得且可能晚于事务成功回调`() = runBlocking {
        val recording = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, recording)
        camera.queueIn(
            PtpContainerCodec.encodeData(PtpConstants.Op.GET_DEVICE_INFO, 1L, byteArrayOf(1, 2)),
            PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L),
        )
        val caps = harness.transport.open(harness.device, harness.conn)
        assertEquals(PreflightFailure.PARSE_ERROR, caps.preflightFailure)
        assertEquals(PtpConstants.Rc.OK, recording.transactions.single().responseCode!!)
        assertNull(recording.transactions.single().exceptionType)
        assertFalse(harness.transport.isFatal)
    }

    @Test
    fun `响应码为零也必须直出原始值`() = runBlocking {
        val recording = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, recording)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)
        camera.queueIn(PtpContainerCodec.encodeResponse(0, 2L))
        assertEquals(0, harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION)).code)
        assertEquals(0, recording.transactions.last().responseCode!!)
        assertFalse(harness.transport.isFatal)
    }

    @Test
    fun `消费方异常保持同一实例且不额外改变fatal规则`() = runBlocking {
        val recording = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, recording)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)
        camera.queueIn(PtpContainerCodec.encodeData(PtpConstants.Op.GET_DEVICE_INFO, 2L, byteArrayOf(1)))
        val failure = IllegalArgumentException("consumer failure")
        try {
            harness.transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO), expectedData = true, dataSink = { _, _ -> throw failure })
            fail("消费方异常必须原样抛出")
        } catch (actual: IllegalArgumentException) {
            assertTrue(failure === actual)
        }
        assertFalse(harness.transport.isFatal)
        assertEquals("IllegalArgumentException", recording.transactions.last().exceptionType)
        assertEquals(failure.message, recording.transactions.last().exceptionMessage)
    }

    @Test
    fun `fatal后的再次调用不触发USB或伪造事务回调`() = runBlocking {
        val recording = RecordingSink()
        val camera = FakeCamera(bulkMax = 512)
        val harness = harness(camera, recording)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)
        try {
            harness.transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION))
            fail("缺少响应必须超时")
        } catch (_: PtpTimeoutException) { }
        val reads = camera.inRequests.size
        val writes = camera.outRequests.size
        val transactions = recording.transactions.size
        try {
            harness.transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO))
            fail("fatal必须fail-fast")
        } catch (expected: cn.alittlecookie.lutista.tether.transport.PtpException) {
            assertEquals("传输层已置 fatal（上一次超时/NACK），请把相机电源关掉再打开后重新连接", expected.message)
        }
        assertEquals(reads, camera.inRequests.size)
        assertEquals(writes, camera.outRequests.size)
        assertEquals(transactions, recording.transactions.size)
    }

    // ────────────────────────────── 工具 ──────────────────────────────

    /** `open()` 预检收到的真机 dump：DeviceInfo DATA 容器（12 B 头 + 223 B payload）。 */
    private fun deviceInfoDataContainer(): ByteArray = PtpContainerCodec.encodeData(
        PtpConstants.Op.GET_DEVICE_INFO,
        1L,
        Fixtures.hex("device_info"),
    )

    /**
     * 同一段脚本在 `diag` 开 / 关两种情形下各跑一遍，收集可逐字节比对的结果。
     * 故意带短写 + 整数倍 DATA 帧，逼出短写循环与 ZLP 两条路径。
     */
    private fun runScript(diag: PtpDiagSink?): RunResult = runBlocking {
        val callsBefore = clockCalls
        val camera = FakeCamera(bulkMax = 4, shortWrite = 3)
        val harness = harness(camera, diag)
        primeOpen(camera)
        harness.transport.open(harness.device, harness.conn)

        val payload = ByteArray(500) { (it % 251).toByte() }
        camera.queueIn(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        val response = harness.transport.transaction(
            op = PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(0x02000031L)),
            dataOut = payload,
        )

        RunResult(
            outBytes = camera.out.toByteArray(),
            response = response,
            isFatal = harness.transport.isFatal,
            inRequests = camera.inRequests.toList(),
            outRequests = camera.outRequests.toList(),
            zlp = camera.zlp,
            clockCalls = clockCalls - callsBefore,
        )
    }

    private data class RunResult(
        val outBytes: ByteArray,
        val response: PtpResponse,
        val isFatal: Boolean,
        val inRequests: List<Int>,
        val outRequests: List<Int>,
        val zlp: Int,
        val clockCalls: Int,
    )

    /** 可编排的假相机（与 `QaUsbTransportScriptedTest.kt` 同构）。 */
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

        /** 队列空 → 返回 -1 模拟超时（不会自旋）。空 chunk → 返回 0 模拟 ZLP。 */
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

    private fun harness(camera: FakeCamera, diag: PtpDiagSink?, rxCapacity: Int = 128 * 1024): Harness {
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
        // 5 参重载：bulk IN / bulk OUT（fill / writeFrames）
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

        // 注入单调假时钟：不依赖 SystemClock 静态 mock，且意外循环会撞 deadline 抛超时（防挂死）。
        return Harness(
            PtpUsbTransport(
                rxCapacity = rxCapacity,
                now = { clock += 1; clockCalls += 1; clock },
                diag = diag,
            ),
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
            deviceInfoDataContainer(),
            PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L),
        )
    }
}

// ────────────────────── 记录型 PtpDiagSink（消费方 T02Diag 的接线示例） ──────────────────────

private data class BulkInEvent(
    val request: Int,
    val read: Int,
    val maxPacketSize: Int,
    val endpointAddress: Int,
    val isZlp: Boolean,
    val isTimeout: Boolean,
)

private data class BulkOutEvent(
    val frameIndex: Int,
    val writeSequence: Int,
    val offset: Int,
    val length: Int,
    val written: Int,
    val isZlp: Boolean,
)

private data class FramesEvent(val opcode: Int, val frames: List<PtpDiagFrame>)

private data class TxEvent(
    val opcode: Int,
    val transactionId: Long,
    val responseCode: Int?,
    val dataBytes: Long,
    val elapsedMs: Long,
    val isFatal: Boolean,
    val isPreflight: Boolean,
    val exceptionType: String?,
    val exceptionMessage: String?,
)

private class ThrowingSink(
    private val callback: String,
    private val failure: Throwable,
    private val delegate: PtpDiagSink = PtpDiagSink.NO_OP,
) : PtpDiagSink {
    private fun failAt(name: String) {
        if (callback == name || callback == "all") throw failure
    }

    override fun onFrames(opcode: Int, frames: List<PtpDiagFrame>) {
        delegate.onFrames(opcode, frames)
        failAt("frames")
    }

    override fun onBulkIn(request: Int, read: Int, maxPacketSize: Int, endpointAddress: Int, isZlp: Boolean, isTimeout: Boolean) {
        delegate.onBulkIn(request, read, maxPacketSize, endpointAddress, isZlp, isTimeout)
        failAt("bulkIn")
    }

    override fun onBulkOut(frameIndex: Int, writeSequence: Int, offset: Int, length: Int, written: Int, isZlp: Boolean) {
        delegate.onBulkOut(frameIndex, writeSequence, offset, length, written, isZlp)
        failAt("bulkOut")
    }

    override fun onFillRejected(tail: Int, maxPacketSize: Int) {
        delegate.onFillRejected(tail, maxPacketSize)
        failAt("fillRejected")
    }

    override fun onTransaction(
        opcode: Int,
        transactionId: Long,
        responseCode: Int?,
        dataBytes: Long,
        elapsedMs: Long,
        isFatal: Boolean,
        isPreflight: Boolean,
        exceptionType: String?,
        exceptionMessage: String?,
    ) {
        delegate.onTransaction(opcode, transactionId, responseCode, dataBytes, elapsedMs, isFatal, isPreflight, exceptionType, exceptionMessage)
        failAt("transaction")
    }
}

private class RecordingSink : PtpDiagSink {
    val bulkIn = ArrayList<BulkInEvent>()
    val bulkOut = ArrayList<BulkOutEvent>()
    val frames = ArrayList<FramesEvent>()
    val transactions = ArrayList<TxEvent>()
    val fillRejected = ArrayList<Pair<Int, Int>>()

    override fun onBulkIn(
        request: Int,
        read: Int,
        maxPacketSize: Int,
        endpointAddress: Int,
        isZlp: Boolean,
        isTimeout: Boolean,
    ) {
        bulkIn += BulkInEvent(request, read, maxPacketSize, endpointAddress, isZlp, isTimeout)
    }

    override fun onFillRejected(tail: Int, maxPacketSize: Int) {
        fillRejected += tail to maxPacketSize
    }

    override fun onFrames(opcode: Int, frames: List<PtpDiagFrame>) {
        this.frames += FramesEvent(opcode, frames)
    }

    override fun onBulkOut(
        frameIndex: Int,
        writeSequence: Int,
        offset: Int,
        length: Int,
        written: Int,
        isZlp: Boolean,
    ) {
        bulkOut += BulkOutEvent(frameIndex, writeSequence, offset, length, written, isZlp)
    }

    override fun onTransaction(
        opcode: Int,
        transactionId: Long,
        responseCode: Int?,
        dataBytes: Long,
        elapsedMs: Long,
        isFatal: Boolean,
        isPreflight: Boolean,
        exceptionType: String?,
        exceptionMessage: String?,
    ) {
        transactions += TxEvent(
            opcode,
            transactionId,
            responseCode,
            dataBytes,
            elapsedMs,
            isFatal,
            isPreflight,
            exceptionType,
            exceptionMessage,
        )
    }
}
