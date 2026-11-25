package cn.alittlecookie.lutista.tether

import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import cn.alittlecookie.lutista.tether.protocol.CapabilityRules
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapabilityRules] 的 7 个纯函数真值表。
 *
 * 期望值全部取自 `.docs/联机模式-实现方案-v8-增量.md` §1.2 / §1.3 与
 * `.docs/联机模式-PTP结论-定稿-2026-11-25.md` §2 的实测数据。
 */
class CapabilityRulesTest {

    // ─────────────────── ① fromOps：只取正向，未命中返回 null ───────────────────

    @Test
    fun `fromOps - 命中返回 Supported 加 STATIC_OPS`() {
        val ops = setOf(0x1001L, 0x1007L, 0x1009L)
        val cap = CapabilityRules.fromOps(0x1007L, ops)
        assertEquals(Availability.Supported, cap!!.availability)
        assertEquals(CapabilityEvidence.STATIC_OPS, cap.evidence)
        assertEquals("0x1007", cap.detail)
    }

    @Test
    fun `fromOps - 未命中必须返回 null 而不是 Unsupported（修正 ① 的守卫）`() {
        // S1 声明的 19 条里一条私有码都没有，而 0x9404/0x9412/0x9706 全部实测可用
        val s1Ops = setOf(
            0x1001L, 0x1002L, 0x1003L, 0x1004L, 0x1005L, 0x1006L, 0x1007L, 0x1008L, 0x1009L,
            0x100AL, 0x100BL, 0x100FL, 0x1014L, 0x1015L, 0x1016L, 0x101BL, 0x9102L, 0x9103L, 0x9104L,
        )
        assertNull(CapabilityRules.fromOps(0x9404L, s1Ops))
        assertNull(CapabilityRules.fromOps(0x9412L, s1Ops))
        assertNull(CapabilityRules.fromOps(0x9706L, s1Ops))
        assertNull(CapabilityRules.fromOps(0x9108L, s1Ops))
    }

    // ─────────────────── ② fromReject ───────────────────

    @Test
    fun `fromReject - 明确被拒即 Unsupported 加 PTP_REJECTED`() {
        listOf(
            PtpConstants.Rc.GENERAL_ERROR,
            PtpConstants.Rc.PARAMETER_NOT_SUPPORTED,
            PtpConstants.Rc.INVALID_STORAGE_ID,
            PtpConstants.Rc.STORE_NOT_AVAILABLE,
            PtpConstants.Rc.INVALID_PARAMETER,
        ).forEach { code ->
            val cap = CapabilityRules.fromReject(code)
            assertEquals("rc=$code", Availability.Unsupported, cap.availability)
            assertEquals(CapabilityEvidence.PTP_REJECTED, cap.evidence)
            assertFalse(cap.mayProbe)
        }
        assertEquals("0x2002", CapabilityRules.fromReject(0x2002).detail)
    }

    // ─────────────────── ③ judgeLiveViewFormats ───────────────────

    @Test
    fun `judgeLiveViewFormats - S1 真机 36 B 回包 3 档 → 两项都 Supported`() {
        // 真机 dump：app/src/test/resources/fixtures/liveview_formats.hex（0x9414 回包，36 B）
        val payload = Fixtures.hex("liveview_formats")
        assertEquals(36, payload.size)
        assertEquals(PtpConstants.Param.LIVEVIEW_FORMAT_TABLE, PtpContainerCodec.readU32(payload, 0))
        assertEquals(3, PtpContainerCodec.readU16(payload, 8))
        assertEquals(8, PtpContainerCodec.readU16(payload, 10))

        val (liveView, formatSwitch) = CapabilityRules.judgeLiveViewFormats(payload)
        assertEquals(Availability.Supported, liveView.availability)
        assertEquals(CapabilityEvidence.PTP_OK, liveView.evidence)
        assertEquals("0x9414 count=3", liveView.detail)
        assertEquals(Availability.Supported, formatSwitch.availability)
        assertEquals(CapabilityEvidence.PTP_OK, formatSwitch.evidence)
        assertTrue(liveView.mayProbe)
        assertTrue(formatSwitch.mayProbe)
    }

    @Test
    fun `judgeLiveViewFormats - count 1 → 取景可 但无切换语义`() {
        val (liveView, formatSwitch) = CapabilityRules.judgeLiveViewFormats(liveViewPayload(count = 1))
        assertEquals(Availability.Supported, liveView.availability)
        assertEquals(Availability.Unsupported, formatSwitch.availability)
        assertFalse(formatSwitch.mayProbe)
        assertTrue(formatSwitch.detail!!.contains("count=1"))
    }

