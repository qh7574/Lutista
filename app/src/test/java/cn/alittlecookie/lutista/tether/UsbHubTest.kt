package cn.alittlecookie.lutista.tether.session

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.IdentityHashMap

class UsbHubTest {
    @Test fun enumerateFiltersVidSortsAndChecksPermissionPerLiveDevice() {
        val h = Harness()
        val first = device("/synthetic/a", 1)
        val second = device("/synthetic/b", 2)
        val unrelated = device("/synthetic/foreign", 3, vendor = 0xFFFF)
        h.attach(second, first, unrelated)
        h.granted += first.deviceName
        val hub = h.hub()
        try {
            assertEquals(listOf(first, second), hub.enumerate())
            assertEquals(listOf(first, second), hub.deviceFlow.value)
            assertTrue(hub.permissionGranted.value)
            assertTrue(hub.hasPermission(first))
            assertFalse(hub.hasPermission(second))
            assertFalse(hub.hasPermission(unrelated))
            assertFalse(hub.hasPermission(device(first.deviceName, 99)))
            h.granted.clear()
            assertFalse(hub.hasPermission(first))
            assertFalse(hub.permissionGranted.value)
        } finally { hub.close() }
        assertTrue(hub.enumerate().isEmpty())
        assertTrue(hub.deviceFlow.value.isEmpty())
        assertFalse(hub.hasPermission(first))
    }

