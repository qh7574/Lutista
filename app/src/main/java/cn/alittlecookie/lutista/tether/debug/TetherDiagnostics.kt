package cn.alittlecookie.lutista.tether.debug

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpTransport
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.Transport
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import cn.alittlecookie.lutista.tether.transport.UsbPtpDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Explicit debug actions, always called on the session's ptp-io owner thread. Switches alone never
 * execute commands. These actions emit controlled-experiment evidence, never a hardware PASS.
 * The allowValueWrite preference does not authorize or execute a shutter/property write here.
 */
class TetherDiagnostics(
    private val config: TetherDebugConfig,
    private val logger: TetherDiagLogger,
) {
    enum class FaultStatus { Disabled, Blocked, TargetObserved }
    data class FaultResult(
        val fault: String,
        val status: FaultStatus,
        val detail: String,
        val exceptionType: String? = null,
        val exceptionMessage: String? = null,
    )

    suspend fun syncConfig(): TetherDebugConfig.Snapshot {
        if (!BuildConfig.DEBUG) {
            logger.enabled = false
            return TetherDebugConfig.Snapshot()
        }
        return config.snapshot().also { logger.enabled = it.enabled }
    }

    fun snapshot(): List<String> = if (BuildConfig.DEBUG) logger.snapshot() else emptyList()

    /** Three short, serialized drains for TV-05a. No capture or live-view command is sent. */
    suspend fun drainEvents(
        transport: Transport,
        diagnostics: PtpDiagnostics,
        rounds: Int = 3,
    ): List<PtpEvent> {
        if (!BuildConfig.DEBUG || !syncConfig().enabled) return emptyList()
        if (!UsbIoThread.isIoThread() || !diagnostics.isOpen) {
            logger.log("DRAIN", "status=BLOCKED; reason=requires_open_transport_on_owner_thread")
            return emptyList()
        }
        val result = ArrayList<PtpEvent>()
        repeat(rounds.coerceIn(1, 3)) { round ->
            val events = transport.drainEvents(timeoutMs = 2)
            result += events
            logger.log(
                "DRAIN",
                "round=${round + 1}; count=${events.size}; discardedEventContainers=${diagnostics.discardedEventContainers}; " +
                    "isOpen=${diagnostics.isOpen}; isFatal=${diagnostics.isFatal}",
            )
            events.forEach { event ->
                logger.log(
                    "EVENT",
                    "code=${hex(event.code)}; params=${event.params.joinToString(prefix = "[", postfix = "]") { hexU32(it) }}; " +
                        "rawBytes=${event.raw.size}; rawKind=normalized_container; source=controlled_drain",
                )
            }
        }
        return result
    }

    /** reserveTail compacts/grows; a small public rxCapacity cannot exercise the private rejection. */
    suspend fun smallRxStatus(): FaultResult {
        if (!BuildConfig.DEBUG) return disabled("small_rx")
        val state = syncConfig()
        if (!state.enabled || !state.faultSmallRx) return disabled("small_rx")
        return report(
            "small_rx",
            FaultStatus.Blocked,
            "reserveTail() compacts/doubles capacity; small rxCapacity does not reach request<maxPacketSize. " +
                "T01 exposes observation only, no public tail control; no reflection/hidden API used.",
        )
    }

    /**
     * Real production reentry: the outer read-only DATA IN invokes a nested transaction on the SAME
     * owner thread. The production busy guard must reject it before a second USB command is written.
     * runBlocking is restricted to this explicit controlled fixture because dataSink is synchronous.
     */
    suspend fun runConcurrentFixture(transport: Transport, diagnostics: PtpDiagnostics): FaultResult {
        if (!BuildConfig.DEBUG) return disabled("concurrent")
        val state = syncConfig()
        if (!state.enabled || !state.faultConcurrent) return disabled("concurrent")
        precondition("concurrent", diagnostics)?.let { return it }
        val violationsBefore = UsbIoThread.violationCount()
        var reentered = false
        var nestedError: Throwable? = null
        var outerError: Throwable? = null
        try {
            transport.transaction(
                PtpOp(PtpConstants.Op.GET_DEVICE_INFO),
                expectedData = true,
                dataSink = { _, _ ->
                    if (!reentered) {
                        reentered = true
                        try {
                            runBlocking {
                                transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO), expectedData = true)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            nestedError = error
                        }
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            outerError = error
        }
        val violationsAfter = UsbIoThread.violationCount()
        val hit = reentered && nestedError is IllegalStateException && nestedError?.message == BUSY_MESSAGE &&
            outerError == null && !diagnostics.isFatal && violationsAfter == violationsBefore
        return report(
            "concurrent",
            if (hit) FaultStatus.TargetObserved else FaultStatus.Blocked,
            "controlled_experiment=true; reentered=$reentered; outerException=${outerError?.javaClass?.simpleName}; " +
                "outerMessage=${outerError?.message}; isOpen=${diagnostics.isOpen}; isFatal=${diagnostics.isFatal}; " +
                "violationCountBefore=$violationsBefore; violationCountAfter=$violationsAfter; " +
                "target=production_transaction_busy_guard; evidenceScope=controlled_reentry",
            nestedError,
        )
    }

    /**
     * Dedicated disposable connection only, AFTER the active session is disconnected. If disabled or
     * preconditions fail the connection remains caller-owned/untouched. Otherwise this consumes and
     * closes it. Natural claim=true is BLOCKED for the claim-failure target; its result is not changed.
     */
    suspend fun runClaimFailureFixture(
        device: UsbDevice,
        ownedConnection: UsbDeviceConnection,
        activeDiagnostics: PtpDiagnostics,
    ): FaultResult {
        if (!BuildConfig.DEBUG) return disabled("claim_fail")
        val state = syncConfig()
        if (!state.enabled || !state.faultClaimFail) return disabled("claim_fail")
        if (!UsbIoThread.isIoThread() || activeDiagnostics.isOpen) {
            return report("claim_fail", FaultStatus.Blocked, "requires_disconnected_session_and_owner_thread; connectionUntouched=true")
        }
        val fixture = PtpUsbTransport(diag = logger)
        val truth = UsbPtpDiagnostics(fixture)
        var observed: Throwable? = null
        var opened = false
        var cleanupError: Throwable? = null
        try {
            fixture.open(device, ownedConnection)
            opened = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            observed = error
        } finally {
            try {
                fixture.close()
            } catch (error: Throwable) {
                cleanupError = error
            } finally {
                // claim=false leaves no transport connection; close the caller-supplied raw handle.
                try {
                    ownedConnection.close()
                } catch (error: Throwable) {
                    cleanupError = error
                }
            }
        }
        val hit = observed?.message == PtpDiagTransport.CLAIM_FAILED_MESSAGE &&
            !truth.isOpen && !truth.isFatal && cleanupError == null
        return report(
            "claim_fail",
            if (hit) FaultStatus.TargetObserved else FaultStatus.Blocked,
            "controlled_experiment=true; opened=$opened; actualClaimFailure=$hit; " +
                "isOpen=${truth.isOpen}; isFatal=${truth.isFatal}; cleanup=production_close_and_raw_close_called; " +
                "releaseResult=not_observed; cleanupException=${cleanupError?.javaClass?.simpleName}; " +
                "cleanupMessage=${cleanupError?.message}; evidenceScope=natural_claim_result_on_dedicated_connection",
            observed,
        )
    }

    /** One explicitly requested read-only 1 ms transaction, then observe production fatal fail-fast. */
    suspend fun forceTimeout(transport: Transport, diagnostics: PtpDiagnostics): FaultResult {
        if (!BuildConfig.DEBUG || !syncConfig().enabled) return disabled("timeout")
        precondition("timeout", diagnostics)?.let { return it }
        var observed: Throwable? = null
        var followup: Throwable? = null
        try {
            transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO), expectedData = true, timeoutMs = 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            observed = error
        }
        if (observed is PtpTimeoutException && diagnostics.isFatal) {
            try {
                transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO), expectedData = true, timeoutMs = 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                followup = error
            }
        }
        val hit = observed is PtpTimeoutException && diagnostics.isFatal && followup?.message == FATAL_MESSAGE
        return report(
            "timeout",
            if (hit) FaultStatus.TargetObserved else FaultStatus.Blocked,
            "controlled_experiment=true; isOpen=${diagnostics.isOpen}; isFatal=${diagnostics.isFatal}; " +
                "followupException=${followup?.javaClass?.simpleName}; followupMessage=${followup?.message}; " +
                "evidenceScope=read_only_1ms_and_fatal_fail_fast",
            observed,
        )
    }

    // Compatibility for callers that already own a production PtpTransport; no opening API leaks out.
    suspend fun drainEvents(transport: PtpTransport, diagnostics: PtpDiagnostics, rounds: Int = 3): List<PtpEvent> =
        drainEvents(narrow(transport), diagnostics, rounds)
    suspend fun runConcurrentFixture(transport: PtpTransport, diagnostics: PtpDiagnostics): FaultResult =
        runConcurrentFixture(narrow(transport), diagnostics)
    suspend fun forceTimeout(transport: PtpTransport, diagnostics: PtpDiagnostics): FaultResult =
        forceTimeout(narrow(transport), diagnostics)

    private fun narrow(delegate: PtpTransport): Transport = object : Transport {
        override suspend fun transaction(op: PtpOp, expectedData: Boolean, dataOut: ByteArray?, dataSink: ((ByteArray, Int) -> Unit)?, timeoutMs: Int): PtpResponse =
            delegate.transaction(op, expectedData, dataOut, dataSink, timeoutMs)
        override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> = delegate.drainEvents(timeoutMs)
        override suspend fun close(timeoutMs: Int) = delegate.close(timeoutMs)
    }

    private fun precondition(fault: String, diagnostics: PtpDiagnostics): FaultResult? =
        if (!UsbIoThread.isIoThread() || !diagnostics.isOpen || diagnostics.isFatal) {
            report(
                fault,
                FaultStatus.Blocked,
                "requires_open_nonfatal_transport_on_owner_thread; isOpen=${diagnostics.isOpen}; isFatal=${diagnostics.isFatal}",
            )
        } else null

    private fun disabled(fault: String) = FaultResult(fault, FaultStatus.Disabled, "BuildConfig.DEBUG && tether_debug_enabled && required_switch must be true")

    private fun report(fault: String, status: FaultStatus, detail: String, error: Throwable? = null): FaultResult {
        if (BuildConfig.DEBUG) logger.log(
            "FAULT",
            "fault=$fault; status=$status; $detail; exceptionType=${error?.javaClass?.simpleName}; exceptionMessage=${error?.message}",
        )
        return FaultResult(fault, status, detail, error?.javaClass?.simpleName, error?.message)
    }

    private fun hex(value: Int): String = "0x" + value.toString(16).uppercase()
    private fun hexU32(value: Long): String = "0x" + (value and 0xFFFF_FFFFL).toString(16).uppercase()

    companion object {
        internal const val BUSY_MESSAGE = "同一个 UsbDeviceConnection 上出现了并发 bulk 收发（约束 3 禁止）"
        private const val FATAL_MESSAGE = "传输层已置 fatal（上一次超时/NACK），必须拔插相机后重建连接"
    }
}
