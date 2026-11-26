package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.Fixtures
import cn.alittlecookie.lutista.tether.profile.CameraProfileRegistry
import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/** 六份资源均为假机组合；重用已收录 wire payload，不表示整条流程通过真机。 */
internal object ProbeFixtures {
    fun load(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/probe_$name.json")
            ?: error("Missing synthetic probe fixture $name")
        val root = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        assertEquals(name, root.getValue("name").jsonPrimitive.content)
        assertFalse(root.getValue("physicalValidation").jsonPrimitive.boolean)
        return root
    }

    fun caps(root: JsonObject = load("s1Full")): PtpDeviceCaps {
        val row = root.getValue("device").jsonObject
        return DeviceInfoParser.parse(Fixtures.hex("device_info")).copy(
            model = row.getValue("model").jsonPrimitive.content,
            serialNumber = row.getValue("serial").jsonPrimitive.content,
            deviceVersion = row.getValue("firmware").jsonPrimitive.content,
            usbVendorId = number(row.getValue("vid")).toInt(),
            usbProductId = number(row.getValue("pid")).toInt(),
            operationsSupported = row.getValue("ops").jsonArray.map(::number).toSet(),
            devicePropertiesSupported = row.getValue("props").jsonArray.map(::number).toSet(),
            imageFormats = row.getValue("formats").jsonArray.map(::number).toSet(),
        )
    }

    fun driver(root: JsonObject): FakeProbe = FakeProbe(root.getValue("responses").jsonArray.map { node ->
        val row = node.jsonObject
        FakeReply(
            number(row.getValue("op")).toInt(),
            row.getValue("params").jsonArray.map(::number),
            row["rc"]?.let(::number)?.toInt() ?: 0x2001,
            row["payload"]?.let { Fixtures.hex(it.jsonPrimitive.content) }
                ?: row["hex"]?.let { hex(it.jsonPrimitive.content) } ?: byteArrayOf(),
            row["elapsedMs"]?.jsonPrimitive?.long ?: 0L,
            row["failure"]?.jsonPrimitive?.content,
        )
    })

    fun storage(root: JsonObject): List<Long> = root["storageIds"]?.jsonArray?.map(::number) ?: emptyList()
    fun profile(caps: PtpDeviceCaps) = CameraProfileRegistry().resolve(caps.usbVendorId, caps.usbProductId, caps)
    fun number(value: JsonElement): Long = value.jsonPrimitive.content.let {
        if (it.startsWith("0x", true)) it.substring(2).toLong(16) else it.toLong()
    }
    fun hex(text: String): ByteArray = text.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun u32(vararg values: Long): ByteArray = values.flatMap { value ->
        (0..3).map { shift -> (value ushr (shift * 8)).toByte() }
    }.toByteArray()
}

internal data class FakeReply(
    val op: Int,
    val params: List<Long>,
    val code: Int = 0x2001,
    val data: ByteArray = byteArrayOf(),
    val elapsedMs: Long = 0L,
    val failure: String? = null,
    val makeFatal: Boolean = false,
)
internal data class ProbeCall(val op: PtpOp, val timeoutMs: Int)

internal class FakeProbe(replies: List<FakeReply>) {
    private val pendingReplies = java.util.ArrayDeque(replies)
    val calls = mutableListOf<ProbeCall>()
    var now = 0L
    var fatal = false
    val logs = mutableListOf<String>()
    val execute: suspend (PtpOp, Int) -> Pair<PtpResponse, ByteArray> = { op, timeout ->
        calls += ProbeCall(op, timeout)
        val reply = pendingReplies.pollFirst() ?: error("Unexpected probe opcode ${op.code.toString(16)}")
        assertEquals(reply.op, op.code)
        assertEquals(reply.params, op.params.toList())
        now += reply.elapsedMs
        fatal = fatal || reply.makeFatal
        if (reply.failure == "preUsbTimeout") throw ProbeBudgetExceededException("synthetic queue deadline")
        PtpResponse(reply.code, dataBytes = reply.data.size.toLong()) to reply.data
    }
    fun probe() = CapabilityProbe(execute, { fatal }, { now }, { logs += it }, { 1234L })
    fun assertConsumed() = assertEquals("All scripted replies must be consumed", 0, pendingReplies.size)
}
