package cn.alittlecookie.lutista.tether.protocol

/**
 * 能力三态。
 *
 * `Unknown` 的存在理由：探测超时 / 无副作用前提下无法判断 / Profile 未声明。
 * **`Unknown` 永远不是 `Unsupported`**：前者允许「试一下」，后者是硬隐藏（v8 §1.6）。
 */
enum class Availability {
    /** 已确认可用。 */
    Supported,

    /** 已确认不可用。 */
    Unsupported,

    /** 无法判定（超时 / 未探 / 无 Profile 断言）。 */
    Unknown,
}

/**
 * 判定证据。UI 用它决定「隐藏 / 置灰 / 点击才试」，也用于诊断面板。
 *
 * @see CapabilityRules 每个纯函数的 evidence 取值
 */
enum class CapabilityEvidence {
    /** `GetDeviceInfo.OperationsSupported` **正向**命中（未命中不代表不支持，见修正 ①）。 */
    STATIC_OPS,

    /** `CameraProfile` 声明（机型实测数据固化）。 */
    PROFILE_ASSERTED,

    /** 探测命令返回 `0x2001` 且回包内容支持该结论。 */
    PTP_OK,

    /** 探测命令返回非 `0x2001`（`0x2002/0x2006/0x2008/0x201D/0x2013`）。 */
    PTP_REJECTED,

    /** bulk 超时（-1），单次，不计入连续超时（v8 §1.4）。 */
    TIMEOUT,

    /** GetDeviceInfo 异常 / 无 Profile / 回包结构非法，不能无副作用判定。 */
    INDETERMINATE,

    /** 总预算用尽，未轮到。 */
    NOT_PROBED,
}

/**
 * 单个特性的能力结论。
 *
 * ## 两个维度是正交的，不要混用
 * - [availability]：**这个属性/操作存不存在**。只有 `Unsupported` 才意味着「根本没有」。
 * - [writable]：**当前能不能改**（只对可写性判定函数有意义，其余场景恒为 false）。
 *
 * 典型场景：相机接管曝光时描述符 `count == 0` —— 属性**存在**、值**可读**，但**不可写**。
 * 正确表示是 `availability = Supported` + `writable = false`，UI 应当
 * **置灰 + 显示真实值**（定稿 §2.3）。
 *
 * ## 给 UI / 调用方的硬约束
 * **`availability` 不得用来决定「隐藏还是置灰」**。决定"隐藏入口"只能在两种情况下做：
 * 1. `availability == Unsupported`（相机明确说没有）；且
 * 2. 该特性不属于「曝光四件套」（快门/光圈/ISO/曝光补偿）——四件套**永不隐藏**，
 *    不可写时置灰 + 显示 `—` 或真实值（v8 §1.6）。
 *
 * @property detail 诊断串，例 `0x9108 count=29` / `0x2002`；**不是**给用户看的文案。
 * @property writable 是否可写（写后需读回校验）。默认 false；可写性判定函数在
 *   `count > 0` 时置 true。
 */
data class Capability(
    val availability: Availability,
    val evidence: CapabilityEvidence,
    val detail: String? = null,
    val writable: Boolean = false,
) {
    /**
     * 允许尝试（`Unknown` → true，`Unsupported` → false）。
     *
     * ⚠️ **绝不代表可写**：判断「可调 vs 置灰」只看 [writable]。
     *
     * 历史名 `attemptable` 已废弃改名：改造前它等价于「可写」（`count == 0` 时为 false），
     * 改造后恒为 true —— 保留旧名会让照旧契约写的调用方**静默**把只读属性当可写。
     * 刻意**不留** `@Deprecated` 别名：T01 未提交、无外部消费者，让编译器报错比让人读注释可靠，
     * 且留别名会给测试引入 deprecation warning，破坏「0 warning」验收基线。
     */
    val mayProbe: Boolean get() = availability != Availability.Unsupported

    companion object {
        /** 判定为可用。 */
        fun ok(
            evidence: CapabilityEvidence,
            detail: String? = null,
            writable: Boolean = false,
        ): Capability = Capability(Availability.Supported, evidence, detail, writable)

        /** 判定为不可用（**只用于「这条命令/属性根本不存在」**）。 */
        fun no(evidence: CapabilityEvidence, detail: String? = null): Capability =
            Capability(Availability.Unsupported, evidence, detail)

        /** 判定为未知。 */
        fun unknown(evidence: CapabilityEvidence, detail: String? = null): Capability =
            Capability(Availability.Unknown, evidence, detail)
    }
}
