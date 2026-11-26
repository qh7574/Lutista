package cn.alittlecookie.lutista.tether.debug

import android.os.SystemClock
import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagFrame
import cn.alittlecookie.lutista.tether.transport.PtpDiagSink
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in local evidence. Serial/model/firmware and exception text are recorded without redaction. */
class TetherDiagLogger internal constructor(
    enabled: Boolean,
    private val now: () -> Long,
    private val output: (String, String) -> Unit,
) : PtpDiagSink {
    constructor(enabled: Boolean = false) : this(
        enabled,
        { SystemClock.elapsedRealtime() },
        { tag, line -> Timber.tag(tag).d(line) },
    )

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var sequence = 0L
    private val disabledCategories = mutableSetOf<String>()

    /** Eight stable UI groups; the returned set cannot mutate logger state. */
    val knownCategories: Set<String> get() = if (BuildConfig.DEBUG) CATEGORIES.toSet() else emptySet()

    fun setCategoryEnabled(category: String, value: Boolean) {
        if (!BuildConfig.DEBUG || category !in CATEGORIES) return
        synchronized(lock) {
            if (value) disabledCategories.remove(category) else disabledCategories.add(category)
        }
    }
    private val zlpCount = AtomicLong()
    private val previousWasZlp = AtomicBoolean()
    @Volatile private var requestedEnabled = BuildConfig.DEBUG && enabled

    var enabled: Boolean
        get() = BuildConfig.DEBUG && requestedEnabled
        set(value) {
            synchronized(lock) {
                requestedEnabled = BuildConfig.DEBUG && value
                if (!requestedEnabled) {
                    lines.clear()
                    zlpCount.set(0)
                    previousWasZlp.set(false)
                }
            }
        }

    /** Both tags receive exactly the same string, including the one monotonic timestamp. */
    fun log(category: String, message: String) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled || categoryGroup(category) in disabledCategories) return
            // Logging must never replace a USB response or exception, even if a Timber tree fails.
            runCatching {
                val line = "${now()} $category seq=${++sequence}; $message"
                if (lines.size == MAX_SNAPSHOT_LINES) lines.removeFirst()
                lines.addLast(line)
                runCatching { output(TAG_VERIFY, line) }
                runCatching { output(TAG_DIAG, line) }
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        if (enabled) lines.toList() else emptyList()
    }

    fun deviceCaps(caps: PtpDeviceCaps) {
        if (!enabled) return
        log(
            "CAPS",
            "vendorExtensionId=${hex(caps.vendorExtensionId)}; manufacturer=${caps.manufacturer}; " +
                "model=${caps.model}; deviceVersion=${caps.deviceVersion}; serialNumber=${caps.serialNumber}; " +
                "operationsSupported=${hexSet(caps.operationsSupported)}; eventsSupported=${hexSet(caps.eventsSupported)}; " +
                "devicePropertiesSupported=${hexSet(caps.devicePropertiesSupported)}; imageFormats=${hexSet(caps.imageFormats)}; " +
                "bcdUsb=${hex(caps.bcdUsb.toLong())}; bulkMaxPacketSize=${caps.bulkMaxPacketSize}; " +
                "usbVendorId=${hex(caps.usbVendorId.toLong())}; usbProductId=${hex(caps.usbProductId.toLong())}; " +
                "preflightFailure=${caps.preflightFailure}; source=open_return",
        )
    }

    override fun onBulkIn(
        request: Int,
        read: Int,
        maxPacketSize: Int,
        endpointAddress: Int,
        isZlp: Boolean,
        isTimeout: Boolean,
    ) {
        if (!enabled) return
        val afterZlp = previousWasZlp.getAndSet(isZlp)
        val count = if (isZlp) zlpCount.incrementAndGet() else zlpCount.get()
        log(
            "FILL",
            "request=$request; read=$read; maxPacketSize=$maxPacketSize; endpointAddress=${hex(endpointAddress.toLong())}; " +
                "isZlp=$isZlp; zlpCount=$count; previousWasZlp=$afterZlp; isTimeout=$isTimeout; source=sink",
        )
    }

    override fun onFillRejected(tail: Int, maxPacketSize: Int) {
        if (!enabled) return
        log("FILL_REJECTED", "tail=$tail; maxPacketSize=$maxPacketSize; source=sink")
    }

    override fun onFrames(opcode: Int, frames: List<PtpDiagFrame>) {
        if (!enabled) return
        frames.forEach { frame ->
            log(
                "FRAME",
                "opcode=${hex(opcode.toLong())}; frameIndex=${frame.frameIndex}; type=${frame.type}; " +
                    "code=${hex(frame.code.toLong())}; txid=${hex(frame.transactionId)}; length=${frame.length}; source=sink_actual_bytes",
            )
        }
    }

    override fun onBulkOut(
        frameIndex: Int,
        writeSequence: Int,
        offset: Int,
        length: Int,
        written: Int,
        isZlp: Boolean,
    ) {
        if (!enabled) return
        log(
            "WRITE",
            "frameIndex=$frameIndex; writeSequence=$writeSequence; offset=$offset; length=$length; " +
                "written=$written; isZlp=$isZlp; source=sink",
        )
    }

    override fun onTransaction(
        opcode: Int,
        transactionId: Long,
        responseCode: Int?,
        dataBytes: Long,
        elapsedMs: Long,
        isFatal: Boolean,
        isPreflight: Boolean,
        exceptionType: String?,
        exceptionMessage: String?,
    ) {
        if (!enabled) return
        log(
            "TX",
            "op=${hex(opcode.toLong())}; txid=${hex(transactionId)}; rc=${responseCode?.let { hex(it.toLong()) }}; " +
                "dataBytes=$dataBytes; elapsedMs=$elapsedMs; isFatal=$isFatal; isPreflight=$isPreflight; " +
                "exceptionType=$exceptionType; exceptionMessage=$exceptionMessage; source=sink",
        )
    }

    private fun categoryGroup(category: String): String = when (category) {
        "CAPS" -> "CAPS"
        "EP", "STATE" -> "ENDPOINT_STATE"
        "FILL", "FILL_REJECTED" -> "FILL"
        "WRITE" -> "WRITE"
        "FRAME" -> "FRAME"
        "EVENT", "EVENTS", "DRAIN" -> "EVENTS"
        "THREAD", "CLAIM", "FAULT" -> "THREAD_CLAIM"
        else -> "TRANSACTION"
    }

    private fun hexSet(values: Set<Long>): String = values.sorted().joinToString(prefix = "[", postfix = "]") { hex(it) }
    private fun hex(value: Long): String = "0x" + value.toString(16).uppercase()

    companion object {
        const val TAG_VERIFY = "T01Verify"
        const val TAG_DIAG = "T02Diag"
        private val CATEGORIES = setOf("CAPS", "ENDPOINT_STATE", "FILL", "WRITE", "FRAME", "TRANSACTION", "EVENTS", "THREAD_CLAIM")
        private const val MAX_SNAPSHOT_LINES = 512
    }
}
