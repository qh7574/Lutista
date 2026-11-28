package cn.alittlecookie.lutista.tether

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * 第五轮：**通道复位梯子**的可执行回归。
 *
 * 真机事故链（`logs_1795787131379` → `logs_1795791570292`）：
 * 1. 21:43–21:45 连续 5 次连不上：`claimInterface(0)` 成功、两条 `CLEAR_FEATURE(ENDPOINT_HALT)`
 *    都被相机接受，但 bulk OUT 在 4 ms 内返回 -1、bulk IN 永不回应 —— 相机停在
 *    「上一次会话的数据段未完成」。当时的恢复代码（`open()` 里 `catch (PtpException) { resyncChannel() }`）
 *    是**死代码**：`probeDeviceInfo()` 内部吞掉异常并改写 `preflightFailure`，catch 永远进不去。
 * 2. 22:58 / 23:05 快速滑动相册：8 MiB 级整段下载在数据段里被取消 → 只清 halt + 排空
 *    256 KiB 不够 → 相机继续拒收命令（OUT 立即 -1）→ fatal，只能拔电池。
 *
 * 本文件锁住的契约：
 * 1. 预检失败 → 正好复位一次 → 重试一次（成功则连接可用）；
 * 2. 复位也救不回来 → 降级为 `preflightFailure`，**不置 fatal**，且恢复预算有界；
 * 3. 事务中 OUT 首包（offset=0）返回 -1 → 清 halt 后原样重写一次，命令字节不重复；
 * 4. 短写循环中间失败（offset>0）→ **不**重试（重写会重复字节、错位容器边界）；
 * 5. 数据段中途取消（Coil 滑走 / 全屏预览返回）→ **绝不半路撒手**：剩余 payload 照读、响应照收，
 *    事务闭合之后再把取消原样抛回；**不发 Cancel、不复位**，会话保持可用。真机
 *    logs_1795830209506 09:41:50 证明半路撒手的后果是相机自己掉下 USB（DETACHED）；
 * 6. 同 5：读满数据段**声明长度**再收响应（libgphoto2 `ptp_usb_getdata` 语义），
 *    失败事务的收尾才走 `Cancel(0x64)` + 按声明长度精确排空；
 * 7. `open()` 先排空上一次会话的残留数据段再预检 —— 真机 `connectionMs=2027` 的来源就是
 *    「残留在、预检必然吃满 1500 ms 超时」。
 *
 * 相机模型是**按 opcode 动态应答**的：写出的命令容器一旦完整，就按 opcode 入队数据段与响应。
 * 控制传输（GET_STATUS / CLEAR_FEATURE / SET_INTERFACE）用**自建记录器** `FakeCamera.controls`
 * 断言 —— 不依赖 mockk 的调用记录（relaxed mock 的记录不可靠，且会把整块 ByteArray 打进报告，
 * 曾让 XML 膨胀到 3.1 MB）。
 *
 * 防挂死：注入单调假时钟（每次 +1），任何意外循环都会撞 deadline 抛超时；
 * IN 队列取空返回 -1 而不是 0，不会出现「永远读到 0 长包」的自旋。
 */
class TransportRecoveryLadderTest {

    private var clock: Long = 1_000L

