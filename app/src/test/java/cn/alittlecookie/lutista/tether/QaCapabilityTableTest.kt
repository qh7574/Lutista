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
 * QA 独立复核（T01）：[CapabilityRules] 与「定稿 §2 能力矩阵 / §3 属性编码」的真值表逐条对。
 *
 * 与工程师的 `CapabilityRulesTest` 的分工：
 * - 那份测函数本身的分支（含 detail 文案、大量非法输入）；
 * - 这份**只按真机实测数据造真值表**：快门 0/29/59 档、光圈 0/78 档、ISO 29/23 项，
 *   逐条核对「`count > 0` ⟺ 可调可写」这条唯一规则有没有被档位硬编码污染。
 *
 * ⚠️ 语义提醒（**已按复核报告修正**）：`count == 0` 曾经被判为 `Unsupported`（当时的 `attemptable == false`），
 * 而定稿 §2.3 要求的是「只读但值可读，UI 置灰 + 显示真实值」。现在改为
 * `availability = Supported` + `writable = false` + `detail` 注明"只读（相机接管）"，
 * 即**属性存在、值可读、不可写**。`Unsupported` 只留给"这条命令/属性根本不存在"。
 * 本文件的 `assertWritable` 已同步改为断言 `writable` 字段（不再用 availability 表达可写性）。
 */
class QaCapabilityTableTest {

    // ─────────────── ① 唯一判定规则：count > 0 ⟺ 可调可写（定稿 §2.3） ───────────────

    @Test
    fun `快门 - 拍照 P A 档 0 档只读，S M 档 59 档可写`() {
        // 定稿 §2.1：P/A 快门描述符 204 B / 0 档；S/M 440 B / 59 档
        assertWritable(false, 0, "拍照 P/A 快门 0 档")
        assertWritable(true, 59, "拍照 S/M 快门 59 档")
    }

    @Test
    fun `快门 - 视频 P A 档 0 档只读，S M 档 29 档可写`() {
        // 定稿 §2.2：视频 P/A 快门 204 B / 0 档；S/M 320 B / 29 档
        assertWritable(false, 0, "视频 P/A 快门 0 档")
        assertWritable(true, 29, "视频 S/M 快门 29 档")
    }

    @Test
    fun `光圈 - 0 档只读，78 档可写（电子镜头 f3_5 到 f22）`() {
        // 定稿 §2.1 / §3.2：198 B / 0 档 vs 354 B / 78 档
        assertWritable(false, 0, "光圈 0 档")
        assertWritable(true, 78, "光圈 78 档")
    }

    @Test
    fun `ISO - 拍照 29 项与视频 23 项都可写`() {
        // 定稿 §3.3：拍照 316 B / 29 项；视频 256 B / 23 项（下限 400）
        assertWritable(true, 29, "拍照 ISO 29 项")
        assertWritable(true, 23, "视频 ISO 23 项")
    }

    @Test
    fun `负数 count 只能来自解析器出错 - 判 Unknown 而不是 Unsupported`() {
        listOf(-1, -29, Int.MIN_VALUE).forEach { count ->
            val cap = CapabilityRules.judgeIsoDescriptor(count)
            assertEquals("count=$count", Availability.Unknown, cap.availability)
            assertEquals(CapabilityEvidence.INDETERMINATE, cap.evidence)
            assertTrue("Unknown 必须可试", cap.mayProbe)
        }
    }

    @Test
    fun `count 为 0 的判定是确定的 - 属性存在但不可写`() {
        // 这条区分很重要：只有「相机自己说没有档位」才是确定的结论，超时/解析失败必须是 Unknown；
        // 而 count==0 的表达是「属性存在、值可读、相机接管 → 不可写」，不是「不存在」。
        val cap = CapabilityRules.judgeIsoDescriptor(0)
        assertEquals(Availability.Supported, cap.availability)
        assertEquals(CapabilityEvidence.PTP_OK, cap.evidence)
        assertFalse("count==0 → 不可写", cap.writable)
        assertTrue("不可写 ≠ 不存在，仍可尝试", cap.mayProbe)
    }

    // ─────────────── ①b writable 是一个**独立维度**（定稿 §2.3.1） ───────────────

