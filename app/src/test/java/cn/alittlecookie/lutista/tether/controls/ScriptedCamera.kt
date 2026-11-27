package cn.alittlecookie.lutista.tether.controls

import cn.alittlecookie.lutista.tether.config.CameraProperty
import cn.alittlecookie.lutista.tether.config.PropertyCodec
import cn.alittlecookie.lutista.tether.session.PtpControlPort
import cn.alittlecookie.lutista.tether.session.PtpScheduler
import cn.alittlecookie.lutista.tether.session.PtpSessionApi
import cn.alittlecookie.lutista.tether.session.PtpSessionApiImpl
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.Transport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** Scripted fake USB, exercised through the real PtpSessionApiImpl and real single-worker scheduler. */
internal class ScriptedCamera : Transport, PtpControlPort {
    data class Call(val op: PtpOp, val dataOut: ByteArray?, val timeoutMs: Int, val at: Long)
    data class Reply(val payload: ByteArray = byteArrayOf(), val code: Int = 0x2001)
    private class Worker : PtpScheduler.IoWorker {
        override val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor {
            Thread(it, UsbIoThread.THREAD_NAME)
        }.asCoroutineDispatcher()
        override fun shutdown() = dispatcher.close()
    }
    val scheduler = PtpScheduler { Worker() }
    private val api = PtpSessionApiImpl(this)
    val calls = Collections.synchronizedList(mutableListOf<Call>())
    var handler: suspend (Call) -> Reply = { Reply() }
    override var isFatal = false
    override var isOpen = true
    private var active = 0
    var maxActive = 0
    override fun pendingUserOps() = scheduler.pendingUserOps()
    override suspend fun <T> runOp(priority: PtpScheduler.Priority, block: suspend (PtpSessionApi) -> T): T =
        scheduler.run(priority) { block(api) }
    override suspend fun transaction(op: PtpOp, expectedData: Boolean, dataOut: ByteArray?,
        dataSink: ((ByteArray, Int) -> Unit)?, timeoutMs: Int): PtpResponse {
        val call = Call(op, dataOut?.copyOf(), timeoutMs, System.nanoTime() / 1_000_000)
        calls += call
        active++
        maxActive = maxOf(maxActive, active)
        try {
            val reply = handler(call)
            // Split payload to prove the controller has no dependency on USB chunk boundaries.
            reply.payload.asList().chunked(7).forEach { piece -> dataSink?.invoke(piece.toByteArray(), piece.size) }
            return PtpResponse(reply.code, dataBytes = reply.payload.size.toLong())
        } finally { active-- }
    }
    override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> = emptyList()
    override suspend fun close(timeoutMs: Int) { isOpen = false }
    suspend fun dispose() { scheduler.close(); isOpen = false }
}

internal fun fixture(name: String): ByteArray {
    val hex = requireNotNull(ScriptedCamera::class.java.classLoader!!.getResourceAsStream("fixtures/$name.hex"))
        .bufferedReader().use { it.readText().trim() }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
internal fun value(id: Long, value: Long, width: Int = 4): ByteArray =
    ByteBuffer.allocate(8 + width).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(CameraProperty.valueId(id).toInt()); putInt(width)
        if (width == 2) putShort(value.toShort()) else putInt(value.toInt())
    }.array()

internal fun descriptor(id: Long, current: Long, values: List<Long>, width: Int = 4): ByteArray {
    val leafSize = 28 + width + values.size * width
    val leaf = ByteBuffer.allocate(8 + leafSize).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(1); putInt(leafSize); putInt(0); putInt(0x10001); putInt(0); putInt(1)
        putInt(if (width == 2) 4 else 6); putInt(2)
        if (width == 2) putShort(current.toShort()) else putInt(current.toInt())
        putInt(values.size)
        values.forEach { if (width == 2) putShort(it.toShort()) else putInt(it.toInt()) }
    }.array()
    return ByteBuffer.allocate(28 + leaf.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(CameraProperty.valueId(id).toInt()); putInt(20); putInt(20); putInt(0x10001); putInt(0)
        putInt(1); putInt(leaf.size); put(leaf)
    }.array()
}
internal fun jpeg(prefix: Int = 180, width: Int = 640, height: Int = 432): ByteArray = ByteArray(prefix) + byteArrayOf(
    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8,
    (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 1, 1, 0x11, 0,
    0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
