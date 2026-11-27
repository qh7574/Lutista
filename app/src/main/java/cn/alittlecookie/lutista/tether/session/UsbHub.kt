package cn.alittlecookie.lutista.tether.session

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * USB discovery and permission lifetime. The caller owns each returned connection and performs
 * openConnection/transport I/O on its ptp-io thread. Activity attach intents are handled by the host.
 * System and permission receivers are separate. A broadcast is only a hint: UsbManager is the truth.
 */
class UsbHub internal constructor(
    context: Context,
    private val usbManager: UsbManager,
    private val platform: Platform,
) : AutoCloseable {
    constructor(context: Context, usbManager: UsbManager = context.getSystemService(UsbManager::class.java)) :
        this(context, usbManager, AndroidPlatform(context.applicationContext ?: context))

    private val packageName = context.packageName
    private val lock = Any()
    private val knownDevices = linkedMapOf<String, UsbDevice>()
    private val pending = linkedMapOf<String, PermissionRequest>()
    private var closed = false
    private val _deviceFlow = MutableStateFlow<List<UsbDevice>>(emptyList())
    private val _permissionGranted = MutableStateFlow(false)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val deviceFlow: StateFlow<List<UsbDevice>> = _deviceFlow.asStateFlow()
    /** Whether at least one currently enumerated target has UsbManager permission. */
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    sealed interface Event {
        data class Attached(val device: UsbDevice) : Event
        data class Detached(val device: UsbDevice) : Event
        data class PermissionResult(val device: UsbDevice, val granted: Boolean) : Event
    }

    private class PermissionRequest(
        val device: UsbDevice,
        val action: String,
        val nonce: String,
        val result: CompletableDeferred<Boolean>,
        val receiver: BroadcastReceiver,
        val intent: PendingIntent,
    )

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = platform.usbDevice(intent) ?: return
            if (device.vendorId != PANASONIC_VENDOR_ID) return
            synchronized(lock) {
                if (closed) return
                val live = currentDevices()
                val current = live.firstOrNull { sameDevice(it, device) }
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (current == null) return
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        // Never trust a forged detach for a still-live device or a different identity.
                        if (current != null) return
                        val previous = knownDevices[device.deviceName]
                        if (previous != null && !sameDevice(previous, device)) return
                    }
                    else -> return
                }
                updateState(live)
            }
        }
    }

    init {
        register(systemReceiver, listOf(UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED), exported = true)
        try {
            synchronized(lock) { updateState(currentDevices()) }
        } catch (error: RuntimeException) {
            runCatching { platform.unregister(systemReceiver) }
            throw error
        }
    }

    fun enumerate(): List<UsbDevice> = synchronized(lock) {
        if (closed) return emptyList()
        currentDevices().also { updateState(it) }
    }

    fun hasPermission(device: UsbDevice): Boolean = synchronized(lock) {
        if (closed) return false
        val devices = currentDevices()
        updateState(devices)
        val live = devices.firstOrNull { sameDevice(it, device) } ?: return false
        usbManager.hasPermission(live)
    }

    suspend fun requestPermission(device: UsbDevice): Boolean {
        val result = synchronized(lock) {
            if (closed) return false
            val devices = currentDevices()
            updateState(devices)
            val live = devices.firstOrNull { sameDevice(it, device) } ?: return false
            if (usbManager.hasPermission(live)) return true
            pending[live.deviceName]?.let { return@synchronized it.result }

            val nonce = UUID.randomUUID().toString()
            val action = "${packageName}.tether.USB_PERMISSION.${UUID.randomUUID()}"
            val deferred = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    synchronized(lock) {
                        val request = pending[live.deviceName] ?: return
                        if (closed || intent.action != request.action || intent.`package` != packageName ||
                            intent.getStringExtra(EXTRA_NONCE) != request.nonce
                        ) return
                        val reported = platform.usbDevice(intent) ?: return
                        if (!sameDevice(request.device, reported)) return
                        val attached = currentDevices()
                        val current = attached.firstOrNull { sameDevice(it, request.device) }
                        // Mutable framework extras alone can never grant access.
                        val granted = current != null &&
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usbManager.hasPermission(current)
                        pending.remove(live.deviceName)
                        updateState(attached)
                        finishRequest(request, granted)
                    }
                }
            }
            try {
                register(receiver, listOf(action), exported = false)
            } catch (_: RuntimeException) {
                return false
            }
            val permissionIntent = try {
                platform.permissionIntent(PermissionIntentSpec(
                    action, packageName, nonce, requestCodes.incrementAndGet(),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
                ))
            } catch (_: RuntimeException) {
                runCatching { platform.unregister(receiver) }
                return false
            }
            val request = PermissionRequest(live, action, nonce, deferred, receiver, permissionIntent)
            pending[live.deviceName] = request
            try {
                usbManager.requestPermission(live, permissionIntent)
            } catch (_: RuntimeException) {
                pending.remove(live.deviceName)
                finishRequest(request, false)
            }
            deferred
        }
        return result.await()
    }

    fun openConnection(device: UsbDevice): UsbDeviceConnection? = synchronized(lock) {
        if (closed) return null
        val devices = currentDevices()
        updateState(devices)
        val live = devices.firstOrNull { sameDevice(it, device) } ?: return null
        if (!usbManager.hasPermission(live)) return null
        try { usbManager.openDevice(live) } catch (_: SecurityException) { null }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { platform.unregister(systemReceiver) }
            val requests = pending.values.toList()
            pending.clear()
            requests.forEach { finishRequest(it, false) }
            knownDevices.clear()
            _deviceFlow.value = emptyList()
            _permissionGranted.value = false
        }
    }

    private fun currentDevices(): List<UsbDevice> = usbManager.deviceList.values
        .filter { it.vendorId == PANASONIC_VENDOR_ID }.sortedBy { it.deviceName }

    /** Enumeration also reconciles detach, so refreshing before a broadcast cannot strand a waiter. */
    private fun updateState(devices: List<UsbDevice>) {
        val removed = knownDevices.values.filter { old -> devices.none { sameDevice(old, it) } }
        val added = devices.filter { fresh -> knownDevices.values.none { sameDevice(it, fresh) } }
        knownDevices.clear()
        devices.forEach { knownDevices[it.deviceName] = it }
        _deviceFlow.value = devices
        _permissionGranted.value = devices.any { usbManager.hasPermission(it) }
        removed.forEach { device ->
            pending.remove(device.deviceName)?.let { finishRequest(it, false) }
            _events.tryEmit(Event.Detached(device))
        }
        added.forEach { _events.tryEmit(Event.Attached(it)) }
    }

    private fun finishRequest(request: PermissionRequest, granted: Boolean) {
        // Cleanup failures must not prevent any waiter from completing or other receivers closing.
        runCatching { platform.unregister(request.receiver) }
        runCatching { request.intent.cancel() }
        request.result.complete(granted)
        _events.tryEmit(Event.PermissionResult(request.device, granted))
    }

    private fun register(receiver: BroadcastReceiver, actions: List<String>, exported: Boolean) {
        val flags = if (platform.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
        } else null
        platform.register(receiver, actions, flags)
    }

    private fun sameDevice(a: UsbDevice, b: UsbDevice): Boolean =
        a.deviceName == b.deviceName && a.deviceId == b.deviceId && a.vendorId == b.vendorId && a.productId == b.productId

    /** Android construction/registration boundary only; production always uses AndroidPlatform. */
    internal interface Platform {
        val sdkInt: Int
        fun register(receiver: BroadcastReceiver, actions: List<String>, flags: Int?)
        fun unregister(receiver: BroadcastReceiver)
        fun permissionIntent(spec: PermissionIntentSpec): PendingIntent
        fun usbDevice(intent: Intent): UsbDevice?
    }

    internal data class PermissionIntentSpec(
        val action: String,
        val packageName: String,
        val nonce: String,
        val requestCode: Int,
        val flags: Int,
    )

    private class AndroidPlatform(private val context: Context) : Platform {
        override val sdkInt: Int get() = Build.VERSION.SDK_INT
        override fun register(receiver: BroadcastReceiver, actions: List<String>, flags: Int?) {
            val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
            if (flags != null) context.registerReceiver(receiver, filter, flags) else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }
        override fun unregister(receiver: BroadcastReceiver) = context.unregisterReceiver(receiver)
        override fun permissionIntent(spec: PermissionIntentSpec): PendingIntent = PendingIntent.getBroadcast(
            context, spec.requestCode,
            Intent(spec.action).setPackage(spec.packageName).putExtra(EXTRA_NONCE, spec.nonce), spec.flags,
        )
        @Suppress("NewApi")
        override fun usbDevice(intent: Intent): UsbDevice? =
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }

    companion object {
        const val PANASONIC_VENDOR_ID: Int = 0x04DA
        internal const val EXTRA_NONCE = "tether_permission_nonce"
        private val requestCodes = AtomicInteger()
    }
}
