package cn.alittlecookie.lutista.tether.render

import java.util.ArrayDeque

fun interface PreviewClock {
    fun nowMs(): Long
    companion object {
        val Monotonic = PreviewClock { System.nanoTime() / 1_000_000L }
    }
}

enum class FilterState { Off, On, Degraded }
// The first two values are retained for existing stored/UI consumers, but are never emitted by policy.
enum class DegradeReason { LowProcessedFps, UnsupportedResolution, RenderingFailure }
enum class PreviewFrameAction { Passthrough, Filter, Drop }

/** One consumable notice per enabled filter session, rather than a repeated statistics warning. */
enum class PreviewRenderNotice { FilterFramesDropped }

data class RenderStats(
    val processedFps: Float = 0f,
    val presentedFps: Float = 0f,
    val droppedFrames: Long = 0,
    val addedLatencyP95Ms: Int = 0,
    val filterState: FilterState = FilterState.Off,
    val degradeReason: DegradeReason? = null,
    val targetFps: Int = 15,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
)

/** A rate deadline limits work, never filter availability. Only an actual rendering error degrades. */
class PreviewRenderPolicy(private val clock: PreviewClock = PreviewClock.Monotonic) {
    var enabled = false
        private set
    var targetFps = 15
        private set
    var degradeReason: DegradeReason? = null
        private set
    private var startedAt: Long? = null
    private var nextAttemptAtScaled: Long? = null
    private val processed = ArrayDeque<Long>()

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        degradeReason = null
        resetWindow()
    }

    fun setTargetFps(value: Int) {
        require(value > 0) { "Preview FPS must be positive" }
        if (targetFps == value) return
        targetFps = value
        resetWindow()
    }

    private fun resetWindow() {
        startedAt = null
        nextAttemptAtScaled = null
        processed.clear()
    }

    fun state(hasFilter: Boolean): FilterState = when {
        degradeReason != null -> FilterState.Degraded
        enabled && hasFilter -> FilterState.On
        else -> FilterState.Off
    }

    /** Rate-capped color frames are dropped, never replaced with a visibly different original. */
    fun frameAction(hasFilter: Boolean, sourceWidth: Int, sourceHeight: Int): PreviewFrameAction {
        if (shouldProcess(hasFilter, sourceWidth, sourceHeight)) return PreviewFrameAction.Filter
        return if (state(hasFilter) == FilterState.On) PreviewFrameAction.Drop else PreviewFrameAction.Passthrough
    }

    /** A busy GPU/null output is a skipped frame. A thrown, actual filter error is separate. */
    fun completeFrame(
        action: PreviewFrameAction,
        filteredOutputAvailable: Boolean,
        renderingFailed: Boolean = false,
    ): PreviewFrameAction {
        if (enabled && action == PreviewFrameAction.Filter && renderingFailed) {
            degradeReason = DegradeReason.RenderingFailure
        }
        if (action == PreviewFrameAction.Filter && filteredOutputAvailable) onProcessed()
        return when {
            action == PreviewFrameAction.Passthrough -> PreviewFrameAction.Passthrough
            action == PreviewFrameAction.Filter && filteredOutputAvailable -> PreviewFrameAction.Filter
            degradeReason != null -> PreviewFrameAction.Passthrough
            else -> PreviewFrameAction.Drop
        }
    }

    @Suppress("UNUSED_PARAMETER") // Actual dimensions remain part of the policy contract, not an enable gate.
    fun shouldProcess(hasFilter: Boolean, sourceWidth: Int, sourceHeight: Int): Boolean {
        if (!enabled || degradeReason != null) return false
        if (!hasFilter) { resetWindow(); return false }
        val now = clock.nowMs()
        if (startedAt == null) startedAt = now
        // Keep a phase deadline, rather than restarting a rounded delay per frame.
        // Source/clock timestamps have millisecond precision: tolerate <1ms quantization only.
        val scaledNow = (now - checkNotNull(startedAt)) * targetFps
        val deadline = nextAttemptAtScaled
        if (deadline != null && scaledNow + targetFps - 1 < deadline) return false
        nextAttemptAtScaled = if (deadline == null || scaledNow >= deadline + 1_000L) {
            scaledNow + 1_000L // no catch-up burst after a stalled source or busy GPU
        } else deadline + 1_000L
        return true
    }

    fun onProcessed() {
        val now = clock.nowMs()
        trim(now)
        processed.addLast(now)
    }

    fun processedFps(): Float {
        val now = clock.nowMs()
        trim(now)
        val elapsed = startedAt?.let { (now - it).coerceIn(1L, 1_000L) } ?: 1_000L
        return processed.size * 1_000f / elapsed
    }

    /** Statistics maintenance has no authority to turn a filter off. */
    fun evaluate() { trim(clock.nowMs()) }

    private fun trim(now: Long) {
        while (processed.isNotEmpty() && processed.peekFirst() <= now - 1_000L) processed.removeFirst()
    }
}

/** A filter session changes only on an enable transition; stale/closed-session drops are ignored. */
internal class PreviewFilterDropGate {
    private var enabled = false
    private var session = 0L
    private var warned = false

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        session++
        warned = false
    }

    @Synchronized fun currentSession(): Long? = session.takeIf { enabled }

    @Synchronized fun isCurrent(value: Long): Boolean = enabled && value == session

    @Synchronized fun onDropped(value: Long?): Boolean {
        if (value == null || !enabled || value != session || warned) return false
        warned = true
        return true
    }
}

/** Validates actual JPEG bounds before allocating pixel memory; input metadata is only a hint. */
data class JpegDecodePlan(val sampleSize: Int, val width: Int, val height: Int) {
    companion object {
        const val MAX_JPEG_BYTES = 8 * 1024 * 1024
        const val MAX_DECODED_EDGE = 1_600
        const val MAX_DECODED_PIXELS = 1_800_000L

        fun forBounds(width: Int, height: Int): JpegDecodePlan? {
            if (width !in 1..32_768 || height !in 1..32_768 ||
                width.toLong() * height > 100_000_000L
            ) return null
            var sample = 1
            fun dimension(value: Int) = (value + sample - 1) / sample
            while (dimension(width) > MAX_DECODED_EDGE || dimension(height) > MAX_DECODED_EDGE ||
                dimension(width).toLong() * dimension(height) > MAX_DECODED_PIXELS
            ) sample *= 2
            return JpegDecodePlan(sample, dimension(width), dimension(height))
        }
    }
}
