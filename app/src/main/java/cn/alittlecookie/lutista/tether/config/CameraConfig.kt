package cn.alittlecookie.lutista.tether.config

import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.session.PtpScheduler
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.session.CameraSession
import cn.alittlecookie.lutista.tether.session.PtpControlPort
import cn.alittlecookie.lutista.tether.session.PtpSessionApi
import cn.alittlecookie.lutista.tether.session.SessionControlPort
import cn.alittlecookie.lutista.tether.transport.PtpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PropertyRejectedException(val responseCode: Int) : Exception("Property response 0x${responseCode.toString(16)}")
sealed interface WriteResult {
    data class Applied(val actual: Long, val readBackMs: Long) : WriteResult
    data class Refused(val actual: Long?, val responseCode: Int?) : WriteResult
    data class Disabled(val reason: String) : WriteResult
}

/** Profile whitelist + real descriptors are the only authority for writes. No property scanning. */
class CameraConfig internal constructor(
    private val port: PtpControlPort,
    private val profile: CameraProfile,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onModeObserved: (Long?) -> Unit = {},
    private val writeAllowed: () -> Boolean = { true },
    private val onPhotoStyleChanged: () -> Unit = {},
) {
    constructor(session: CameraSession, profile: CameraProfile, onModeObserved: (Long?) -> Unit = {},
        onPhotoStyleChanged: () -> Unit = {}) :
        this(SessionControlPort(session), profile, onModeObserved = onModeObserved,
            writeAllowed = { session.caps.value[Feature.ConfigWrite].availability != Availability.Unsupported },
            onPhotoStyleChanged = onPhotoStyleChanged)

    private val mutex = Mutex()
    private val disabled = mutableSetOf<Long>()
    private val descriptors = mutableMapOf<ModeKey, Map<Long, PropertyDescriptor>>()
    private val mutableCatalog = MutableStateFlow<PropertyCatalogSnapshot?>(null)
    private val mutableStyleChanged = MutableStateFlow(false)
    val catalog: StateFlow<PropertyCatalogSnapshot?> = mutableCatalog.asStateFlow()
    val photoStyleChanged: StateFlow<Boolean> = mutableStyleChanged.asStateFlow()
    private var originalStyle: Long? = null

    suspend fun readProperty(id: Long): Long {
        allowed(id)
        return port.runOp { read(it, id).also { value -> if (id == CameraProperty.MODE) onModeObserved(value) } }
    }

    suspend fun describe(id: Long): PropertyDescriptor {
        allowed(id)
        return port.runOp { describe(it, id) }
    }

    /** Every refresh reads each displayed value independently, including count=0 properties. */
    suspend fun refreshCatalog(): PropertyCatalogSnapshot = mutex.withLock {
        val current = linkedMapOf<Long, PropertyState>()
        val readDescriptors = linkedMapOf<Long, PropertyDescriptor>()
        val mode = try { readProperty(CameraProperty.MODE) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) { rethrowTransport(error); null }
        // Shutter/aperture descriptors also reveal video P/A/S/M without extra USB probes.
        for (id in CameraProperty.display.filter { it in profile.propertyCatalogSeed }) {
            var detail: String? = null
            val descriptor = if (id in disabled) null else try { describe(id).also { readDescriptors[id] = it } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Throwable) {
                rethrowTransport(error)
                if (error is PropertyRejectedException && unsupported(error.responseCode)) disabled += id
                detail = error.message
                null
            }
            var value: Long? = null
            try { value = readProperty(id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Throwable) { rethrowTransport(error); detail = error.message }
            val access = when {
                id in disabled -> PropertyAccess.Unsupported
                value == null -> PropertyAccess.Unavailable
                id == CameraProperty.MODE || id == CameraProperty.IMAGE_FORMAT -> PropertyAccess.ReadOnly
                descriptor?.writable == true -> PropertyAccess.Writable
                else -> PropertyAccess.ReadOnly
            }
            current[id] = PropertyState(id, value, label(id, value), access, descriptor, detail)
        }
        val submode = when (mode) {
            in 0L..3L -> mode?.toInt()
            profile.quirks.videoModePos -> {
                val shutter = readDescriptors[CameraProperty.SHUTTER]?.writable == true
                val aperture = readDescriptors[CameraProperty.APERTURE]?.writable == true
                if (CameraProperty.SHUTTER !in readDescriptors || CameraProperty.APERTURE !in readDescriptors) null
                else when { shutter && aperture -> 3; shutter -> 2; aperture -> 1; else -> 0 }
            }
            else -> null
        }
        val key = ModeKey(mode, submode)
        descriptors[key] = readDescriptors.toMap()
        PropertyCatalogSnapshot(key, current.toMap()).also { mutableCatalog.value = it }
    }

    suspend fun writeProperty(id: Long, value: Long): WriteResult = mutex.withLock {
        allowed(id)
        require(value in 0..0xFFFF_FFFFL)
        if (!writeAllowed()) return@withLock WriteResult.Disabled("相机未提供参数写入")
        val snapshot = mutableCatalog.value ?: return@withLock WriteResult.Disabled("请先刷新参数")
        val descriptor = descriptors[snapshot.mode]?.get(id)
        if (id in disabled || descriptor?.writable != true || id == CameraProperty.MODE || id == CameraProperty.IMAGE_FORMAT)
            return@withLock WriteResult.Disabled("当前模式下不可调整")
        if (value !in descriptor.values || (id == CameraProperty.PHOTO_STYLE && value !in (profile.photoStyleValues ?: LongRange.EMPTY)))
            return@withLock WriteResult.Refused(snapshot.properties[id]?.value, null)
        port.runOp { api ->
            // Snapshot on the admitted worker, before the first mutation. A cancelled waiter
            // cannot skip either read-back or restoration bookkeeping after a physical write.
            if (id == CameraProperty.PHOTO_STYLE && originalStyle == null) originalStyle = read(api, id)
            val response = api.setProperty(descriptor.valueId, PropertyCodec.encodeValue(descriptor.valueId, value))
            if (response.code != PtpConstants.Rc.OK) {
                if (unsupported(response.code)) {
                    disabled += id
                    update(id, snapshot.properties[id]?.value, PropertyAccess.Unsupported, "相机不支持此属性")
                    WriteResult.Disabled("相机不支持此属性")
                } else WriteResult.Refused(snapshot.properties[id]?.value, response.code)
            } else {
                if (id == CameraProperty.PHOTO_STYLE) {
                    mutableStyleChanged.value = true
                    onPhotoStyleChanged()
                }
                val started = clock()
                val actual = read(api, id, 300)
                val elapsed = clock() - started
                update(id, actual)
                if (actual == value && elapsed <= 300) WriteResult.Applied(actual, elapsed)
                else WriteResult.Refused(actual, null) // No automatic retry or fictional value.
            }
        }
    }

    /** Must run before a normal session close; fatal/detached connections perform no I/O. */
    suspend fun restorePhotoStyle(): WriteResult? = mutex.withLock {
        if (port.isFatal || !port.isOpen) return@withLock null
        // A cancelled caller may have left an admitted style snapshot/write draining on USB.
        // Wait for it before inspecting originalStyle, including cancellation during the first read.
        port.runOp(PtpScheduler.Priority.ABORT) {}
        val original = originalStyle ?: return@withLock null
        if (!mutableStyleChanged.value || port.isFatal || !port.isOpen) return@withLock null
        port.runOp { api ->
            val id = CameraProperty.PHOTO_STYLE
            val response = api.setProperty(id, PropertyCodec.encodeValue(id, original))
            if (response.code != PtpConstants.Rc.OK) WriteResult.Refused(mutableCatalog.value?.properties?.get(id)?.value, response.code)
            else {
                val started = clock()
                val actual = read(api, id, 300)
                update(id, actual)
                if (actual == original) {
                    mutableStyleChanged.value = false
                    originalStyle = null
                    onPhotoStyleChanged()
                    WriteResult.Applied(actual, clock() - started)
                } else WriteResult.Refused(actual, null)
            }
        }
    }

    private suspend fun read(api: PtpSessionApi, id: Long, timeoutMs: Int = 200): Long {
        val valueId = CameraProperty.valueId(id)
        val data = api.getProperty(valueId, timeoutMs)
        if (data.response.code != PtpConstants.Rc.OK) throw PropertyRejectedException(data.response.code)
        return PropertyCodec.decodeValue(data.payload, valueId)
    }
    private suspend fun describe(api: PtpSessionApi, id: Long): PropertyDescriptor {
        check(profile.quirks.supportsPropertyDescriptor) { "Profile has no descriptor protocol" }
        val data = api.listPropertyDescriptor(id)
        if (data.response.code != PtpConstants.Rc.OK) throw PropertyRejectedException(data.response.code)
        val descriptor = PropertyCodec.decodeDescriptor(data.payload, id)
        return if (id == CameraProperty.PHOTO_STYLE) descriptor.copy(values = descriptor.values.filter {
            it in (profile.photoStyleValues ?: LongRange.EMPTY)
        }) else descriptor
    }
    private fun allowed(id: Long) {
        require(id in CameraProperty.whitelist && id in profile.propertyCatalogSeed) { "Property is outside the profile whitelist" }
    }
    private fun update(id: Long, value: Long?, access: PropertyAccess? = null, detail: String? = null) {
        val snapshot = mutableCatalog.value ?: return
        val previous = snapshot.properties[id] ?: return
        mutableCatalog.value = snapshot.copy(properties = snapshot.properties + (id to previous.copy(
            value = value, label = label(id, value), access = access ?: previous.access, detail = detail)))
    }
    private fun label(id: Long, value: Long?): String = when {
        value == null -> "—"
        id == CameraProperty.PHOTO_STYLE -> "格调 $value"
        else -> PropertyCodec.label(profile.propertyCatalogSeed.getValue(id).codec, value)
    }
    private fun unsupported(code: Int) = code == 0x2005 || code == 0x2002
    private fun rethrowTransport(error: Throwable) { if (port.isFatal || error is PtpException) throw error }
}