    @Test
    fun `writable 维度 - 只有 judgeIsoDescriptor 的 count 大于 0 会置 true`() {
        // 防回归：将来若有人把「可写」错误地挂到别的判定函数上（例如 judgeObjectHandles 判 OK 就置 writable），
        // 这里会立刻红。可写性只由描述符 count 决定，其它六个函数一律不得置 writable。
        listOf(0, 1, 23, 29, 59, 78).forEach { count ->
            val cap = CapabilityRules.judgeIsoDescriptor(count)
            assertEquals("count=$count", count > 0, cap.writable)
        }

        val others = listOf(
            "judgeObjectHandles(OK)" to CapabilityRules.judgeObjectHandles(PtpConstants.Rc.OK),
            "judgeObjectHandles(0x2006)" to
                CapabilityRules.judgeObjectHandles(PtpConstants.Rc.PARAMETER_NOT_SUPPORTED),
            "judgeModeValue(3)" to CapabilityRules.judgeModeValue(3L),
            "judgePhotoStyleValue(22)" to CapabilityRules.judgePhotoStyleValue(22L, 1L..22L),
            "fromReject(0x201D)" to CapabilityRules.fromReject(PtpConstants.Rc.INVALID_PARAMETER),
            "fromOps(0x1001) 命中" to CapabilityRules.fromOps(0x1001L, DeviceInfoParserSet.s1Ops)!!,
            "judgeLiveViewFormats(count=3).first" to CapabilityRules.judgeLiveViewFormats(Fixtures.hex("liveview_formats")).first,
        )
        others.forEach { (label, cap) ->
            assertFalse("$label 不得置 writable（可写性只由描述符 count 决定）", cap.writable)
        }
    }

    @Test
    fun `mayProbe 已不再等价于可写 - T02 必须读 writable 才能决定置灰`() {
        // 语义漂移锚点：改造前这个字段叫 attemptable，count==0 时它为 false（等于「不可写」）；
        // 改造后改名 mayProbe，只表示「允许调用」，恒为 true。旧名已删，依赖旧语义的代码会**编译不过**。
        val readOnly = CapabilityRules.judgeIsoDescriptor(0)
        assertTrue("mayProbe 现在恒为 true", readOnly.mayProbe)
        assertFalse("真正要读的是 writable", readOnly.writable)

        // 对照：真正「不存在」时才允许 mayProbe == false
        assertFalse(CapabilityRules.fromReject(PtpConstants.Rc.GENERAL_ERROR).mayProbe)
        assertFalse(
            CapabilityRules.judgeObjectHandles(PtpConstants.Rc.PARAMETER_NOT_SUPPORTED).mayProbe,
        )
    }

    @Test
    fun `Unsupported 只用于不存在 - 取景 count 0 与描述符 count 0 语义不同`() {
        // 相机自己说「一个取景格式都没有」= 取景能力不存在 → Unsupported（可硬隐藏入口）
        val (liveView, formatSwitch) = CapabilityRules.judgeLiveViewFormats(
            liveView(count = 0, total = 12, blobSize = 4),
        )
        assertEquals(Availability.Unsupported, liveView.availability)
        assertEquals(Availability.Unsupported, formatSwitch.availability)
        assertFalse(liveView.mayProbe)

        // 而描述符 count==0 只是「不可写」，属性仍然存在 → 绝不允许 Unsupported
        val readOnlyProp = CapabilityRules.judgeIsoDescriptor(0)
        assertEquals(Availability.Supported, readOnlyProp.availability)
        assertTrue(readOnlyProp.mayProbe)
    }

    // ─────────────── ② 0x9414 取景格式表的结构边界 ───────────────

    @Test
    fun `取景格式表 - count 恰好填满回包才算 Supported`() {
        val exact = liveView(count = 2, total = 12 + 2 * 8, blobSize = (12 + 2 * 8 - 8).toLong())
        assertSupportedLiveView(exact)

        // 少 1 字节：blobsize 自适应（不触发 blobsize 分支），让 count 分支单独报错
        val shortByOne = liveView(count = 2, total = 12 + 2 * 8 - 1, blobSize = (12 + 2 * 8 - 1 - 8).toLong())
        val (shortLiveView, _) = CapabilityRules.judgeLiveViewFormats(shortByOne)
        assertEquals(Availability.Unknown, shortLiveView.availability)
        assertEquals(CapabilityEvidence.INDETERMINATE, shortLiveView.evidence)
        assertTrue(shortLiveView.detail!!.contains("count=2"))
    }

    @Test
    fun `取景格式表 - blobsize 边界 8 加 blobsize 等于回包长度才通过`() {
        val ok = liveView(count = 1, total = 20, blobSize = 12)
        assertEquals(Availability.Supported, CapabilityRules.judgeLiveViewFormats(ok).first.availability)

        val oneTooBig = liveView(count = 1, total = 20, blobSize = 13)
        val (cap, _) = CapabilityRules.judgeLiveViewFormats(oneTooBig)
        assertEquals(Availability.Unknown, cap.availability)
        assertTrue(cap.detail!!.contains("blobsize"))
    }

