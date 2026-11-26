package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.profile.ProbeStep
import cn.alittlecookie.lutista.tether.profile.ProfileMatchLevel
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import cn.alittlecookie.lutista.tether.protocol.CapabilityRules
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Op
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.PanasonicOp
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Param
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Prop
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Rc
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse

/** 仅允许在执行器确认尚未发起 USB 事务时抛出；真实 PtpTimeoutException 绝不能包装成此类。 */
class ProbeBudgetExceededException(message: String) : Exception(message)

/** 底层返回了响应但 diagnostics 已 fatal。调用方必须取消队列、断开并提示拔插。 */
class ProbeFatalException(message: String) : PtpException(message)

data class ProbeResult(val capabilities: CameraCapabilities, val gallerySeed: List<Long>?)

/**
 * P1..P6 串行只读探测，无 Android / Settings / 会话实现依赖。
 * execute 必须在会话的同一 ptp-io 队列上执行，并把传入 timeoutMs 传给真实 transaction。
 * fatal 在每步及每次实际事务前后检查；所有真实传输异常原样上抛，没有「两次才 fatal」。
 * 调用 probe 前会话必须已经完成 GetDeviceInfo / GetStorageIDs 热身。
 */
class CapabilityProbe(
    private val execute: suspend (PtpOp, Int) -> Pair<PtpResponse, ByteArray>,
    private val isFatal: () -> Boolean,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun probe(
        caps: PtpDeviceCaps,
        profile: CameraProfile,
        storageIds: List<Long> = emptyList(),
    ): ProbeResult {
        ProbePlan.validate(profile.probePlan)
        val start = nowMs()
        val sequence = mutableListOf<Int>()
        var result = CameraCapabilities(
            caps.model, caps.serialNumber, caps.deviceVersion, profile.id,
            wallClockMs(), 0L, emptyMap(),
        )
        var seed: List<Long>? = null
        fun elapsed(): Long = (nowMs() - start).coerceAtLeast(0L)
        try {
            ensureNotFatal()
            for (step in profile.probePlan) {
                ensureNotFatal()
                if (elapsed() >= ProbePlan.TOTAL_BUDGET_MS) break
                val vendorStep = step.ops.any { it >= 0x9000 }
                if (vendorStep && (profile.matchLevel == ProfileMatchLevel.Generic ||
                        !profile.match(caps.usbVendorId, caps.usbProductId, caps))) continue
                if (step.judgeId == ProbePlan.P3 && profile.photoStyleValues == null) continue
                if (step.judgeId == ProbePlan.P4 && !profile.quirks.supportsPropertyDescriptor) continue
                val stepStart = nowMs()
                suspend fun attempt(op: PtpOp): Attempt {
                    ensureNotFatal()
                    check(op.code in ProbePlan.allowedOps) { "Forbidden probe opcode" }
                    val remaining = minOf(
                        ProbePlan.TOTAL_BUDGET_MS - elapsed(),
                        step.timeoutMs.toLong() - (nowMs() - stepStart).coerceAtLeast(0L),
                    )
                    if (remaining <= 0L) return Attempt.Budget
                    sequence += op.code
                    emit("probe opcode=${hex(op.code)} timeoutMs=${remaining}")
                    val response = try {
                        execute(op, remaining.toInt())
                    } catch (budget: ProbeBudgetExceededException) {
                        // 唯一可降级的超时：队列在 USB 前耗尽预算。fatal 真值仍优先。
                        if (isFatal()) throw budget
                        return Attempt.PreUsbTimeout
                    }
                    ensureNotFatal()
                    return Attempt.Reply(response.first, response.second)
                }
                val attempt = when (step.judgeId) {
                    ProbePlan.P1 -> attempt(PtpOp(PanasonicOp.GET_LIVEVIEW_FORMATS, longArrayOf(Param.LIVEVIEW_FORMAT_TABLE)))
                    ProbePlan.P2 -> attempt(PtpOp(PanasonicOp.GET_PROP_VALUE, longArrayOf(Prop.MODE_POS)))
                    ProbePlan.P3 -> attempt(PtpOp(PanasonicOp.GET_PROP_VALUE, longArrayOf(Prop.PHOTO_STYLE)))
                    ProbePlan.P4 -> attempt(PtpOp(PanasonicOp.GET_PROP_DESC, longArrayOf(Prop.ISO_CONTAINER, 0L, 0L)))
                    ProbePlan.P5 -> {
                        val concrete = storageIds.firstOrNull { it in 1L until PtpConstants.ALL_STORAGE_IDS }
                            ?: profile.quirks.storageIdForObjectQuery.takeIf { it in 1L until PtpConstants.ALL_STORAGE_IDS }
                        val storage = if (profile.quirks.storageIdForObjectQuery == 0L) concrete
                            else profile.quirks.storageIdForObjectQuery
                        if (storage == null) {
                            Attempt.MissingStorage
                        } else {
                            var reply = attempt(objectHandlesOp(storage))
                            if (storage == PtpConstants.ALL_STORAGE_IDS && reply is Attempt.Reply && reply.response.code in STORAGE_REJECTS &&
                                concrete != null) {
                                reply = attempt(objectHandlesOp(concrete))
                            } else if (storage == PtpConstants.ALL_STORAGE_IDS && reply is Attempt.Reply && reply.response.code in STORAGE_REJECTS && concrete == null) {
                                reply = Attempt.MissingStorage
                            }
                            reply
                        }
                    }
                    else -> error("Unvalidated probe step")
                }
                val features = featuresFor(step)
                when (attempt) {
                    Attempt.Budget -> features.forEach {
                        result = result.with(it, Capability.unknown(CapabilityEvidence.NOT_PROBED, "probe budget exhausted before USB"))
                    }
                    Attempt.PreUsbTimeout -> features.forEach {
                        result = result.with(it, Capability.unknown(CapabilityEvidence.TIMEOUT, "queue timeout before USB"))
                    }
                    Attempt.MissingStorage -> result = result.with(
                        Feature.ObjectBrowse, Capability.unknown(CapabilityEvidence.INDETERMINATE, "no concrete StorageID for safe query/retry"),
                    )
                    is Attempt.Reply -> {
                        if (attempt.response.code != Rc.OK) {
                            val cap = if (step.judgeId == ProbePlan.P5) CapabilityRules.judgeObjectHandles(attempt.response.code)
                                else CapabilityRules.fromReject(attempt.response.code)
                            features.forEach { result = result.with(it, cap) }
                        } else {
                            when (step.judgeId) {
                                ProbePlan.P1 -> {
                                    val pair = CapabilityRules.judgeLiveViewFormats(attempt.data)
                                    result = result.with(Feature.LiveView, pair.first).with(Feature.LiveViewFormatSwitch, pair.second)
                                }
                                ProbePlan.P2 -> {
                                    val value = ProbePayloads.propertyValue(attempt.data, Prop.MODE_POS)
                                    val cap = value?.let(CapabilityRules::judgeModeValue) ?: malformed(step)
                                    result = result.with(Feature.ModeRead, cap).with(Feature.ConfigRead, cap).copy(probedModePos = value)
                                }
                                ProbePlan.P3 -> {
                                    val value = ProbePayloads.propertyValue(attempt.data, Prop.PHOTO_STYLE)
                                    val cap = value?.let { CapabilityRules.judgePhotoStyleValue(it, profile.photoStyleValues) } ?: malformed(step)
                                    result = result.with(Feature.PhotoStyle, cap)
                                }
                                ProbePlan.P4 -> {
                                    val count = ProbePayloads.isoDescriptorCount(attempt.data)
                                    val cap = count?.let(CapabilityRules::judgeIsoDescriptor) ?: malformed(step)
                                    result = result.with(Feature.ConfigWrite, cap)
                                }
                                ProbePlan.P5 -> {
                                    val handles = ProbePayloads.handles(attempt.data)
                                    result = result.with(Feature.ObjectBrowse, if (handles != null) {
                                        seed = handles
                                        CapabilityRules.judgeObjectHandles(Rc.OK)
                                    } else malformed(step))
                                }
                            }
                        }
                    }
                }
                ensureNotFatal()
            }
            ensureNotFatal()
            result = applyP6(result, caps, profile).copy(totalProbeMs = elapsed())
            return ProbeResult(result, seed)
        } finally {
            emit("probe totalProbeMs=${elapsed()} opcodes=${sequence.joinToString(",") { hex(it) }}")
        }
    }

    /** 缓存命中跳过 P1..P5；恢复的所有 writable 均为 false，不解析历史 detail。 */
    fun supplementP6(cached: CameraCapabilities, caps: PtpDeviceCaps, profile: CameraProfile): CameraCapabilities {
        ensureNotFatal()
        var safe = cached.copy(model = caps.model, serial = caps.serialNumber, firmware = caps.deviceVersion, profileId = profile.id, totalProbeMs = 0L, probedModePos = null)
        Feature.entries.forEach { safe = safe.with(it, safe[it].copy(writable = false)) }
        safe = applyP6(safe, caps, profile)
        ensureNotFatal()
        emit("probe totalProbeMs=0 opcodes= cacheHit=true")
        return safe
    }

    private fun applyP6(initial: CameraCapabilities, caps: PtpDeviceCaps, profile: CameraProfile): CameraCapabilities {
        var result = initial
        val ops = caps.operationsSupported
        val matched = profile.matchLevel != ProfileMatchLevel.Generic && profile.match(caps.usbVendorId, caps.usbProductId, caps)
        fun unknown(): Capability = Capability.unknown(CapabilityEvidence.INDETERMINATE, "not declared; absence is not rejection")
        result = result.with(Feature.Battery, if (Op.GET_DEVICE_PROP_VALUE.toLong() in ops && Prop.BATTERY_LEVEL in caps.devicePropertiesSupported)
            Capability.ok(CapabilityEvidence.STATIC_OPS, "0x1015 + 0x5001") else unknown())
        result = result.with(Feature.Thumbnail, CapabilityRules.fromOps(Op.GET_THUMB.toLong(), ops) ?: unknown())
        result = result.with(Feature.RawPreview, if (Op.GET_PARTIAL_OBJECT.toLong() in ops && profile.quirks.rawFormat in caps.imageFormats)
            Capability.ok(CapabilityEvidence.STATIC_OPS, "0x101B + rawFormat") else unknown())
        result = result.with(Feature.ObjectDownload, CapabilityRules.fromOps(Op.GET_OBJECT.toLong(), ops)
            ?: CapabilityRules.fromOps(Op.GET_PARTIAL_OBJECT.toLong(), ops) ?: unknown())
        result = result.with(Feature.CapturePhoto, if (matched && profile.capturePhotoSupported)
            Capability.ok(CapabilityEvidence.PROFILE_ASSERTED, profile.id) else unknown())
        result = result.with(Feature.CaptureMovie, if (matched && profile.captureMovieSupported)
            Capability.ok(CapabilityEvidence.PROFILE_ASSERTED, profile.id) else unknown())
        return result
    }

    private fun ensureNotFatal() {
        if (isFatal()) throw ProbeFatalException("PTP transport is fatal; stop probe and reconnect USB")
    }
    private fun emit(message: String) { try { log(message) } catch (_: Exception) { /* 诊断不能改变探测结果。 */ } }
    private fun objectHandlesOp(storage: Long) = PtpOp(Op.GET_OBJECT_HANDLES, longArrayOf(storage, 0L, PtpConstants.ROOT_PARENT_HANDLE))
    private fun malformed(step: ProbeStep): Capability = Capability.unknown(CapabilityEvidence.INDETERMINATE, "${step.judgeId}: malformed payload")
    private fun featuresFor(step: ProbeStep): List<Feature> = when (step.judgeId) {
        ProbePlan.P1 -> listOf(Feature.LiveView, Feature.LiveViewFormatSwitch)
        ProbePlan.P2 -> listOf(Feature.ModeRead, Feature.ConfigRead)
        else -> listOfNotNull(step.feature)
    }
    private sealed interface Attempt {
        data class Reply(val response: PtpResponse, val data: ByteArray) : Attempt
        data object Budget : Attempt
        data object PreUsbTimeout : Attempt
        data object MissingStorage : Attempt
    }
    private companion object {
        val STORAGE_REJECTS = setOf(Rc.PARAMETER_NOT_SUPPORTED, Rc.STORE_NOT_AVAILABLE)
        fun hex(code: Int): String = "0x" + code.toString(16).uppercase().padStart(4, '0')
    }
}