    @Test fun systemAndCustomReceiversUseSeparateFlagsAndUniqueMutableExplicitTokens() = runBlocking {
        val h = Harness(33)
        val a = device("/synthetic/a", 1)
        val b = device("/synthetic/b", 2)
        h.attach(a, b)
        val hub = h.hub()
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(a) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(b) }
            val registrations = h.platform.registered
            assertEquals(setOf(UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED), registrations[0].actions.toSet())
            assertEquals(Context.RECEIVER_EXPORTED, registrations[0].flags)
            assertEquals(Context.RECEIVER_NOT_EXPORTED, registrations[1].flags)
            assertEquals(Context.RECEIVER_NOT_EXPORTED, registrations[2].flags)
            val specs = h.platform.specs
            assertEquals(2, specs.size)
            specs.forEach { spec ->
                assertEquals(PACKAGE_NAME, spec.packageName)
                assertTrue(spec.action.startsWith("$PACKAGE_NAME.tether.USB_PERMISSION."))
                assertTrue(spec.nonce.isNotBlank())
                assertEquals(PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT, spec.flags)
                assertEquals(0, spec.flags and PendingIntent.FLAG_IMMUTABLE)
            }
            assertNotEquals(specs[0].action, specs[1].action)
            assertNotEquals(specs[0].nonce, specs[1].nonce)
            assertNotEquals(specs[0].requestCode, specs[1].requestCode)
            h.granted += a.deviceName
            h.platform.deliverPermission(0, a)
            assertTrue(withTimeout(500) { first.await() })
            h.platform.deliverPermission(1, b, frameworkGranted = false)
            assertFalse(withTimeout(500) { second.await() })
            assertEquals(2, h.permissionRequests.size)
            assertSame(a, h.permissionRequests[0].first)
            assertSame(h.platform.tokens[0], h.permissionRequests[0].second)
        } finally { hub.close() }
    }

    @Test fun legacyReceiversUseLegacyRegistrationWithoutUnsupportedFlags() = runBlocking {
        val h = Harness(29)
        val d = device("/synthetic/legacy", 1)
        h.attach(d)
        val hub = h.hub()
        val request = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(d) }
        assertEquals(listOf<Int?>(null, null), h.platform.registered.map { it.flags })
        hub.close()
        assertFalse(withTimeout(500) { request.await() })
    }

    @Test fun permissionReplyRejectsWrongActionPackageNonceAndDeviceBeforeManagerTruth() = runBlocking {
        val h = Harness()
        val d = device("/synthetic/a", 1)
        h.attach(d)
        val hub = h.hub()
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(d) }
            h.platform.deliverPermission(0, d, action = "forged.action")
            h.platform.deliverPermission(0, d, packageName = "other.app")
            h.platform.deliverPermission(0, d, nonce = "forged nonce")
            h.platform.deliverPermission(0, device(d.deviceName, 99))
            assertFalse(request.isCompleted)
            assertTrue(h.platform.unregistered.isEmpty())
            // Even a valid mutable intent claiming true cannot overrule UsbManager false.
            h.platform.deliverPermission(0, d)
            assertFalse(withTimeout(500) { request.await() })
            assertFalse(hub.hasPermission(d))
            assertFalse(hub.permissionGranted.value)
            assertTrue(h.platform.unregistered.contains(h.platform.registered[1].receiver))
            verify(exactly = 1) { h.platform.tokens[0].cancel() }
        } finally { hub.close() }
    }

    @Test fun grantedReplyNeedsFrameworkAndManagerAndDetachedDeviceCanNeverGrant() = runBlocking {
        val h = Harness()
        val d = device("/synthetic/a", 1)
        h.attach(d)
        val hub = h.hub()
        try {
            val denied = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(d) }
            h.granted += d.deviceName
            h.platform.deliverPermission(0, d, frameworkGranted = false)
            assertFalse(withTimeout(500) { denied.await() })
            assertTrue(hub.hasPermission(d))
            assertTrue(hub.requestPermission(d))
            assertEquals(1, h.permissionRequests.size)
            h.granted.clear()
            val detached = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(d) }
            h.devices.clear()
            h.granted += d.deviceName
            h.platform.deliverPermission(1, d)
            assertFalse(withTimeout(500) { detached.await() })
            assertTrue(hub.deviceFlow.value.isEmpty())
            assertFalse(hub.permissionGranted.value)
        } finally { hub.close() }
    }

    @Test fun sameDeviceRequestsCoalesceAndOneCancelledCallerCannotCancelOtherWaiter() = runBlocking {
        val h = Harness()
        val d = device("/synthetic/a", 1)
        h.attach(d)
        val hub = h.hub()
        try {
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(d) }
            val survivor = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(d) }
            assertEquals(1, h.permissionRequests.size)
            assertEquals(1, h.platform.specs.size)
            cancelled.cancelAndJoin()
            assertFalse(survivor.isCompleted)
            h.granted += d.deviceName
            h.platform.deliverPermission(0, d)
            assertTrue(withTimeout(500) { survivor.await() })
        } finally { hub.close() }
    }

    @Test fun enumerateReconcilesDetachBeforeBroadcastWithoutLosingEventOrWaiter() = runBlocking {
        val h = Harness()
        val d = device("/synthetic/a", 1)
        h.attach(d)
        val hub = h.hub()
        val events = mutableListOf<UsbHub.Event>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { hub.events.collect { events += it } }
        try {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(d) }
            h.platform.deliverSystem(UsbManager.ACTION_USB_DEVICE_DETACHED, d)
            assertFalse(waiting.isCompleted) // forged detach while device still live.
            assertEquals(listOf(d), hub.deviceFlow.value)
            h.devices.clear()
            assertTrue(hub.enumerate().isEmpty())
            assertFalse(withTimeout(500) { waiting.await() })
            h.platform.deliverSystem(UsbManager.ACTION_USB_DEVICE_DETACHED, d)
            yield()
            assertEquals(listOf(UsbHub.Event.PermissionResult(d, false), UsbHub.Event.Detached(d)), events)
        } finally { collector.cancelAndJoin(); hub.close() }
    }

    @Test fun replacementWithSameNameCancelsOldIdentityAndCannotBorrowItsPermissionRequest() = runBlocking {
        val h = Harness()
        val old = device("/synthetic/a", 1)
        val fresh = device(old.deviceName, 2)
        h.attach(old)
        val hub = h.hub()
        val events = mutableListOf<UsbHub.Event>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { hub.events.collect { events += it } }
        try {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(old) }
            h.devices[old.deviceName] = fresh
            assertEquals(listOf(fresh), hub.enumerate())
            assertFalse(withTimeout(500) { waiting.await() })
            assertFalse(hub.hasPermission(old))
            assertFalse(hub.requestPermission(old))
            yield()
            assertEquals(listOf(UsbHub.Event.PermissionResult(old, false), UsbHub.Event.Detached(old), UsbHub.Event.Attached(fresh)), events)
        } finally { collector.cancelAndJoin(); hub.close() }
    }

    @Test fun attachHintsCannotInventTargetsAndActualAttachIsEmittedOnce() = runBlocking {
        val h = Harness()
        val d = device("/synthetic/a", 1)
        val hub = h.hub()
        val events = mutableListOf<UsbHub.Event>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { hub.events.collect { events += it } }
        try {
            h.platform.deliverSystem(UsbManager.ACTION_USB_DEVICE_ATTACHED, d)
            assertTrue(hub.deviceFlow.value.isEmpty())
            h.attach(d)
            h.platform.deliverSystem(UsbManager.ACTION_USB_DEVICE_ATTACHED, d)
            h.platform.deliverSystem(UsbManager.ACTION_USB_DEVICE_ATTACHED, d)
            yield()
            assertEquals(listOf(UsbHub.Event.Attached(d)), events)
        } finally { collector.cancelAndJoin(); hub.close() }
    }

    @Test fun closeCompletesAllWaitersEvenWhenEveryCleanupThrowsAndIsIdempotent() = runBlocking {
        val h = Harness()
        val a = device("/synthetic/a", 1)
        val b = device("/synthetic/b", 2)
        h.attach(a, b)
        val hub = h.hub()
        val first = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(a) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { hub.requestPermission(b) }
        h.platform.failUnregister = true
        h.platform.tokens.forEach { token -> every { token.cancel() } throws SecurityException("synthetic cancel") }
        hub.close()
        assertFalse(withTimeout(500) { first.await() })
        assertFalse(withTimeout(500) { second.await() })
        assertEquals(3, h.platform.unregistered.size)
        hub.close()
        assertEquals(3, h.platform.unregistered.size)
        assertFalse(hub.requestPermission(a))
        assertFalse(hub.hasPermission(a))
        assertNull(hub.openConnection(a))
    }

    @Test fun platformAndManagerRequestFailuresReturnFalseAndUnregisterResources() = runBlocking {
        for (failureAt in listOf("register", "intent", "request")) {
            val h = Harness()
            val d = device("/synthetic/a", 1)
            h.attach(d)
            val hub = h.hub()
            h.platform.failPermissionRegister = failureAt == "register"
            h.platform.failIntent = failureAt == "intent"
            if (failureAt == "request") every { h.manager.requestPermission(any<UsbDevice>(), any<PendingIntent>()) } throws SecurityException("synthetic request")
            try {
                assertFalse(withTimeout(500) { hub.requestPermission(d) })
                if (failureAt != "register") assertEquals(1, h.platform.unregistered.size)
                if (failureAt == "request") verify(exactly = 1) { h.platform.tokens.single().cancel() }
            } finally { hub.close() }
        }
    }

    @Test fun openRequiresLivePerDevicePermissionReturnsSameConnectionAndNeverOwnsIt() {
        val h = Harness()
        val d = device("/synthetic/a", 1)
        h.attach(d)
        val hub = h.hub()
        val connection = mockk<UsbDeviceConnection>()
        every { h.manager.openDevice(d) } returns connection
        assertNull(hub.openConnection(d))
        h.granted += d.deviceName
        assertSame(connection, hub.openConnection(d))
        assertNull(hub.openConnection(device(d.deviceName, 99)))
        every { h.manager.openDevice(d) } throws SecurityException("permission revoked after check")
        assertNull(hub.openConnection(d))
        hub.close()
        verify(exactly = 0) { connection.close() }
    }

    private class Harness(sdkInt: Int = 33) {
        val context = mockk<Context>()
        val manager = mockk<UsbManager>()
        val devices = HashMap<String, UsbDevice>()
        val granted = mutableSetOf<String>()
        val permissionRequests = mutableListOf<Pair<UsbDevice, PendingIntent>>()
        val platform = TestPlatform(context, sdkInt)
        init {
            every { context.packageName } returns PACKAGE_NAME
            every { manager.deviceList } answers { devices }
            every { manager.hasPermission(any<UsbDevice>()) } answers { (args[0] as UsbDevice).deviceName in granted }
            every { manager.requestPermission(any<UsbDevice>(), any<PendingIntent>()) } answers { permissionRequests += (args[0] as UsbDevice) to (args[1] as PendingIntent) }
        }
        fun attach(vararg targets: UsbDevice) { targets.forEach { devices[it.deviceName] = it } }
        fun hub() = UsbHub(context, manager, platform)
    }

    private data class Registration(val receiver: BroadcastReceiver, val actions: List<String>, val flags: Int?)
    private class TestPlatform(private val context: Context, override val sdkInt: Int) : UsbHub.Platform {
        val registered = mutableListOf<Registration>()
        val unregistered = mutableListOf<BroadcastReceiver>()
        val specs = mutableListOf<UsbHub.PermissionIntentSpec>()
        val tokens = mutableListOf<PendingIntent>()
        private val reportedDevices = IdentityHashMap<Intent, UsbDevice>()
        var failUnregister = false
        var failPermissionRegister = false
        var failIntent = false
        override fun register(receiver: BroadcastReceiver, actions: List<String>, flags: Int?) {
            if (failPermissionRegister) throw SecurityException("synthetic registration")
            registered += Registration(receiver, actions, flags)
        }
        override fun unregister(receiver: BroadcastReceiver) {
            unregistered += receiver
            if (failUnregister) throw SecurityException("synthetic unregister")
        }
        override fun permissionIntent(spec: UsbHub.PermissionIntentSpec): PendingIntent {
            if (failIntent) throw SecurityException("synthetic intent")
            specs += spec
            return mockk<PendingIntent>().also { token -> every { token.cancel() } just runs; tokens += token }
        }
        override fun usbDevice(intent: Intent): UsbDevice? = reportedDevices[intent]
        fun deliverPermission(index: Int, device: UsbDevice, frameworkGranted: Boolean = true,
                              action: String = specs[index].action, packageName: String = PACKAGE_NAME, nonce: String = specs[index].nonce) {
            val intent = mockk<Intent>()
            every { intent.action } returns action
            every { intent.`package` } returns packageName
            every { intent.getStringExtra(UsbHub.EXTRA_NONCE) } returns nonce
            every { intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) } returns frameworkGranted
            reportedDevices[intent] = device
            registered.single { it.actions == listOf(specs[index].action) }.receiver.onReceive(context, intent)
        }
        fun deliverSystem(action: String, device: UsbDevice) {
            val intent = mockk<Intent>()
            every { intent.action } returns action
            reportedDevices[intent] = device
            registered.first().receiver.onReceive(context, intent)
        }
    }

    companion object {
        private const val PACKAGE_NAME = "test.tether.fixture"
        private fun device(name: String, id: Int, vendor: Int = UsbHub.PANASONIC_VENDOR_ID, product: Int = 0xFFFF): UsbDevice =
            mockk<UsbDevice>().also { device ->
                every { device.deviceName } returns name; every { device.deviceId } returns id
                every { device.vendorId } returns vendor; every { device.productId } returns product
            }
    }
}
