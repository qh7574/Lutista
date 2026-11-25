package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.PreflightOutcomes
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpProtocolException
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * QA 独立复核（T01）：传输层的**线程契约**与**未就绪状态下的行为**。
 *
 * 相机不在线，所以这里刻意只覆盖「不需要 USB 就能证明」的部分：
 * - 约束 7（所有 USB I/O 必须在 `ptp-io` 线程）：未 bind 时 debug 必须抛
 *   [IllegalStateException]，且**不能**被当成「相机拒绝」处理；
 * - 未 `open()` 时 `transaction` 必须抛 [PtpException]，**不能**误报成超时（超时会置 fatal）；
 * - `close()` 幂等、`drainEvents()` 在未 open 时返回空；
 * - 预检结果分类的三个出口（TIMEOUT / REJECTED / PARSE_ERROR）覆盖所有响应码。
 *
 * 真正的 `bulkTransfer` 路径需要真机，见复核报告 B 组的静态证据。
 */
class QaTransportContractTest {

    @Before
    fun setUp() {
        UsbIoThread.resetForTest()
    }

    @After
    fun tearDown() {
        // 全局单例，必须还原，否则会污染其它测试类
        UsbIoThread.resetForTest()
    }

    // ─────────────── ① 线程断言（约束 7） ───────────────

    @Test
    fun `未 bind 时 requireIoThread 抛 IllegalStateException 且计数加一`() {
        assertFalse(UsbIoThread.isBound())
        assertFalse(UsbIoThread.isIoThread())
        try {
            UsbIoThread.requireIoThread("Qa 复核")
            fail("未绑定 IO 线程时必须抛 IllegalStateException（debug 变体）")
        } catch (expected: IllegalStateException) {
            assertTrue("异常信息必须带线程名：${expected.message}", expected.message!!.contains("ptp-io"))
            assertTrue("异常信息必须说明未绑定：${expected.message}", expected.message!!.contains("未绑定"))
        }
        assertEquals(1, UsbIoThread.violationCount())
    }

    @Test
    fun `bind 之后同线程放行 其它线程仍被拦下`() {
        UsbIoThread.bind()
        assertTrue(UsbIoThread.isIoThread())
        // 同线程不抛
        UsbIoThread.requireIoThread("Qa 复核")
        assertEquals(0, UsbIoThread.violationCount())

        var captured: Throwable? = null
        val other = Thread({
            try {
                UsbIoThread.requireIoThread("Qa 复核")
            } catch (t: Throwable) {
                captured = t
            }
        }, "not-ptp-io")
        other.start()
        other.join()

        assertTrue("其它线程必须被拦下，实际 $captured", captured is IllegalStateException)
        assertEquals(1, UsbIoThread.violationCount())
    }

    @Test
    fun `unbind 之后重新变回非 IO 线程`() {
        UsbIoThread.bind()
        assertTrue(UsbIoThread.isBound())
        UsbIoThread.unbind()
        assertFalse(UsbIoThread.isBound())
        assertFalse(UsbIoThread.isIoThread())
    }

    // ─────────────── ② 未 open 时的行为 ───────────────

    @Test
    fun `未 open 时 transaction 抛 PtpException 而不是超时`() = runBlocking {
        UsbIoThread.bind()
        val transport = PtpUsbTransport()
        assertFalse(transport.isOpen)
        assertFalse(transport.isFatal)
        assertNull(transport.deviceCaps)

        try {
            transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO))
            fail("未 open 必须抛 PtpException")
        } catch (wrong: PtpTimeoutException) {
            fail("未 open 不能误报成超时（超时会置 fatal 并逼用户拔插）：${wrong.message}")
        } catch (expected: PtpException) {
            assertTrue("信息应指明未 open：${expected.message}", expected.message!!.contains("未 open"))
        }
        // 未 open 不是「通道坏了」，不能在 fatal 上留痕
        assertFalse(transport.isFatal)
    }

    @Test
    fun `非 IO 线程调用 transaction 直接抛 IllegalStateException`() = runBlocking {
        val transport = PtpUsbTransport()
        try {
            transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO))
            fail("非 IO 线程必须被拦下")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("ptp-io"))
        }
        assertFalse(transport.isFatal)
    }

    @Test
    fun `未 open 时 close 幂等 且 drainEvents 返回空`() = runBlocking {
        UsbIoThread.bind()
        val transport = PtpUsbTransport()
        transport.close()
        transport.close()
        assertTrue(transport.drainEvents().isEmpty())
        assertTrue(transport.collectPendingEvents().isEmpty())
        assertFalse(transport.isOpen)
        assertFalse(transport.isFatal)
    }

    @Test
    fun `close 之后 isOpen 为 false 且再 close 不抛`() = runBlocking {
        UsbIoThread.bind()
        val transport = PtpUsbTransport()
        transport.close(timeoutMs = 1)
        assertFalse(transport.isOpen)
        transport.close()
    }

    // ─────────────── ③ 预检三出口（定稿 §6 约束 8 的例外） ───────────────

    @Test
    fun `预检分类 - 除 0x2001 外所有响应码都归 REJECTED`() {
        val codes = listOf(
            0x2002, 0x2003, 0x2005, 0x2006, 0x2008, 0x2009, 0x2013, 0x2014, 0x201D, 0x201E,
            0xA001, 0xC108,
        )
        for (code in codes) {
            assertEquals(
                "rc=0x${code.toString(16)} 应归 REJECTED",
                PreflightFailure.REJECTED,
                PreflightOutcomes.resolve(code, null),
            )
        }
        assertNull("0x2001 是成功", PreflightOutcomes.resolve(PtpConstants.Rc.OK, null))
    }

    @Test
    fun `预检分类 - 异常优先且超时与解析错误必须区分`() {
        assertEquals(PreflightFailure.TIMEOUT, PreflightOutcomes.resolve(null, PtpTimeoutException("bulk -1")))
        assertEquals(PreflightFailure.PARSE_ERROR, PreflightOutcomes.resolve(null, PtpProtocolException("容器长度非法")))
        assertEquals(
            PreflightFailure.PARSE_ERROR,
            PreflightOutcomes.resolve(null, cn.alittlecookie.lutista.tether.protocol.PtpParseException("未对齐")),
        )
        // 预检期抛出任何异常都不可以是 null（否则会被当成「预检成功」）
        assertNull(PreflightOutcomes.resolve(null, null))
    }

    @Test
    fun `PtpProtocolException 与 PtpTimeoutException 都派生自 PtpException`() {
        // 语义分层：捕获顺序写反就会把「超时置 fatal」的逻辑吞掉
        val timeout: PtpException = PtpTimeoutException("x")
        val protocol: PtpException = PtpProtocolException("x")
        assertTrue(timeout is PtpTimeoutException)
        assertTrue(protocol is PtpProtocolException)
        assertFalse(PtpException("x") is PtpTimeoutException)
    }
}