    @Test
    fun `取景格式表 - structsize 不是 8 时判 Unknown 而不是瞎解`() {
        listOf(0, 4, 9, 16, 0xFFFF).forEach { structSize ->
            val payload = liveView(count = 1, total = 20, blobSize = 12, structSize = structSize)
            val (cap, _) = CapabilityRules.judgeLiveViewFormats(payload)
            assertEquals("structSize=$structSize", Availability.Unknown, cap.availability)
        }
    }

    @Test
    fun `取景格式表 - count 用 u16 上限 65535 不溢出`() {
        val payload = liveView(count = 1, total = 20, blobSize = 12)
        PtpContainerCodec.writeU16(payload, 8, 0xFFFF)
        val (cap, _) = CapabilityRules.judgeLiveViewFormats(payload)
        assertEquals(Availability.Unknown, cap.availability)
    }

    @Test
    fun `取景格式表 - 真机 36 B 三档回包仍判两项 Supported`() {
        assertSupportedLiveView(Fixtures.hex("liveview_formats"))
    }

    // ─────────────── ③ 0x1007 响应码真值表 ───────────────

    @Test
    fun `对象枚举 - 只有 0x2006 与 0x2013 判 Unsupported`() {
        assertEquals(Availability.Supported, CapabilityRules.judgeObjectHandles(PtpConstants.Rc.OK).availability)

        listOf(
            PtpConstants.Rc.PARAMETER_NOT_SUPPORTED,
            PtpConstants.Rc.STORE_NOT_AVAILABLE,
        ).forEach { rc ->
            val cap = CapabilityRules.judgeObjectHandles(rc)
            assertEquals("rc=0x${rc.toString(16)}", Availability.Unsupported, cap.availability)
            assertEquals(CapabilityEvidence.PTP_REJECTED, cap.evidence)
            assertFalse(cap.mayProbe)
        }
    }

    @Test
    fun `对象枚举 - 0x2002 与 0x2008 与 0x201D 只能判 Unknown 不许判死`() {
        // ⚠️ 这是与 fromReject 的**刻意差异**：单次 0x1007 回 0x2002 可能只是抖动，
        // 判死会让整个图库入口消失。T02 的重试策略（连续两次同码才降级）就建立在这里。
        listOf(
            PtpConstants.Rc.GENERAL_ERROR,
            PtpConstants.Rc.INVALID_STORAGE_ID,
            PtpConstants.Rc.INVALID_PARAMETER,
        ).forEach { rc ->
            val cap = CapabilityRules.judgeObjectHandles(rc)
            assertEquals("rc=0x${rc.toString(16)}", Availability.Unknown, cap.availability)
            assertEquals(CapabilityEvidence.INDETERMINATE, cap.evidence)
            assertTrue(cap.mayProbe)
        }
    }

    // ─────────────── ④ 静态判定只取正向 ───────────────

    @Test
    fun `fromOps - 未命中一律 null 而不是 Unsupported`() {
        val s1Ops = DeviceInfoParserSet.s1Ops
        // 定稿 §1.2 的坑：私有码一条都不在声明表里，但全部实测可用
        listOf(0x9402L, 0x9403L, 0x9108L, 0x9404L, 0x9405L, 0x9412L, 0x9414L, 0x9706L).forEach { op ->
            assertNull("0x${op.toString(16)} 未声明 → 必须 null", CapabilityRules.fromOps(op, s1Ops))
        }
        listOf(0x1001L, 0x1007L, 0x1009L, 0x101BL).forEach { op ->
            val cap = CapabilityRules.fromOps(op, s1Ops)
            assertEquals(Availability.Supported, cap!!.availability)
            assertEquals(CapabilityEvidence.STATIC_OPS, cap.evidence)
        }
    }

    @Test
    fun `fromReject - 五个真机遇到过的拒绝码全部 Unsupported`() {
        listOf(0x2002, 0x2006, 0x2008, 0x2013, 0x201D).forEach { rc ->
            val cap = CapabilityRules.fromReject(rc)
            assertEquals("rc=0x${rc.toString(16)}", Availability.Unsupported, cap.availability)
            assertEquals(CapabilityEvidence.PTP_REJECTED, cap.evidence)
            assertEquals("0x" + rc.toString(16).uppercase(), cap.detail)
            assertFalse(cap.mayProbe)
        }
    }

