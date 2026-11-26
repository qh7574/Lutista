package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.profile.ProbeStep
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Op
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.PanasonicOp

/** v8 §1.2 的只读白名单。任何自定义 Profile 仍须过 validate，不能扩到副作用命令。 */
object ProbePlan {
    const val P1 = "judgeLiveViewFormats"
    const val P2 = "judgeModeValue"
    const val P3 = "judgePhotoStyleValue"
    const val P4 = "judgeIsoDescriptor"
    const val P5 = "judgeObjectHandles"
    const val TOTAL_BUDGET_MS = 1_500L
    val allowedOps: Set<Int> = setOf(
        PanasonicOp.GET_LIVEVIEW_FORMATS, PanasonicOp.GET_PROP_VALUE,
        PanasonicOp.GET_PROP_DESC, Op.GET_OBJECT_HANDLES,
    )

    fun standard(): List<ProbeStep> = listOf(
        ProbeStep(null, listOf(PanasonicOp.GET_LIVEVIEW_FORMATS), 300, P1, true),
        ProbeStep(null, listOf(PanasonicOp.GET_PROP_VALUE), 200, P2, true),
        ProbeStep(Feature.PhotoStyle, listOf(PanasonicOp.GET_PROP_VALUE), 200, P3, true),
        ProbeStep(Feature.ConfigWrite, listOf(PanasonicOp.GET_PROP_DESC), 300, P4, true),
        ProbeStep(Feature.ObjectBrowse, listOf(Op.GET_OBJECT_HANDLES), 500, P5),
    )

    /** 同时守卫 opcode、judge 对应关系及顺序；先整体验证，失败时不会已发半套探测。 */
    fun validate(steps: List<ProbeStep>) {
        val standard = standard()
        var previous = -1
        for (step in steps) {
            val index = standard.indexOfFirst { it.judgeId == step.judgeId }
            require(index > previous) { "Probe steps must be a unique ordered subset of P1..P5" }
            require(step.ops == standard[index].ops && step.ops.all { it in allowedOps }) { "Forbidden probe opcode" }
            require(step.feature == standard[index].feature && step.requiresProfileMatch == standard[index].requiresProfileMatch) { "Probe judge routing must match the standard plan" }
            require(step.timeoutMs in 1..standard[index].timeoutMs) { "Probe step budget exceeds standard limit" }
            previous = index
        }
    }
}