    @Test
    fun `judgeLiveViewFormats - count 0 → 两项都 Unsupported`() {
        val (liveView, formatSwitch) = CapabilityRules.judgeLiveViewFormats(liveViewPayload(count = 0))
        assertEquals(Availability.Unsupported, liveView.availability)
        assertEquals(Availability.Unsupported, formatSwitch.availability)
        assertEquals("0x9414 count=0", liveView.detail)
    }

    @Test
    fun `judgeLiveViewFormats - null 荷载代表 bulk 超时 → 两项 Unknown 加 TIMEOUT`() {
        val (liveView, formatSwitch) = CapabilityRules.judgeLiveViewFormats(null)
        assertEquals(Availability.Unknown, liveView.availability)
        assertEquals(CapabilityEvidence.TIMEOUT, liveView.evidence)
        assertEquals(Availability.Unknown, formatSwitch.availability)
        // Unknown 仍然可试（只有 Unsupported 才隐藏入口）
        assertTrue(liveView.mayProbe)
        assertTrue(formatSwitch.mayProbe)
    }

    @Test
    fun `judgeLiveViewFormats - 结构非法 → Unknown 加 INDETERMINATE 而不是判死`() {
        val tooShort = CapabilityRules.judgeLiveViewFormats(ByteArray(8))
        assertEquals(Availability.Unknown, tooShort.first.availability)
        assertEquals(CapabilityEvidence.INDETERMINATE, tooShort.first.evidence)

        val badEcho = liveViewPayload(count = 3).also {
            PtpContainerCodec.writeU32(it, 0, 0x0D800011L)
        }
        val echoResult = CapabilityRules.judgeLiveViewFormats(badEcho)
        assertEquals(Availability.Unknown, echoResult.first.availability)

        val badStruct = liveViewPayload(count = 3).also {
            PtpContainerCodec.writeU16(it, 10, 12)
        }
        assertEquals(
            Availability.Unknown,
            CapabilityRules.judgeLiveViewFormats(badStruct).first.availability,
        )
    }

    @Test
    fun `judgeLiveViewFormats - 声称 count 超出回包长度 → Unknown`() {
        val payload = liveViewPayload(count = 3)
        PtpContainerCodec.writeU16(payload, 8, 9) // count=9 但只带了 3 条
        val (liveView, _) = CapabilityRules.judgeLiveViewFormats(payload)
        assertEquals(Availability.Unknown, liveView.availability)
        assertEquals(CapabilityEvidence.INDETERMINATE, liveView.evidence)
    }

    // ─────────────────── ④ judgeModeValue ───────────────────

    @Test
    fun `judgeModeValue - 0 到 7 全部合法`() {
        for (value in 0L..7L) {
            val cap = CapabilityRules.judgeModeValue(value)
            assertEquals("value=$value", Availability.Supported, cap.availability)
            assertEquals(CapabilityEvidence.PTP_OK, cap.evidence)
        }
    }

    @Test
    fun `judgeModeValue - 越界返回 Unknown 而不是 Unsupported`() {
        listOf(8L, 0xFFFFFFFFL, 0xFFFEL).forEach { value ->
            val cap = CapabilityRules.judgeModeValue(value)
            assertEquals("value=$value", Availability.Unknown, cap.availability)
            assertEquals(CapabilityEvidence.INDETERMINATE, cap.evidence)
            assertTrue(cap.mayProbe)
        }
    }

    // ─────────────────── ⑤ judgePhotoStyleValue ───────────────────

    @Test
    fun `judgePhotoStyleValue - S1 视频档读到 22 在 1 到 22 内 → Supported`() {
        val cap = CapabilityRules.judgePhotoStyleValue(value = 22L, range = 1L..22L)
        assertEquals(Availability.Supported, cap.availability)
        assertEquals(CapabilityEvidence.PTP_OK, cap.evidence)
    }

    @Test
    fun `judgePhotoStyleValue - 0 是非法值 → Unknown`() {
        val cap = CapabilityRules.judgePhotoStyleValue(value = 0L, range = 1L..22L)
        assertEquals(Availability.Unknown, cap.availability)
        assertEquals(CapabilityEvidence.INDETERMINATE, cap.evidence)
    }

    @Test
    fun `judgePhotoStyleValue - 越界 → Unknown`() {
        val cap = CapabilityRules.judgePhotoStyleValue(value = 23L, range = 1L..22L)
        assertEquals(Availability.Unknown, cap.availability)
        assertTrue(cap.detail!!.contains("越界"))
    }