    /** 捕获 `Timber` 日志：真机就是靠这些行定性的，测试里同样按日志行断言。 */
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        UsbIoThread.resetForTest()
        UsbIoThread.bind()
        clock = 1_000L
        logs.clear()
        Timber.plant(
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    logs += message
                }
            },
        )
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        UsbIoThread.resetForTest()
    }

    // ──────────────── 用例 1：完全沉默时只清 halt，不升级复位 ────────────────

    @Test
    fun `预检完全沉默时跳过复位升级并标记 cameraSilent`() = runBlocking {
        val camera = FakeCamera()
        // 两条命令（预检 + 重试预检）各写两遍（首写 + 清 halt 后重写）全部 -1：
        // 相机一个字节都没回，等价于真机 logs_1795824713270 的「相机端 PTP 引擎卡死」形态。
        camera.outFailures = 4
        val h = harness(camera)

        val caps = h.transport.open(h.device, h.conn)

        assertEquals("零回包时预检只能报 TIMEOUT", PreflightFailure.TIMEOUT, caps.preflightFailure)
        assertTrue(
            "必须标记 cameraSilent，会话层据此让用户关掉相机电源（拔数据线无效）",
            h.transport.cameraSilent,
        )
        assertTrue("预检失败不等于整条连接判死", h.transport.isOpen)
        assertFalse("预检失败绝不能置 fatal", h.transport.isFatal)
        assertEquals(
            "完全沉默时不得升级复位（真机 6 次尝试证明复位从不救活它，只烧掉 5 s 预算）：" +
                "setInterface=${camera.setInterfaceCalls}",
            0,
            camera.setInterfaceCalls.size,
        )
        assertFalse("完全沉默时不得写 PTP DeviceReset(0x1003)", camera.sawDeviceReset)
        assertTrue("必须留下「跳过复位升级」日志（真机定位用）", logs.any { it.contains("跳过复位升级") })
        assertTrue("必须留下 cameraSilent 判定日志", logs.any { it.contains("判定相机无响应") })
        assertEquals("预检 + 重试预检各两遍 bulk OUT", 4, camera.outWrites)
    }

    // ────────────────────────────── 用例 2：复位无效时降级 ──────────────────────────────

    @Test
    fun `通道复位也失败时降级为 preflightFailure 且不置 fatal`() = runBlocking {
        val camera = FakeCamera()
        camera.outFailures = Int.MAX_VALUE // 相机彻底不接受任何 OUT 写
        val h = harness(camera)

        val caps = h.transport.open(h.device, h.conn)

        assertNotNull("连命令都写不进去时必须把失败带给 session 层", caps.preflightFailure)
        assertTrue("预检失败不等于整条连接判死（拔插后还要能重试）", h.transport.isOpen)
        assertFalse("预检失败绝不能置 fatal", h.transport.isFatal)
        // 恢复预算必须有界：控制传输不能无限打
        assertTrue("恢复梯子必须是有界的，不能无限打控制传输", camera.controls.size <= 24)
    }

    // ────────────────────────────── 用例 3：OUT 首包 STALL 自愈 ──────────────────────────────

    @Test
    fun `事务中 OUT 首包返回 -1 会清 halt 后重试一次且不重复字节`() = runBlocking {
        val camera = FakeCamera()
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()
        // 真机故障形态：下一个 OUT 写立刻 -1（端点停在 halt）
        camera.outFailures = 1

        val response = h.transport.transaction(
            op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
            expectedData = false,
            dataOut = null,
            dataSink = null,
            timeoutMs = 2_000,
        )

        assertEquals(PtpConstants.Rc.OK, response.code)
        // OPEN_SESSION 带 1 个参数：12 字节头 + 4 字节参数 = 16。
        // 失败那次一个字节都没写出去，重试不能造成重复。
        assertEquals("命令容器必须正好一份（16 字节）", 16, camera.out.size())
        // 清 halt 只针对 OUT 端点，且只清一次
        assertEquals(
            "只允许清一次 OUT 端点的 halt",
            1,
            camera.controls.count { it.type == 0x02 && it.request == 0x01 && it.index == 0x01 },
        )
    }

    // ────────────────────────────── 用例 4：短写中间失败不重试 ──────────────────────────────

    @Test
    fun `短写循环中间失败不会重试以免容器边界错位`() = runBlocking {
        val camera = FakeCamera()
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()
        // 先只写出去 6 字节（返回正数 → 不是 STALL，不能清 halt 重试），
        // 续写时彻底失败（offset>0 → 绝不允许重写）
        camera.shortWrite = 6
        camera.failWhenOffsetNonZero = true

        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
                expectedData = false,
                dataOut = null,
                dataSink = null,
                timeoutMs = 2_000,
            )
            fail("短写无法完成时必须抛异常，否则容器边界会错位")
        } catch (_: PtpTimeoutException) {
            assertEquals("只允许已经写出去的那 6 字节落盘", 6, camera.out.size())
            assertEquals(
                "短写中间失败不能清 halt 重试（会重复字节）",
                0,
                camera.controls.count { it.type == 0x02 && it.request == 0x01 },
            )
        }
    }

    // ────────────── 用例 5：取消不许半路撒手（真机 09:41:50 的 USB 掉线） ──────────────

    @Test
    fun `数据段中途取消会把剩余数据读完再原样抛回且不发 Cancel`() = runBlocking {
        val camera = FakeCamera()
        // GetObject 回一个完整的 DATA 容器；sink 在第一块就抛取消（等价于全屏预览按返回）。
        camera.dataPayloadBytes = 4_096
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()

        var chunks = 0
        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(0x10001L)),
                expectedData = true,
                dataOut = null,
                dataSink = { _, _ ->
                    chunks++
                    throw CancellationException("全屏预览被关闭")
                },
                timeoutMs = 2_000,
            )
            fail("取消必须原样抛回调用方，不能被吞掉")
        } catch (_: CancellationException) {
            assertEquals("下游只应该被喂到第一块（之后只读不喂）", 1, chunks)
            assertFalse("取消不是通道故障，绝不能判死会话", h.transport.isFatal)
            assertTrue("会话必须仍然可用（不需要拔插）", h.transport.isOpen)
            // 关键契约：事务已经闭合 → 不许再发 Cancel、不许清 halt、不许复位。
            // 真机 09:41:50 就是「半路撒手 + 立刻 Cancel/复位」把相机弄掉线的。
            assertEquals(
                "事务已闭合时绝不能发 Cancel(0x64)",
                0,
                camera.controls.count { it.type == 0x21 && it.request == 0x64 },
            )
            assertEquals(
                "事务已闭合时不需要清 IN halt",
                0,
                camera.controls.count { it.type == 0x02 && it.request == 0x01 && it.index == 0x81 },
            )
            assertEquals(
                "事务已闭合时不需要清 OUT halt",
                0,
                camera.controls.count { it.type == 0x02 && it.request == 0x01 && it.index == 0x01 },
            )
            assertTrue("数据段与响应必须都已经被读干净", camera.inQueue.isEmpty())
            assertFalse("响应已闭合时绝不能复位相机", camera.sawDeviceReset)
            assertTrue("响应已闭合时不需要 SET_INTERFACE", camera.setInterfaceCalls.isEmpty())
        }

        // 取消之后通道必须还能用：紧接着的事务要正常拿到响应。
        camera.resetOut()
        camera.resetControls()
        val next = h.transport.transaction(
            op = PtpOp(PtpConstants.Op.GET_OBJECT_INFO, longArrayOf(0x10001L)),
            expectedData = true,
            dataOut = null,
            dataSink = { _, _ -> },
            timeoutMs = 2_000,
        )
        assertEquals("取消之后下一条事务必须仍然成功", PtpConstants.Rc.OK, next.code)
    }

    // ───────── 用例 6：取消也要读满声明长度（300 KB 跨多次 64 KiB 读） ─────────

    @Test
    fun `数据段中途取消也按声明长度读满再抛而不动用恢复梯子`() = runBlocking {
        val camera = FakeCamera()
        // 300 000 字节 > 单次 bulk IN 上限（64 KiB），必须多读几次才读完。
        camera.dataPayloadBytes = 300_000
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()

        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(0x10001L)),
                expectedData = true,
                dataOut = null,
                dataSink = { _, _ -> throw CancellationException("全屏预览被关闭") },
                timeoutMs = 30_000,
            )
            fail("取消必须原样抛回调用方")
        } catch (_: CancellationException) {
            assertFalse(h.transport.isFatal)
            assertTrue(h.transport.isOpen)
            assertEquals(
                "读满数据段后响应自然到手，不需要 Cancel",
                0,
                camera.controls.count { it.type == 0x21 && it.request == 0x64 },
            )
            assertFalse("精确读完数据段后不需要复位", camera.sawDeviceReset)
            assertTrue("响应容器必须已被消费干净", camera.inQueue.isEmpty())
        }
    }

    // ───── 用例 6c：刚被取消的大数据段之后，OUT 允许 300/600/900 ms 递进退避 ─────

    @Test
    fun `取消大数据段之后的命令写入失败会退避重试而不是立刻判死`() = runBlocking {
        val camera = FakeCamera()
        camera.dataPayloadBytes = 4_096
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()

        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(0x10001L)),
                expectedData = true,
                dataOut = null,
                dataSink = { _, _ -> throw CancellationException("全屏预览被关闭") },
                timeoutMs = 30_000,
            )
            fail("取消必须原样抛回调用方")
        } catch (_: CancellationException) {
            assertFalse(h.transport.isFatal)
        }

        // 真机 logs_1795841499743 12:51:22：取消排空 9 MB 之后，参数轮询的 24 字节命令
        // 连续两次被 NAK，只重试一次就判 fatal。这里让前两次写都失败，第三次必须成功。
        camera.resetOut()
        camera.resetControls()
        camera.outFailures = 2
        val baseline = camera.outTimeouts.size
        val next = h.transport.transaction(
            op = PtpOp(PtpConstants.Op.GET_OBJECT_INFO, longArrayOf(0x10001L)),
            expectedData = true,
            dataOut = null,
            dataSink = { _, _ -> },
            timeoutMs = 2_000,
        )
        assertEquals("退避重试必须把命令救回来", PtpConstants.Rc.OK, next.code)
        assertFalse("救回来之后绝不能判死会话", h.transport.isFatal)
        assertTrue(h.transport.isOpen)
        val timeouts = camera.outTimeouts.drop(baseline)
        assertEquals("应当是「首次写 + 两次退避重试」共三次：$timeouts", 3, timeouts.size)
        assertTrue(
            "首次写仍用事务原超时（只被 deadline 余量削掉几毫秒）：${timeouts[0]}",
            timeouts[0] in 1_500..2_000,
        )
        assertEquals("第一次重试用 300 ms", 300, timeouts[1])
        assertEquals("第二次重试递进到 600 ms", 600, timeouts[2])
        assertFalse("救回来的事务不需要设备级复位", camera.sawDeviceReset)
        assertTrue(camera.setInterfaceCalls.isEmpty())
    }

    // ───── 用例 6b：下游抛非取消异常时立即中断（不读垃圾、也不动通道） ─────

    @Test
    fun `下游抛非取消异常时立即中断事务`() = runBlocking {
        val camera = FakeCamera()
        camera.dataPayloadBytes = 200_000
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()

        val failure = IllegalStateException("写盘失败")
        val thrown = try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(0x10001L)),
                expectedData = true,
                dataOut = null,
                dataSink = { _, _ -> throw failure },
                timeoutMs = 30_000,
            )
            null
        } catch (copy: Throwable) {
            copy
        }
        assertSame("下游异常必须原样抛出", failure, thrown)
        assertFalse("下游异常不等于通道故障", h.transport.isFatal)
        assertTrue(h.transport.isOpen)
        assertFalse("非取消异常立即中断，剩余数据段不读（长度已不可信）", camera.inQueue.isEmpty())
        assertEquals(
            "非取消异常不做通道复位",
            0,
            camera.controls.count { it.type == 0x21 && it.request == 0x64 },
        )
    }

    // ───────────── 用例 7：零回包时事务层自收尾、open 层不再升级 ─────────────

    @Test
    fun `预检超时且零回包时事务层收尾一次而 open 层不再升级复位`() = runBlocking {
        val camera = FakeCamera()
        // 真机 logs_1795791570292 / logs_1795824713270：上一次会话残留的数据段把相机钉住，
        // 第一条 GetDeviceInfo 被静默吞掉（rx=0 dataBytes=0，吃满 1500 ms 超时）。
        camera.busyFirstCommand = true
        val h = harness(camera)

        val caps = h.transport.open(h.device, h.conn)

        assertNull("清 halt + 重试之后预检必须成功", caps.preflightFailure)
        assertTrue(caps.operationsSupported.isNotEmpty())
        assertFalse("相机最终回了包，不能标记为完全沉默", h.transport.cameraSilent)
        // 事务层的失败收尾（`transactionInternal` 的 catch）已经做过一次完整复位：
        // 它知道事务号，所以必须让相机丢掉那条事务（类请求 Cancel 0x64）。
        assertEquals(
            "失败事务收尾必须把事务号交给相机（Cancel 恰好一次）",
            1,
            camera.controls.count { it.type == 0x21 && it.request == 0x64 },
        )
        assertTrue("事务层收尾走到 PTP DeviceReset 这一档", camera.sawDeviceReset)
        // open 层看到「零回包」就不再升级第二遍复位（真机证明那是纯烧预算）：
        // 只清 halt 后重试一次预检，因此日志里没有第二遍「通道复位（预检失败=…）」。
        assertTrue(
            "open 层必须留下「跳过复位升级」日志；logs=$logs",
            logs.any { it.contains("跳过复位升级") },
        )
        assertEquals(
            "open 层不得再跑一遍预检复位（只有事务层那一遍）",
            0,
            logs.count { it.contains("通道复位（预检失败=") },
        )
        // 预检命令 + 事务层收尾的 PTP DeviceReset + 重试预检 = 3 次命令写。
        assertEquals("预检 + 事务层 DeviceReset + 重试", 3, camera.outWrites)
    }

    // ────────────────────── 用例 8：收不干净必须判死 ──────────────────────

    @Test
    fun `收尾收不干净时必须判死会话`() = runBlocking {
        val camera = FakeCamera()
        // GetObject 声明 5000 字节数据段；下面让相机在 1012 字节后彻底沉默。
        camera.dataPayloadBytes = 5_000
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()
        // 12 字节容器头 + 1000 字节 payload 之后相机不再发任何东西：
        // 数据段欠 4000 字节、响应也没有 —— 通道状态**不可证明**，不能宣称可用。
        camera.inSilentAfterBytes = 1_012

        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(0x10001L)),
                expectedData = true,
                dataOut = null,
                dataSink = { _, _ -> },
                timeoutMs = 2_000,
            )
            fail("相机中途沉默必须抛超时")
        } catch (_: PtpTimeoutException) {
            assertTrue("排空读不满、响应也没读到 → 必须判死，不能骗上层说通道干净", h.transport.isFatal)
            assertTrue(
                "日志必须留下收尾判据（drainComplete=false），真机靠它定性；logs=${logs}",
                logs.any { it.contains("drainComplete=false") },
            )
        }
    }

    // ────────────────── 用例 9：读到本事务响应 = 干净闭合 ──────────────────

    @Test
    fun `读到本事务响应就算干净收尾并允许后续事务`() = runBlocking {
        val camera = FakeCamera()
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()
        // 相机其实已经回包，只是比主机超时晚到：类请求 Cancel 一到就吐出来。
        camera.deferResponseUntilCancel = true

        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
                expectedData = false,
                dataOut = null,
                dataSink = null,
                timeoutMs = 500,
            )
            fail("响应没到，事务必须超时")
        } catch (_: PtpTimeoutException) {
            assertFalse("读到本事务的响应容器 = 通道可证明干净，绝不能判死", h.transport.isFatal)
            assertTrue(h.transport.isOpen)
            assertEquals(
                "失败事务收尾必须把事务号交给相机（Cancel 恰好一次）",
                1,
                camera.controls.count { it.type == 0x21 && it.request == 0x64 },
            )
            assertFalse("干净收尾不需要升级到设备级复位", camera.sawDeviceReset)
            assertEquals("干净收尾不需要 SET_INTERFACE", 0, camera.setInterfaceCalls.size)
        }

        // 上一条事务闭合后，会话必须还能正常用（这就是「不再需要拔插」的意义）。
        camera.deferResponseUntilCancel = false
        val response = h.transport.transaction(
            op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
            expectedData = false,
            dataOut = null,
            dataSink = null,
            timeoutMs = 2_000,
        )
        assertEquals(PtpConstants.Rc.OK, response.code)
        assertFalse(h.transport.isFatal)
    }

    // ─────────────── 用例 10：响应事务号不符不算闭合 ───────────────

    @Test
    fun `响应事务号不符不算闭合必须判死`() = runBlocking {
        val camera = FakeCamera()
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()
        camera.deferResponseUntilCancel = true
        // 相机缓冲里躺的是**上一条**事务的响应：结构合法但事务号对不上。
        camera.responseTxidOffset = 5L

        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
                expectedData = false,
                dataOut = null,
                dataSink = null,
                timeoutMs = 500,
            )
            fail("响应没到，事务必须超时")
        } catch (_: PtpTimeoutException) {
            assertTrue("事务号对不上等于「这条事务没闭合」，必须判死", h.transport.isFatal)
            assertTrue("事务号不符只能靠设备级复位救，必须走到那一步", camera.sawDeviceReset)
        }
    }

    // ─────────────── 用例 11：EVENT 不算闭合 ───────────────

    @Test
    fun `收到 EVENT 容器不算事务闭合必须判死`() = runBlocking {
        val camera = FakeCamera()
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()
        camera.deferResponseUntilCancel = true
        // bulk IN 上先来一个事件容器：它属于设备异步通知，不是任何事务的闭合证据。
        camera.respondWithEvent = true

        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(1L)),
                expectedData = false,
                dataOut = null,
                dataSink = null,
                timeoutMs = 500,
            )
            fail("响应没到，事务必须超时")
        } catch (_: PtpTimeoutException) {
            assertTrue("EVENT 不能当响应，通道不可证明 → 必须判死", h.transport.isFatal)
        }
    }

    /**
     * 真机常态：数据段的尾巴与 RESPONSE 落在**同一个** bulk IN 包里。
     *
     * 第十轮起语义变了：取消不再打断数据段，所以「同包响应」直接由正常读取路径消费掉，
     * 既不需要额外的 USB 读，也不会（更不敢）升级到 Cancel / 设备复位。
     * 本用例锁住的就是这一点：一次同包到达的取消，全程零控制请求、零残留。
     */
    @Test
    fun `响应与数据段同包到达时不必再读 USB 就能闭合`() = runBlocking {
        val camera = FakeCamera()
        camera.dataPayloadBytes = 4_096
        camera.coalesceDataAndResponse = true
        val h = harness(camera)
        h.transport.open(h.device, h.conn)
        camera.resetOut()
        camera.resetControls()

        var chunks = 0
        try {
            h.transport.transaction(
                op = PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(0x10001L)),
                expectedData = true,
                dataOut = null,
                dataSink = { _, _ ->
                    chunks++
                    throw CancellationException("Coil 请求被滑走")
                },
                timeoutMs = 2_000,
            )
            fail("取消必须原样抛回调用方")
        } catch (_: CancellationException) {
            assertEquals("sink 只应该被喂到第一块", 1, chunks)
            assertFalse("同包响应足以闭合通道，绝不能判死会话", h.transport.isFatal)
            assertTrue("会话必须仍然可用（不需要拔插）", h.transport.isOpen)
            assertTrue("响应已经被用掉，IN 队列不该再有残留", camera.inQueue.isEmpty())
            // 读满数据段并把同包响应收下就够了：不许再发 Cancel、不许清 halt、不许复位。
            assertEquals(
                "同包响应已闭合事务，不得再发 Cancel(0x64)：${camera.controls}",
                0,
                camera.controls.count { it.type == 0x21 && it.request == 0x64 },
            )
            assertEquals(
                "同包响应已闭合事务，不得再清 halt：${camera.controls}",
                0,
                camera.controls.count { it.type == 0x02 && it.request == 0x01 },
            )
            assertTrue(
                "已闭合就不得升级到类请求 DeviceReset(0x66)：${camera.controls}",
                camera.controls.none { it.type == 0x21 && it.request == 0x66 },
            )
            assertTrue("已闭合就不得 SET_INTERFACE：${camera.setInterfaceCalls}", camera.setInterfaceCalls.isEmpty())
        }
    }

    @Test
    fun `端点本来就是干净的时候不要多发 CLEAR_FEATURE`() = runBlocking {
        val camera = FakeCamera()
        val h = harness(camera)
        try {
            h.transport.open(h.device, h.conn)
            // 设备自报 halt=0 → 清理动作必须跳过：从 userspace 发 CLEAR_FEATURE 只归零设备侧
            // 数据翻转，不会复位手机 host 控制器侧，两端错开后首条命令就会被当成重传丢弃
            // （真机 logs_1795829123428：haltBefore 两端 clear，第一条命令就 -1）。
            assertTrue(
                "端点干净时不得发 CLEAR_FEATURE：${camera.controls}",
                camera.controls.none { it.type == 0x02 && it.request == 0x01 },
            )
            assertTrue(
                "open 日志必须写清清理被跳过，logs=$logs",
                logs.any { it.contains("clearHalt(in=skipped out=skipped)") },
            )
        } finally {
            h.transport.close()
        }
    }

    @Test
    fun `设备自报端点 halt 时仍然清 halt`() = runBlocking {
        val camera = FakeCamera()
        camera.haltedEndpoints += 0x81
        val h = harness(camera)
        try {
            h.transport.open(h.device, h.conn)
            val clears = camera.controls.filter { it.type == 0x02 && it.request == 0x01 }
            assertEquals("只有 IN 端自报 halt，必须只清 IN：$clears", 1, clears.size)
            assertEquals("清理目标必须是 IN 端点 0x81", 0x81, clears.single().index)
        } finally {
            h.transport.close()
        }
    }

    @Test
    fun `预检失败时会换一条 usbfs 通道再试一次`() = runBlocking {
        val camera = FakeCamera()
        camera.outFailures = Int.MAX_VALUE
        var reopens = 0
        val h = harness(camera) {
            reopens++
            // 模拟「关掉 fd 重新 openDevice 之后通道恢复」：新 fd 上命令开始能被相机接受。
            camera.outFailures = 0
            camera.inQueue.clear()
            camera.resetOut()
            mockk<UsbDeviceConnection>(relaxed = true).also { fresh ->
                every { fresh.claimInterface(any(), any()) } returns true
                every { fresh.releaseInterface(any()) } returns true
                every { fresh.rawDescriptors } returns null
                every { fresh.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) } answers {
                    camera.onControl(
                        type = args[0] as Int,
                        request = args[1] as Int,
                        value = args[2] as Int,
                        index = args[3] as Int,
                        length = args[5] as Int,
                        buffer = args[4] as ByteArray?,
                    )
                }
                every { fresh.setInterface(any()) } answers { true }
                every { fresh.bulkTransfer(any(), any(), any(), any(), any()) } answers {
                    val endpoint = args[0] as UsbEndpoint
                    val buffer = args[1] as ByteArray
                    val offset = args[2] as Int
                    val length = args[3] as Int
                    val timeoutMs = args[4] as Int
                    if (endpoint.address == 0x01) camera.onOut(buffer, offset, length, timeoutMs)
                    else camera.onIn(buffer, offset, length)
                }
                every { fresh.bulkTransfer(any(), any(), any(), any()) } answers {
                    val endpoint = args[0] as UsbEndpoint
                    val buffer = args[1] as ByteArray
                    val length = args[2] as Int
                    if (endpoint.address == 0x01) camera.onOut(buffer, 0, length, 0) else camera.onIn(buffer, 0, length)
                }
            }
        }
        try {
            val caps = h.transport.open(h.device, h.conn)
            assertEquals("必须恰好换一次通道", 1, reopens)
            assertEquals("换通道后预检必须成功", null, caps.preflightFailure)
            assertTrue(
                "必须记下换通道这一步，便于真机判读，logs=$logs",
                logs.any { it.contains("USB 重新打开") },
            )
            assertFalse("换通道成功后不得判相机沉默", h.transport.cameraSilent)
        } finally {
            h.transport.close()
        }
    }

    // ────────────────────────────── 测试脚手架 ──────────────────────────────

    private class Harness(
        val transport: PtpUsbTransport,
        val device: UsbDevice,
        val conn: UsbDeviceConnection,
    )

    /** 一条已被相机收到的控制传输（bmRequestType / bRequest / wValue / wIndex / wLength）。 */
    private data class ControlCall(
        val type: Int,
        val request: Int,
        val value: Int,
        val index: Int,
        val length: Int,
    )

    /**
     * 可编排响应的「相机」。
     *
     * - `outFailures`：接下来多少次 OUT 写直接返回 -1（模拟端点 halt）；
     * - `failWhenOffsetNonZero`：短写续写时返回 -1（模拟写出半截后彻底失败）；
     * - `shortWrite`：单次 OUT 最多写出去的字节数（模拟短写）。
     *
     * 写出的命令容器一旦完整，就按 opcode 入队响应（见 [dispatch]）。
     */
    private class FakeCamera {

        val inQueue = ArrayDeque<ByteArray>()
        val out = ByteArrayOutputStream()
        private var parsedUntil: Int = 0
        var outFailures: Int = 0
        /** 每次 bulk OUT 传下去的 timeout（清 halt 重写必须显著短于首写）。 */
        val outTimeouts = mutableListOf<Int>()

        /** 设备自报处于 halt 的端点地址（GET_STATUS 返回的 bit0）。 */
        val haltedEndpoints = mutableSetOf<Int>()
        var shortWrite: Int = Int.MAX_VALUE
        var failWhenOffsetNonZero: Boolean = false
        var outWrites: Int = 0
        var inReads: Int = 0

        /** 控制传输的**自建记录**（不依赖 mockk 的调用记录）。 */
        val controls = mutableListOf<ControlCall>()
        val setInterfaceCalls = mutableListOf<UsbInterface>()

        /** `GET_OBJECT` 回包的数据段长度（0 = 不产生数据段）。 */
        var dataPayloadBytes: Int = 0

        /**
         * 相机「忙」模型：**第一条**命令被静默吞掉（不排队任何响应，IN 一律超时）。
         *
         * 真机 logs_1795791570292 的表现就是这样 —— 上一次会话的数据段没收干净时，
         * `GetDeviceInfo` 预检必然吃满 1500 ms 超时（`rx=0 dataBytes=0`），连接因此慢到 2027 ms。
         * 复位完成后相机恢复响应，重试的预检一次成功。
         */
        var busyFirstCommand: Boolean = false

        /** 是否收到过 `DeviceReset(0x1003)`：复位梯子走到最后一步的直接证据。 */
        var sawDeviceReset: Boolean = false

        /**
         * 响应先扣住，直到收到类请求 **Cancel(0x64)** 才吐出来（模拟「相机其实回包了，只是比主机
         * 的超时晚到」）。这是「干净收尾」的必要条件：只有读到本事务的响应容器才算闭合。
         */
        var deferResponseUntilCancel: Boolean = false
        private val deferred = ArrayDeque<ByteArray>()

        /** 响应容器用错事务号（模拟相机缓冲里残留着上一条事务的响应）。 */
        var responseTxidOffset: Long = 0L

        /** 用 EVENT 容器代替 RESPONSE（主机在 bulk IN 上先收到事件不算事务闭合）。 */
        var respondWithEvent: Boolean = false

        /**
         * 数据段与响应容器**挤在同一个 bulk IN 包**里到达（真机常态：数据段尾巴与 RESPONSE
         * 一起被 64 KiB 读进来）。收尾时必须先把 `rx` 里已到的响应用掉，而不是再从 USB 读。
         */
        var coalesceDataAndResponse: Boolean = false

        /**
         * 相机在 IN 上总共只肯给这么多字节，超出后**彻底沉默**并丢弃剩余队列
         * （模拟数据段发到一半相机侧卡住：后面既没有尾部数据，也没有响应）。
         */
        var inSilentAfterBytes: Int = Int.MAX_VALUE
        private var servedInBytes: Int = 0

        /** 丢弃已写出的字节（用例里只想看**新**事务的 OUT 流量）。 */
        fun resetOut() {
            out.reset()
            parsedUntil = 0
            // IN 侧的「总共肯给多少字节」也重新计时：用例总是从干净的一刻开始编排
            servedInBytes = 0
            inSilentAfterBytes = Int.MAX_VALUE
        }

        /** 丢弃已记录的控制传输（用例里只想看**新**事务的流量）。 */
        fun resetControls() {
            controls.clear()
            setInterfaceCalls.clear()
        }

        /**
         * 记录一条控制传输并按 USB 语义返回。
         *
         * 只有 IN 方向的 GET_STATUS 需要 2 字节状态（halt 位），其余返回 0（成功）。
         */
        fun onControl(type: Int, request: Int, value: Int, index: Int, length: Int, buffer: ByteArray? = null): Int {
            controls += ControlCall(type, request, value, index, length)
            // GET_STATUS(endpoint)：把「设备自报的 halt 位」写进缓冲。默认 0（干净）——
            // 真机 logs_1795829123428 就是两端都 clear 却写不进，此时不该再去清 halt。
            if (type == 0x82 && request == 0x00 && buffer != null) {
                buffer[0] = if (index in haltedEndpoints) 0x01 else 0x00
                if (buffer.size > 1) buffer[1] = 0
            }
            // 类请求 Cancel(0x64)：相机按 libgphoto2 语义丢掉这条事务，并把迟到的响应放出来。
            if (type == 0x21 && request == 0x64) flushDeferred()
            return if (type and 0x80 != 0) 2 else 0
        }

        private fun flushDeferred() {
            while (deferred.isNotEmpty()) inQueue.addLast(deferred.removeFirst())
        }

        fun onOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            outWrites++
            outTimeouts += timeoutMs
            if (outFailures > 0) {
                outFailures--
                return -1
            }
            if (length == 0) return 0
            if (failWhenOffsetNonZero && offset > 0) return -1
            val n = minOf(length, shortWrite)
            out.write(buffer, offset, n)
            if (n == shortWrite) shortWrite = Int.MAX_VALUE
            dispatch()
            return n
        }

        fun onIn(buffer: ByteArray, offset: Int, length: Int): Int {
            inReads++
            // 相机在数据段中途彻底沉默（inSilentAfterBytes）：剩余队列直接丢弃，
            // 之后永远返回 -1 —— 这样的通道**不可证明干净**，收尾必须判死。
            if (servedInBytes >= inSilentAfterBytes) {
                inQueue.clear()
                return -1
            }
            val head = inQueue.firstOrNull() ?: return -1
            val budget = inSilentAfterBytes - servedInBytes
            val n = minOf(head.size, length, budget)
            System.arraycopy(head, 0, buffer, offset, n)
            servedInBytes += n
            if (n == head.size) inQueue.removeFirst() else inQueue[0] = head.copyOfRange(n, head.size)
            return n
        }

        /** 把已经完整写出的命令容器当作「相机收到命令」，并按 PTP 语义回应。 */
        private fun dispatch() {
            val bytes = out.toByteArray()
            var index = parsedUntil
            while (index + CONTAINER_HEADER <= bytes.size) {
                val length = readInt(bytes, index)
                if (length < CONTAINER_HEADER || index + length > bytes.size) break
                val type = readShort(bytes, index + 4)
                val code = readShort(bytes, index + 6)
                val txid = readInt(bytes, index + 8).toLong() and 0xFFFFFFFFL
                if (type == CONTAINER_TYPE_COMMAND) respond(code, txid)
                index += length
            }
            parsedUntil = index
        }

        private fun respond(code: Int, txid: Long) {
            // 相机忙：第一条命令被静默吞掉（真机行为，见 busyFirstCommand）。
            if (busyFirstCommand) {
                busyFirstCommand = false
                return
            }
            // DeviceReset 按 PTP 语义**没有响应容器**，并且会丢弃之前挂起的全部容器。
            if (code == DEVICE_RESET_OPCODE) {
                sawDeviceReset = true
                inQueue.clear()
                return
            }
            val queue = if (deferResponseUntilCancel) deferred else inQueue
            // GetDeviceInfo 也会让相机丢弃之前挂起的数据段
            if (code == PtpConstants.Op.GET_DEVICE_INFO) {
                inQueue.clear()
                queue.addLast(
                    PtpContainerCodec.encodeData(code, txid, Fixtures.hex("device_info")),
                )
            }
            if (code == PtpConstants.Op.GET_OBJECT && dataPayloadBytes > 0) {
                val data = PtpContainerCodec.encodeData(code, txid, ByteArray(dataPayloadBytes))
                if (coalesceDataAndResponse) {
                    // 数据段与响应挤在同一个包：这里把两者拼成**一个** chunk 入队，
                    // 主机的第一次 64 KiB 读就会同时拿到数据段尾巴和响应容器。
                    // （onIn 会按请求长度截断并保留剩余字节，所以两种到达方式都能模拟。）
                    queue.addLast(
                        data + PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, txid),
                    )
                    return
                }
                queue.addLast(data)
            }
            queue.addLast(
                if (respondWithEvent) {
                    // 事件容器：type=EVENT，不属于任何事务的闭合证据。
                    PtpContainerCodec.encodeEvent(EVENT_CODE, transactionId = txid + responseTxidOffset)
                } else {
                    PtpContainerCodec.encodeResponse(
                        PtpConstants.Rc.OK,
                        txid + responseTxidOffset,
                    )
                },
            )
        }

        private fun readInt(bytes: ByteArray, index: Int): Int =
            (bytes[index].toInt() and 0xFF) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                ((bytes[index + 2].toInt() and 0xFF) shl 16) or
                ((bytes[index + 3].toInt() and 0xFF) shl 24)

        private fun readShort(bytes: ByteArray, index: Int): Int =
            (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
    }

    private fun harness(
        camera: FakeCamera,
        reopen: (() -> UsbDeviceConnection?)? = null,
    ): Harness {
        val inEp = mockk<UsbEndpoint>()
        every { inEp.address } returns 0x81
        every { inEp.type } returns 2
        every { inEp.maxPacketSize } returns 512

        val outEp = mockk<UsbEndpoint>()
        every { outEp.address } returns 0x01
        every { outEp.type } returns 2
        every { outEp.maxPacketSize } returns 512

        val intrEp = mockk<UsbEndpoint>()
        every { intrEp.address } returns 0x82
        every { intrEp.type } returns 3
        every { intrEp.maxPacketSize } returns 64

        val intf = mockk<UsbInterface>()
        every { intf.endpointCount } returns 3
        every { intf.getEndpoint(0) } returns inEp
        every { intf.getEndpoint(1) } returns outEp
        every { intf.getEndpoint(2) } returns intrEp
        every { intf.alternateSetting } returns 0
        every { intf.id } returns 0

        val device = mockk<UsbDevice>()
        every { device.interfaceCount } returns 1
        every { device.getInterface(0) } returns intf
        every { device.vendorId } returns 0x04DA
        every { device.productId } returns 0x2382

        val conn = mockk<UsbDeviceConnection>(relaxed = true)
        every { conn.claimInterface(any(), any()) } returns true
        every { conn.releaseInterface(any()) } returns true
        every { conn.rawDescriptors } returns null
        // 控制传输：只显式编排「带缓冲」的 7 参重载（GET_STATUS / CLEAR_FEATURE 都走它），
        // 由 FakeCamera 自己记录并返回状态码；null 缓冲的 7 参重载（DeviceReset 类请求）
        // 由 relaxed 返回 0。断言一律读 FakeCamera.controls，不读 mockk 的调用记录。
        every { conn.controlTransfer(any(), any(), any(), any(), any<ByteArray>(), any(), any()) } answers {
            camera.onControl(
                type = args[0] as Int,
                request = args[1] as Int,
                value = args[2] as Int,
                index = args[3] as Int,
                length = args[5] as Int,
                buffer = args[4] as ByteArray?,
            )
        }
        every { conn.setInterface(any()) } answers {
            camera.setInterfaceCalls += args[0] as UsbInterface
            true
        }
        every { conn.bulkTransfer(any(), any(), any(), any(), any()) } answers {
            val endpoint = args[0] as UsbEndpoint
            val buffer = args[1] as ByteArray
            val offset = args[2] as Int
            val length = args[3] as Int
            val timeoutMs = args[4] as Int
            if (endpoint.address == 0x01) camera.onOut(buffer, offset, length, timeoutMs) else camera.onIn(buffer, offset, length)
        }
        every { conn.bulkTransfer(any(), any(), any(), any()) } answers {
            val endpoint = args[0] as UsbEndpoint
            val buffer = args[1] as ByteArray
            val length = args[2] as Int
            if (endpoint.address == 0x01) camera.onOut(buffer, 0, length, 0) else camera.onIn(buffer, 0, length)
        }

        return Harness(
            PtpUsbTransport(
                rxCapacity = 128 * 1024,
                now = { clock += 1; clock },
                reopenConnection = reopen,
            ),
            device,
            conn,
        )
    }

    @Test
    fun `清理 halt 后的重写使用短超时以免烧光连接预算`() = runBlocking {
        val camera = FakeCamera()
        camera.outFailures = 1
        val h = harness(camera)
        try {
            h.transport.open(h.device, h.conn)

            // 预检命令：首写用事务超时，清 halt 后的重写必须显著更短
            // （真机 logs_1795826645352 每轮 1.5 s + 1.5 s 直接把 5 s 预算吃光）。
            assertTrue("必须至少记录两次 OUT 超时，实际=" + camera.outTimeouts, camera.outTimeouts.size >= 2)
            val first = camera.outTimeouts[0]
            val retry = camera.outTimeouts[1]
            assertTrue("首写超时应为事务级（>=800ms），实际=$first", first >= 800)
            assertTrue("清 halt 重写必须是短超时（<=300ms），实际=$retry", retry <= 300)
            assertTrue("重写超时必须远小于首写，实际 first=$first retry=$retry", retry < first)
        } finally {
            h.transport.close()
        }
    }

    @Test
    fun `open 在清 halt 之前记录端点 halt 位`() = runBlocking {
        val camera = FakeCamera()
        val h = harness(camera)
        try {
            h.transport.open(h.device, h.conn)
            // 只记 clearHalt(in=true out=true) 无法区分「相机自己停住端点」和「端点本来就干净」，
            // 真机 logs_1795826645352 缺这一位导致无法判断相机侧还是手机 host 侧故障。
            assertTrue(
                "open 日志必须包含清 halt 之前的端点状态，logs=" + logs,
                logs.any { it.contains("haltBefore(") },
            )
        } finally {
            h.transport.close()
        }
    }

    private companion object {
        const val CONTAINER_HEADER = 12
        const val CONTAINER_TYPE_COMMAND = 1
        const val DEVICE_RESET_OPCODE = 0x1003

        /** 任意设备事件码（`PTP_EC_DeviceInfoChanged` 邻域）：只用来验证「EVENT 不算闭合」。 */
        const val EVENT_CODE = 0x4002
    }
}
