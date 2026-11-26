package cn.alittlecookie.lutista.tether.debug

import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagFrame
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class TetherDiagLoggerTest {
    @Test fun defaultAndDisabledLoggerDoesNotReadClockOrEmitEvidence() {
        assertFalse(TetherDiagLogger().enabled)
        val logger = TetherDiagLogger(false, { error("disabled clock") }, { _, _ -> error("disabled output") })
        allSinkCallbacks(logger)
        logger.deviceCaps(syntheticCaps())
        logger.log("STATE", "private evidence")
        assertTrue(logger.snapshot().isEmpty())
        logger.enabled = true
        assertEquals(BuildConfig.DEBUG, logger.enabled)
        logger.enabled = false
        assertTrue(logger.snapshot().isEmpty())
    }

    @Test fun tagsShareExactlyOneTimestampAndSequencePerRecord() {
        assumeTrue(BuildConfig.DEBUG)
        var clockCalls = 0
        val output = mutableListOf<Pair<String, String>>()
        val logger = TetherDiagLogger(true, { clockCalls++; 700L }, { tag, line -> output += tag to line })
        logger.log("STATE", "isOpen=true")
        logger.log("STATE", "isOpen=false")
        assertEquals(2, clockCalls)
        assertEquals(4, output.size)
        output.chunked(2).forEachIndexed { index, pair ->
            assertEquals(listOf("T01Verify", "T02Diag"), pair.map { it.first })
            assertEquals(pair[0].second, pair[1].second)
            assertTrue(pair[0].second.startsWith("700 STATE seq=${index + 1}; "))
        }
        assertEquals(output.filter { it.first == "T02Diag" }.map { it.second }, logger.snapshot())
    }

    @Test fun capsKeepAllActualFieldsAndUnredactedSyntheticIdentity() {
        assumeTrue(BuildConfig.DEBUG)
        val logger = recordingLogger()
        logger.deviceCaps(syntheticCaps())
        val line = logger.snapshot().single()
        listOf(
            "vendorExtensionId=0x1C", "manufacturer=Synthetic Maker", "model=Mock Camera",
            "deviceVersion=test-fw", "serialNumber=synthetic-serial-0001",
            "operationsSupported=[0x1001, 0x9402]", "eventsSupported=[0x4006, 0xC108]",
            "devicePropertiesSupported=[0x5001]", "imageFormats=[0x3801]",
            "bcdUsb=0x310", "bulkMaxPacketSize=1024", "usbVendorId=0x4DA", "usbProductId=0xFFFF",
            "preflightFailure=PARSE_ERROR", "source=open_return",
        ).forEach { assertTrue("missing actual field: $it", line.contains(it)) }
    }

    @Test fun sinkFieldsRemainExactIncludingWriteSequenceAndFullException() {
        assumeTrue(BuildConfig.DEBUG)
        val logger = recordingLogger()
        allSinkCallbacks(logger)
        val lines = logger.snapshot()
        assertTrue(lines.any { it.contains("request=65000; read=0; maxPacketSize=1000; endpointAddress=0x81; isZlp=true; zlpCount=1; previousWasZlp=false; isTimeout=false") })
        assertTrue(lines.any { it.contains("read=17;") && it.contains("previousWasZlp=true") })
        assertTrue(lines.any { it.contains("read=-1;") && it.contains("isTimeout=true") })
        assertTrue(lines.any { it.contains("tail=999; maxPacketSize=1000; source=sink") })
        assertTrue(lines.any { it.contains("frameIndex=8; writeSequence=43; offset=19; length=7; written=3; isZlp=false") })
        assertTrue(lines.any { it.contains("frameIndex=9; type=2; code=0x9403; txid=0xFFFFFFFF; length=27; source=sink_actual_bytes") })
        val tx = lines.single { it.contains(" TX ") }
        assertTrue(tx.contains("op=0x9403; txid=0xFFFFFFFF; rc=null; dataBytes=17; elapsedMs=9; isFatal=true; isPreflight=true"))
        assertTrue(tx.contains("exceptionType=SyntheticFailure; exceptionMessage=$LONG_EXCEPTION; source=sink"))
    }

    @Test fun snapshotIsBoundedCopiedAndClearedOnDisable() {
        assumeTrue(BuildConfig.DEBUG)
        val logger = recordingLogger()
        logger.log("STATE", "first")
        val retained = logger.snapshot()
        repeat(512) { logger.log("STATE", "record=$it") }
        assertEquals(1, retained.size)
        assertTrue(retained.single().endsWith("first"))
        assertEquals(512, logger.snapshot().size)
        assertTrue(logger.snapshot().first().contains("seq=2;"))
        assertTrue(logger.snapshot().last().contains("seq=513;"))
        logger.enabled = false
        assertTrue(logger.snapshot().isEmpty())
        logger.enabled = true
        logger.onBulkIn(512, 1, 512, 0x81, false, false)
        assertTrue(logger.snapshot().single().contains("zlpCount=0; previousWasZlp=false"))
    }

    @Test fun allEightGroupsGateBothTagsWithoutBypassingMasterSwitch() {
        assumeTrue(BuildConfig.DEBUG)
        var clockCalls = 0
        val output = mutableListOf<Pair<String, String>>()
        val logger = TetherDiagLogger(true, { clockCalls++; 1L }, { tag, line -> output += tag to line })
        assertEquals(setOf("CAPS", "ENDPOINT_STATE", "FILL", "WRITE", "FRAME", "TRANSACTION", "EVENTS", "THREAD_CLAIM"), logger.knownCategories)
        logger.knownCategories.forEach { logger.setCategoryEnabled(it, false) }
        allSinkCallbacks(logger)
        logger.deviceCaps(syntheticCaps())
        listOf("STATE", "EP", "TX_BEGIN", "EXCEPTION", "EVENT", "EVENTS", "DRAIN", "THREAD", "CLAIM", "FAULT", "CUSTOM").forEach { logger.log(it, "hidden") }
        assertTrue(output.isEmpty())
        assertEquals(0, clockCalls)
        logger.setCategoryEnabled("not_a_group", true)
        logger.setCategoryEnabled("FILL", true)
        logger.onFillRejected(13, 512)
        assertEquals(2, output.size)
        assertEquals(output[0].second, output[1].second)
        assertEquals(1, clockCalls)
        logger.enabled = false
        logger.setCategoryEnabled("FILL", true)
        logger.onFillRejected(13, 512)
        assertEquals(2, output.size)
    }

    @Test fun failingFirstTagDoesNotPreventSecondTagOrThrowIntoUsb() {
        assumeTrue(BuildConfig.DEBUG)
        val received = mutableListOf<Pair<String, String>>()
        val logger = TetherDiagLogger(true, { 1L }, { tag, line ->
            received += tag to line
            if (tag == "T01Verify") throw AssertionError("synthetic Timber failure")
        })
        logger.onFillRejected(8, 512)
        assertEquals(2, received.size)
        assertEquals(received[0].second, received[1].second)
        assertEquals(listOf(received[0].second), logger.snapshot())
        TetherDiagLogger(true, { throw AssertionError("clock") }, { _, _ -> fail("clock failed") }).log("TX", "still no escape")
    }

    @Test fun concurrentCallersKeepPairsAdjacentAndSequencesStrictlyIncreasing() {
        assumeTrue(BuildConfig.DEBUG)
        val clock = AtomicLong(0)
        val received = mutableListOf<Pair<String, String>>()
        val logger = TetherDiagLogger(true, { clock.incrementAndGet() }, { tag, line -> received += tag to line })
        val writers = List(4) { writer -> thread { repeat(20) { logger.log("STATE", "writer=$writer; record=$it") } } }
        writers.forEach { it.join(2_000); assertFalse("writer did not finish", it.isAlive) }
        assertEquals(80L, clock.get())
        assertEquals(160, received.size)
        received.chunked(2).forEachIndexed { index, pair ->
            assertEquals("T01Verify", pair[0].first)
            assertEquals("T02Diag", pair[1].first)
            assertEquals(pair[0].second, pair[1].second)
            assertTrue(pair[0].second.startsWith("${index + 1} STATE seq=${index + 1};"))
        }
    }

    private fun recordingLogger() = TetherDiagLogger(true, { 1L }, { _, _ -> })
    private fun allSinkCallbacks(logger: TetherDiagLogger) {
        logger.onBulkIn(65000, 0, 1000, 0x81, true, false)
        logger.onBulkIn(65000, 17, 1000, 0x81, false, false)
        logger.onBulkIn(65000, -1, 1000, 0x81, false, true)
        logger.onFillRejected(999, 1000)
        logger.onFrames(0x9403, listOf(PtpDiagFrame(9, 2, 0x9403, 0xFFFF_FFFFL, 27)))
        logger.onBulkOut(8, 43, 19, 7, 3, false)
        logger.onTransaction(0x9403, 0xFFFF_FFFFL, null, 17, 9, true, true, "SyntheticFailure", LONG_EXCEPTION)
    }

    companion object {
        private val LONG_EXCEPTION = "synthetic ep=0x81 request=65000 timeout=37\n" + "detail".repeat(240)
        internal fun syntheticCaps() = PtpDeviceCaps(
            0x1CL, "Synthetic Maker", "Mock Camera", "test-fw", "synthetic-serial-0001",
            setOf(0x9402L, 0x1001L), setOf(0xC108L, 0x4006L), setOf(0x5001L), setOf(0x3801L),
            0x310, 1024, 0x04DA, 0xFFFF, PreflightFailure.PARSE_ERROR,
        )
    }
}
