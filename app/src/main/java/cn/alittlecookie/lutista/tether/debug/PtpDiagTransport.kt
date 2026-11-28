package cn.alittlecookie.lutista.tether.debug

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.PtpTransport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import java.util.concurrent.atomic.AtomicInteger

/**
 * L1 observation only: the delegate receives identical object/callback references and timeout values.
 * L2 FILL/WRITE/FRAME truth comes solely from the logger passed to PtpUsbTransport's diag argument.
 */
class PtpDiagTransport internal constructor(
    private val delegate: PtpTransport,
    private val diagnostics: PtpDiagnostics,
    private val logger: TetherDiagLogger,
    private val now: () -> Long,
) : PtpTransport {
    constructor(delegate: PtpTransport, diagnostics: PtpDiagnostics, logger: TetherDiagLogger) :
        this(delegate, diagnostics, logger, { SystemClock.elapsedRealtime() })

    /**
     * 纯转发：诊断包装层不得改变传输层自报的恢复状态。
     *
     * 真机 logs_1795826645352（2026-11-28 08:43）里传输层已经判定 `cameraSilent`，
     * 会话层日志却是 `connect failure=… cameraSilent=false` —— 因为这一层没有转发，
     * `Transport` 的默认 `false` 把上层的判定吞掉了：用户看到的是「拔数据线」而不是
     * 「关相机电源」，`openRecoveryMs` 的连接预算退款同样一直拿到 0。
     */
    override val openRecoveryMs: Long get() = delegate.openRecoveryMs

    /** 见 [openRecoveryMs]：静默判定必须原样穿过包装层。 */
    override val cameraSilent: Boolean get() = delegate.cameraSilent

    private val unknownEventCount = AtomicInteger()
    private val noiseEventCount = AtomicInteger()

    override suspend fun open(device: UsbDevice, connection: UsbDeviceConnection): PtpDeviceCaps {
        observe {
            thread("open.before")
            endpoints(device)
            state("open.before")
        }
        try {
            val caps = delegate.open(device, connection)
            observe {
                // Parsing happens after the preflight transaction. This returned value is its truth.
                logger.deviceCaps(caps)
                logger.log("CLAIM", "result=true; interfaceIndex=0; source=open_return")
            }
            return caps
        } catch (error: Throwable) {
            observe {
                val claim = if (error is PtpException && error.message == CLAIM_FAILED_MESSAGE) "false" else "not_observed"
                logger.log(
                    "CLAIM",
                    "result=$claim; interfaceIndex=0; source=open_exception; exceptionType=${error.javaClass.simpleName}; " +
                        "exceptionMessage=${error.message}",
                )
            }
            throw error
        } finally {
            observe { state("open.after"); thread("open.after") }
        }
    }

    override suspend fun close(timeoutMs: Int) {
        observe { state("close.before"); thread("close.before") }
        try {
            delegate.close(timeoutMs)
        } catch (error: Throwable) {
            observe { exception("close", error) }
            throw error
        } finally {
            observe { state("close.after"); thread("close.after") }
        }
    }

    override suspend fun transaction(
        op: PtpOp,
        expectedData: Boolean,
        dataOut: ByteArray?,
        dataSink: ((ByteArray, Int) -> Unit)?,
        timeoutMs: Int,
    ): PtpResponse {
        val started = timestamp()
        observe {
            state("transaction.before")
            thread("transaction.before")
            logger.log("TX_BEGIN", "op=${hex(op.code)}; expectedData=$expectedData; dataOutBytes=${dataOut?.size ?: 0}; timeoutMs=$timeoutMs")
        }
        try {
            val response = delegate.transaction(op, expectedData, dataOut, dataSink, timeoutMs)
            observe {
                logger.log(
                    "TX",
                    "op=${hex(op.code)}; rc=${hex(response.code)}; dataBytes=${response.dataBytes}; " +
                        "elapsedMs=${elapsed(started)}; source=decorator",
                )
            }
            return response
        } catch (error: Throwable) {
            observe {
                logger.log(
                    "TX",
                    "op=${hex(op.code)}; rc=null; dataBytes=unavailable; elapsedMs=${elapsed(started)}; " +
                        "exceptionType=${error.javaClass.simpleName}; exceptionMessage=${error.message}; source=decorator",
                )
            }
            throw error
        } finally {
            observe { state("transaction.after"); thread("transaction.after") }
        }
    }

    override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> {
        observe { state("drain.before"); thread("drain.before") }
        try {
            val events = delegate.drainEvents(timeoutMs)
            observe {
                events.forEach { event ->
                    val kind = when (event.code) {
                        PtpConstants.Event.OBJECT_ADDED -> "object_added"
                        PtpConstants.Event.NOISE_STATE_CHANGED,
                        PtpConstants.Event.NOISE_CAPACITY_CHANGED,
                        PtpConstants.Event.DEVICE_PROP_CHANGED -> {
                            noiseEventCount.incrementAndGet()
                            "noise"
                        }
                        else -> {
                            unknownEventCount.incrementAndGet()
                            "unknown"
                        }
                    }
                    logger.log(
                        "EVENT",
                        "code=${hex(event.code)}; params=${event.params.joinToString(prefix = "[", postfix = "]") { hexU32(it) }}; " +
                            "kind=$kind; rawBytes=${event.raw.size}; rawKind=normalized_container; source=drainEvents",
                    )
                }
                logger.log(
                    "EVENTS",
                    "count=${events.size}; discardedEventContainers=${diagnostics.discardedEventContainers}; " +
                        "unknownEventCount=${unknownEventCount.get()}; noiseEventCount=${noiseEventCount.get()}; counterScope=decorated_drain",
                )
            }
            return events
        } catch (error: Throwable) {
            observe { exception("drainEvents", error) }
            throw error
        } finally {
            observe { state("drain.after"); thread("drain.after") }
        }
    }

    private fun endpoints(device: UsbDevice) {
        if (device.interfaceCount <= 0) {
            logger.log("EP", "interfaceIndex=0; present=false; source=usb_descriptor")
            return
        }
        val intf = device.getInterface(0)
        for (index in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(index)
            logger.log(
                "EP",
                "interfaceIndex=0; endpointIndex=$index; address=${hex(endpoint.address)}; type=${endpoint.type}; " +
                    "direction=${hex(endpoint.direction)}; maxPacketSize=${endpoint.maxPacketSize}; source=usb_descriptor",
            )
        }
    }

    private fun state(phase: String) {
        logger.log("STATE", "phase=$phase; isOpen=${diagnostics.isOpen}; isFatal=${diagnostics.isFatal}")
    }

    private fun thread(phase: String) {
        logger.log(
            "THREAD",
            "phase=$phase; name=${Thread.currentThread().name}; isIoThread=${UsbIoThread.isIoThread()}; violationCount=${UsbIoThread.violationCount()}",
        )
    }

    private fun exception(phase: String, error: Throwable) {
        logger.log("EXCEPTION", "phase=$phase; exceptionType=${error.javaClass.simpleName}; exceptionMessage=${error.message}")
    }

    private fun timestamp(): Long? = if (BuildConfig.DEBUG && logger.enabled) runCatching { now() }.getOrNull() else null
    private fun elapsed(started: Long?): String = started?.let { (now() - it).toString() } ?: "unavailable"
    private inline fun observe(block: () -> Unit) {
        if (BuildConfig.DEBUG && logger.enabled) runCatching(block)
    }
    private fun hex(value: Int): String = "0x" + value.toString(16).uppercase()
    private fun hexU32(value: Long): String = "0x" + (value and 0xFFFF_FFFFL).toString(16).uppercase()

    companion object {
        internal const val CLAIM_FAILED_MESSAGE = "claimInterface(0) 失败：设备可能被系统 MTP/PTP 进程占用"
    }
}