    @Test
    fun `judgePhotoStyleValue - 无 Profile 断言（range 为 null）→ Unknown`() {
        val cap = CapabilityRules.judgePhotoStyleValue(value = 5L, range = null)
        assertEquals(Availability.Unknown, cap.availability)
        assertTrue(cap.detail!!.contains("无 Profile 断言"))
    }

    // ─────────────────── ⑥ judgeIsoDescriptor ───────────────────

    @Test
    fun `judgeIsoDescriptor - 拍照档 29 项与视频档 23 项都可调可写`() {
        listOf(29, 23, 1, 59, 78).forEach { count ->
            val cap = CapabilityRules.judgeIsoDescriptor(count)
            assertEquals("count=$count", Availability.Supported, cap.availability)
            assertEquals(CapabilityEvidence.PTP_OK, cap.evidence)
            assertEquals("count=$count", "0x9108 count=$count", cap.detail)
            assertTrue("count>0 必须可写", cap.writable)
            assertTrue(cap.mayProbe)
        }
    }

    @Test
    fun `judgeIsoDescriptor - count 0 是相机接管 属性存在值可读但不可写`() {
        // 语义修正（QA 复核发现）：count==0 不能判 Unsupported，
        // 否则 T02/T05 的通用映射会把曝光四件套整块隐藏（v8 §1.6 的 bug）。
        val cap = CapabilityRules.judgeIsoDescriptor(0)
        assertEquals(Availability.Supported, cap.availability)
        assertEquals(CapabilityEvidence.PTP_OK, cap.evidence)
        assertFalse("count==0 不可写", cap.writable)
        assertTrue("属性存在 → 仍可尝试（不可写 ≠ 不存在）", cap.mayProbe)
        assertTrue("detail 必须说清只读原因：${cap.detail}", cap.detail!!.contains("只读"))
    }

    @Test
    fun `judgeIsoDescriptor - 负数是解析器出错 → Unknown`() {
        val cap = CapabilityRules.judgeIsoDescriptor(-1)
        assertEquals(Availability.Unknown, cap.availability)
        assertEquals(CapabilityEvidence.INDETERMINATE, cap.evidence)
    }

    // ─────────────────── ⑦ judgeObjectHandles ───────────────────

    @Test
    fun `judgeObjectHandles - 0x2001 为 Supported`() {
        val cap = CapabilityRules.judgeObjectHandles(PtpConstants.Rc.OK)
        assertEquals(Availability.Supported, cap.availability)
        assertEquals(CapabilityEvidence.PTP_OK, cap.evidence)
    }

    @Test
    fun `judgeObjectHandles - 0x2006 与 0x2013 为 Unsupported`() {
        // 0x2013 = StoreNotAvailable（PIMA 15740 §11.3.19）；S1 按 storage 精查就是 0x2006 或它
        listOf(PtpConstants.Rc.PARAMETER_NOT_SUPPORTED, PtpConstants.Rc.STORE_NOT_AVAILABLE)
            .forEach { rc ->
                val cap = CapabilityRules.judgeObjectHandles(rc)
                assertEquals("rc=$rc", Availability.Unsupported, cap.availability)
                assertEquals(CapabilityEvidence.PTP_REJECTED, cap.evidence)
                assertFalse(cap.mayProbe)
            }
    }

    @Test
    fun `judgeObjectHandles - 其它非零码为 Unknown 不判死`() {
        listOf(PtpConstants.Rc.GENERAL_ERROR, PtpConstants.Rc.INVALID_STORAGE_ID).forEach { rc ->
            val cap = CapabilityRules.judgeObjectHandles(rc)
            assertEquals("rc=$rc", Availability.Unknown, cap.availability)
            assertTrue(cap.mayProbe)
        }
    }

    // ─────────────────── 辅助 ───────────────────

    /** 构造 `0x9414` 回包（首条 8 B 全 0，只关心 count/structsize/长度自洽）。 */
    private fun liveViewPayload(count: Int): ByteArray {
        val total = 12 + count * 8
        val out = ByteArray(total)
        PtpContainerCodec.writeU32(out, 0, PtpConstants.Param.LIVEVIEW_FORMAT_TABLE)
        PtpContainerCodec.writeU32(out, 4, (total - 8).toLong())
        PtpContainerCodec.writeU16(out, 8, count)
        PtpContainerCodec.writeU16(out, 10, 8)
        return out
    }
}