    @Test
    fun `fromReject 与 judgeObjectHandles 对 0x2002 的结论不同 - 调用方必须选对函数`() {
        // 记录下来，避免 T02 把 0x1007 的回包丢进 fromReject 导致图库入口被硬隐藏
        assertEquals(Availability.Unsupported, CapabilityRules.fromReject(PtpConstants.Rc.GENERAL_ERROR).availability)
        assertEquals(Availability.Unknown, CapabilityRules.judgeObjectHandles(PtpConstants.Rc.GENERAL_ERROR).availability)
    }

    // ─────────────── ⑤ 模式位 / 图片格调 ───────────────

    @Test
    fun `模式位 - 0 到 7 判 Supported，8 及以上判 Unknown`() {
        // 实现契约（CapabilityRules KDoc）：0..7 合法。
        // 注意 4 / 6 在定稿 §3.6 里**没有出现过**（实测只有 0/1/2/3/5/7），
        // 但读到 4/6 说明「模式位可读」这件事成立，判 Supported 是合理的；
        // T02 的档位分支不能假设只可能是 0/1/2/3/5/7（见复核报告 C 组风险项）。
        listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L).forEach {
            assertEquals("value=$it", Availability.Supported, CapabilityRules.judgeModeValue(it).availability)
        }
        listOf(8L, 9L, 0xFFFFFFFFL, 0xFFFEL).forEach {
            assertEquals("value=$it", Availability.Unknown, CapabilityRules.judgeModeValue(it).availability)
            assertTrue(CapabilityRules.judgeModeValue(it).mayProbe)
        }
    }

    @Test
    fun `图片格调 - 视频 17 项真值 22 在 1 到 22 内合法，0 永远非法`() {
        val videoRange = 1L..22L
        assertEquals(Availability.Supported, CapabilityRules.judgePhotoStyleValue(22L, videoRange).availability)
        listOf(0L, 23L, 0xFFFFFFFFL).forEach { value ->
            assertEquals("value=$value", Availability.Unknown, CapabilityRules.judgePhotoStyleValue(value, videoRange).availability)
        }
        assertTrue(CapabilityRules.judgePhotoStyleValue(22L, null).mayProbe)
        assertEquals(Availability.Unknown, CapabilityRules.judgePhotoStyleValue(22L, null).availability)
    }

    // ─────────────── 辅助 ───────────────

    private fun assertWritable(writable: Boolean, count: Int, label: String) {
        val cap = CapabilityRules.judgeIsoDescriptor(count)
        // 语义（修正后）：可写性只看 `writable`，availability 一律 Supported ——
        // 描述符存在（无论 count 是 0 还是 78）就说明「这个属性在这台相机上存在且值可读」。
        assertEquals("$label：count=$count 时 availability 不符", Availability.Supported, cap.availability)
        assertEquals("$label：count=$count 时 writable 不符", writable, cap.writable)
        assertTrue("$label：属性存在 → 必须可尝试", cap.mayProbe)
    }

    private fun assertSupportedLiveView(payload: ByteArray) {
        val (liveView, formatSwitch) = CapabilityRules.judgeLiveViewFormats(payload)
        assertEquals(Availability.Supported, liveView.availability)
        assertEquals(Availability.Supported, formatSwitch.availability)
        assertEquals(CapabilityEvidence.PTP_OK, liveView.evidence)
    }

    /** 构造 `0x9414` 回包：`[echo u32][blobsize u32][count u16][structsize u16][entries...]`。 */
    private fun liveView(
        count: Int,
        total: Int,
        blobSize: Long,
        structSize: Int = 8,
        echo: Long = PtpConstants.Param.LIVEVIEW_FORMAT_TABLE,
    ): ByteArray {
        val out = ByteArray(total)
        PtpContainerCodec.writeU32(out, 0, echo)
        PtpContainerCodec.writeU32(out, 4, blobSize)
        PtpContainerCodec.writeU16(out, 8, count)
        PtpContainerCodec.writeU16(out, 10, structSize)
        return out
    }

    /** 真机声明的 19 条 opcode（定稿 §1.2）。 */
    private object DeviceInfoParserSet {
        val s1Ops: Set<Long> = setOf(
            0x1001L, 0x1002L, 0x1003L, 0x1004L, 0x1005L, 0x1006L, 0x1007L, 0x1008L, 0x1009L,
            0x100AL, 0x100BL, 0x100FL, 0x1014L, 0x1015L, 0x1016L, 0x101BL, 0x9102L, 0x9103L, 0x9104L,
        )
    }
}
