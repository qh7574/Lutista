package cn.alittlecookie.lutista.tether.driver

import android.hardware.usb.UsbDevice
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import kotlinx.coroutines.flow.StateFlow

sealed interface CameraTarget { val cacheKey: String }
data class UsbTarget(val device: UsbDevice, val permissionGranted: Boolean) : CameraTarget {
    override val cacheKey: String get() = "usb:" + device.deviceName + ":" + device.vendorId + ":" + device.productId
}

enum class CloseReason { USER, DETACHED, FATAL, ACTIVITY_DESTROYED, RECONNECT }
sealed interface ConnectOutcome {
    data object Connected : ConnectOutcome
    data object AlreadyConnecting : ConnectOutcome
    data object NotAttached : ConnectOutcome
    data object PermissionDenied : ConnectOutcome
    data object WrongMode : ConnectOutcome
    data class Failed(val cause: Throwable) : ConnectOutcome
}
interface CameraStatusSource {
    val battery: StateFlow<Int?>
    val modePos: StateFlow<Long?>
}
/** Future controllers are deliberately absent until their own delivery gates pass. */
interface CameraObjectSource
interface CameraLiveView
interface CameraConfig
interface CameraCapture
interface CameraDriver {
    val profileId: String
    val capabilities: StateFlow<CameraCapabilities>
    val status: CameraStatusSource
    val objects: CameraObjectSource? get() = null
    val liveView: CameraLiveView? get() = null
    val config: CameraConfig? get() = null
    val capture: CameraCapture? get() = null
    suspend fun connect(): ConnectOutcome
    suspend fun disconnect(reason: CloseReason)
    suspend fun ensureCapabilities(force: Boolean = false)
}
