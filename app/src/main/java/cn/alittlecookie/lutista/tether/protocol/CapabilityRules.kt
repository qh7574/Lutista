package cn.alittlecookie.lutista.tether.protocol

import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Param
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Rc

/**
 * 能力判定的 7 条纯规则（零 I/O、零 Android 依赖、可 JVM 单测）。
 *
 * 判定语义的三条铁律（v8 §1.3 / §1.2）：
 * 1. **静态判定只取正向**：`OperationsSupported` 命中 = Supported；**未命中不等于不支持**
 *    （S1 声明的 19 条里一条私有码都没有，而私有码全部实测可用）→ 未命中返回 null / Unknown。
 * 2. **明确被拒 = Unsupported**：只有相机真的回了非 `0x2001` 才是不可用。
 * 3. **说不清 = Unknown**：超时、回包结构非法、无 Profile 断言一律 Unknown，
 *    让 UI 保持「可试」而不是永久隐藏。
 *
 * @see Capability
 */
object CapabilityRules {

    // ─────────────────────── ① 静态判定（0 命令） ───────────────────────

    /**
     * `OperationsSupported` 正向命中判定。
     *
     * @return 命中 → `Supported(STATIC_OPS)`；**未命中 → null**（调用方必须继续走实测或 Profile 断言，
     *   绝不能把 null 当 Unsupported —— 那是修正 ① 描述过的致命产品 bug）。
     */
    fun fromOps(op: Long, ops: Set<Long>): Capability? =
        if (op in ops) Capability.ok(CapabilityEvidence.STATIC_OPS, hex(op)) else null

    /** 相机明确拒绝（`0x2002/0x2006/0x2008/0x2013/0x201D`）→ Unsupported。 */
    fun fromReject(code: Int): Capability =
        Capability.no(CapabilityEvidence.PTP_REJECTED, hex(code.toLong() and 0xFFFFL))

    // ─────────────────────── ② P1 取景格式表 ───────────────────────

    /**
     * `0x9414 [0x0D800012]` 回包判定（v8 §1.2 P1）。
     *
     * 回包布局（36 B / 3 档，见 v7 §10.1）：
     * ```
     * [0..3] echo = 0x0D800012 | [4..7] blobsize = 28 | [8..9] count | [10..11] structsize = 8
     * 之后每条 8 B：height u16 / width u16 / x u16 / freq u16
     * ```
     *
     * 判定：
     * - `payload == null`（bulk 超时）→ 两项 `Unknown(TIMEOUT)`；
     * - `count >= 2` → liveView `Supported` + formatSwitch `Supported`；
     * - `count == 1` → liveView `Supported` + formatSwitch `Unsupported`（只有一档，无切换语义）；
     * - `count == 0` → 两项 `Unsupported`（相机自己说没有取景格式）；
     * - 回包结构非法 → 两项 `Unknown(INDETERMINATE)`（**不判死**）。
     *
     * @return `first` = liveView，`second` = liveViewFormatSwitch。
     */
    fun judgeLiveViewFormats(payload: ByteArray?): Pair<Capability, Capability> {
        if (payload == null) {
            val cap = Capability.unknown(CapabilityEvidence.TIMEOUT, "0x9414 bulk timeout")
            return cap to cap
        }
        if (payload.size < 12) {
            return indeterminatePair("0x9414 payload too short: ${payload.size} B")
        }
        val echo = PtpContainerCodec.readU32(payload, 0)
        if (echo != Param.LIVEVIEW_FORMAT_TABLE) {
            return indeterminatePair("0x9414 unexpected echo ${hex(echo)}")
        }
        val blobSize = PtpContainerCodec.readU32(payload, 4)
        val count = PtpContainerCodec.readU16(payload, 8)
        val structSize = PtpContainerCodec.readU16(payload, 10)
        if (structSize != 8) return indeterminatePair("0x9414 unexpected structsize $structSize")
        if (8L + blobSize > payload.size) {
            return indeterminatePair("0x9414 blobsize $blobSize 超出回包 ${payload.size} B")
        }
        if (12 + count * structSize > payload.size) {
            return indeterminatePair(
                "0x9414 count=$count 需要 ${12 + count * structSize} B，回包仅 ${payload.size} B",
            )
        }

        return when {
            count <= 0 -> {
                val cap = Capability.no(CapabilityEvidence.PTP_OK, "0x9414 count=0")
                cap to cap
            }

            count == 1 -> Capability.ok(CapabilityEvidence.PTP_OK, "0x9414 count=1") to
                Capability.no(
                    CapabilityEvidence.PTP_OK,
                    "0x9414 count=1（只有一档，无切换语义）",
                )

            else -> {
                val detail = "0x9414 count=$count"
                Capability.ok(CapabilityEvidence.PTP_OK, detail) to
                    Capability.ok(CapabilityEvidence.PTP_OK, detail)
            }
        }
    }

    // ─────────────────────── ③ 模式位 ───────────────────────

    /**
     * `0x9402 [0x02000082]` 读到的模式位判定（v8 §1.2 P2）。
     *
     * 合法值 = `0..7`（拍照 0/1/2/3、视频 5、iA 7，定稿 §3.6）。
     * 越界 → `Unknown(INDETERMINATE)`（**不是 Unsupported**：读到一个怪值说明不了相机不支持模式读取）。
     */
    fun judgeModeValue(value: Long): Capability {
        val detail = "0x02000082=${value and 0xFFFF_FFFFL}"
        return if (value in 0L..7L) {
            Capability.ok(CapabilityEvidence.PTP_OK, detail)
        } else {
            Capability.unknown(CapabilityEvidence.INDETERMINATE, "$detail 越界（合法 0..7）")
        }
    }

    // ─────────────────────── ④ 图片格调 ───────────────────────

    /**
     * `0x9402 [0x02000083]` 读到的格调值判定（v8 §1.2 P3）。
     *
     * - `range == null`（无 Profile 断言，如 `GenericPtpProfile`）→ `Unknown(INDETERMINATE)`；
     * - `value == 0` → `Unknown(INDETERMINATE)`（`0` 是非法值，定稿 §3.6）；
     * - `value in range` → `Supported(PTP_OK)`；
     * - 越界 → `Unknown(INDETERMINATE)`。
     */
    fun judgePhotoStyleValue(value: Long, range: LongRange?): Capability {
        val detail = "0x02000083=${value and 0xFFFF_FFFFL}"
        if (range == null) {
            return Capability.unknown(CapabilityEvidence.INDETERMINATE, "$detail 无 Profile 断言")
        }
        if (value == 0L) {
            return Capability.unknown(CapabilityEvidence.INDETERMINATE, "$detail 非法值 0")
        }
        return if (value in range) {
            Capability.ok(CapabilityEvidence.PTP_OK, detail)
        } else {
            Capability.unknown(
                CapabilityEvidence.INDETERMINATE,
                "$detail 越界（Profile 断言 ${range.first}..${range.last}）",
            )
        }
    }

    // ─────────────────────── ⑤ ISO 描述符 ───────────────────────

    /**
     * `0x9108 [0x02000020]` 解析出的档位项数判定（v8 §1.2 P4）。
     *
     * 统一规则：**`count > 0` ⟺ 可调可写**；`count == 0` ⟺ **相机接管：属性存在、值可读、但不可写**。
     *
     * 这条与「`count==0` 判 Unsupported」有本质差别（定稿 §2.3）：
     * `count == 0` 时属性是**存在**的，UI 必须**置灰 + 显示真实值**，
     * 所以返回 `Supported` + `writable = false`，**不能**返回 `Unsupported`
     * —— 后者在通用映射下会把快门/光圈/ISO/曝光补偿四件套整块隐藏（v8 §1.6 反复警告的 bug）。
     *
     * 负数是解析器出错的信号 → `Unknown(INDETERMINATE)`。
     */
    fun judgeIsoDescriptor(count: Int): Capability = when {
        count > 0 -> Capability.ok(
            evidence = CapabilityEvidence.PTP_OK,
            detail = "0x9108 count=$count",
            writable = true,
        )

        count == 0 -> Capability.ok(
            evidence = CapabilityEvidence.PTP_OK,
            detail = "0x9108 count=0（只读：相机接管，值仍可读）",
            writable = false,
        )

        else -> Capability.unknown(
            CapabilityEvidence.INDETERMINATE,
            "0x9108 count=$count 非法（解析器出错）",
        )
    }

    // ─────────────────────── ⑥ 对象枚举 ───────────────────────

    /**
     * `0x1007 GetObjectHandles` 响应码判定（v8 §1.2 P5）。
     *
     * - `0x2001` → `Supported(PTP_OK)`；
     * - `0x2006`（`ParameterNotSupported`）/ `0x2013`（`StoreNotAvailable`）→ `Unsupported(PTP_REJECTED)`
     *   （S1 上按 storage 精查就是这两种；调用方须先按 quirks 重试一次具体 storage 再下结论）；
     * - 其它非零码 → `Unknown(INDETERMINATE)`（可能是一次抖动，**不判死**）；
     * - 超时由调用方单独处理为 `Unknown(TIMEOUT)`（本函数拿不到「超时」这个信息）。
     *
     * ⚠️ 给调用方（T02 探测执行器）的约束：本函数只做「单次」判定。`Unknown` **不得**直接渲染成
     * 「不支持」；**连续两次同码才降级为 Unsupported**，该重试策略由探测执行器负责，不在本纯函数内。
     */
    fun judgeObjectHandles(rc: Int): Capability = when (rc) {
        Rc.OK -> Capability.ok(CapabilityEvidence.PTP_OK, hex(rc.toLong() and 0xFFFFL))
        Rc.PARAMETER_NOT_SUPPORTED, Rc.STORE_NOT_AVAILABLE ->
            Capability.no(CapabilityEvidence.PTP_REJECTED, hex(rc.toLong() and 0xFFFFL))

        else -> Capability.unknown(
            CapabilityEvidence.INDETERMINATE,
            "0x1007 rc=${hex(rc.toLong() and 0xFFFFL)}",
        )
    }

    // ─────────────────────── 工具 ───────────────────────

    private fun indeterminatePair(detail: String): Pair<Capability, Capability> {
        val cap = Capability.unknown(CapabilityEvidence.INDETERMINATE, detail)
        return cap to cap
    }

    private fun hex(value: Long): String = "0x" + value.toString(16).uppercase()
}
