package cn.alittlecookie.lutista.gpu

import android.content.Context
import cn.alittlecookie.lutista.data.model.LutData
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.tether.render.GpuFilterLease

class VulkanGpuEngine(private val logger: LogManager) {
    companion object {
        private const val TAG = "VulkanGpuEngine"
        private val SHADER_NAMES = listOf("unified.comp.spv")
    }

    // One lock covers native lifetime AND the complete upload -> dispatch transaction.
    private val lease = GpuFilterLease()
    private var nativeHandle = 0L

    /** true after init() completes (success or failure), false after destroy(). */
    @Volatile
    var initialized = false
        private set

    val resourceGeneration: Long get() = lease.generation
    val isAvailable: Boolean get() = lease.withLease { availableLocked() }

    /** Callers must not suspend inside either transaction block. Nested GPU calls are reentrant. */
    fun <T> withSession(block: () -> T): T = lease.withLease(block)
    fun <T> tryWithSession(block: () -> T): T? = lease.tryWithLease(block)
    fun invalidateResourceCache() { lease.invalidate() }

    private fun availableLocked(): Boolean =
        initialized && nativeHandle != 0L && nativeIsAvailable(nativeHandle)

    fun init(context: Context) = lease.withLease {
        if (availableLocked()) return@withLease
        val previousHandle = nativeHandle
        // Clear the old handle before JNI, so a failed teardown can never publish a stale pointer.
        nativeHandle = 0L
        initialized = false
        lease.invalidate()
        try {
            if (previousHandle != 0L) nativeDestroy(previousHandle)
            val shaderDataMap = loadShaderData(context)
            if (shaderDataMap.isEmpty()) {
                logger.w(TAG, "No shader data loaded, Vulkan disabled")
                return@withLease
            }
            nativeHandle = nativeInit(shaderDataMap)
            if (nativeHandle != 0L) {
                logger.i(TAG, "Vulkan GPU engine initialized successfully")
            } else {
                logger.w(TAG, "Vulkan GPU engine init returned null handle")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to initialize Vulkan GPU engine", e)
        } finally {
            initialized = true
        }
    }

    private fun loadShaderData(context: Context): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        try {
            for (name in SHADER_NAMES) {
                result[name] = context.assets.open("shaders/$name").use { it.readBytes() }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to load shader data from assets", e)
            return emptyMap()
        }
        return result
    }

    fun destroy() = lease.withLease {
        val handle = nativeHandle
        // Publish unavailability before calling JNI; destroy is idempotent even on failure.
        nativeHandle = 0L
        initialized = false
        lease.invalidate()
        if (handle != 0L) nativeDestroy(handle)
    }

    fun uploadLut(lutData: LutData, slot: Int = 0): Boolean = lease.withLease {
        if (!availableLocked() || slot !in 0..1) return@withLease false
        // A failed/partial upload invalidates every ImageProcessingEngine instance too.
        lease.invalidate()
        try {
            nativeUploadLut(nativeHandle, lutData.data, lutData.size, slot)
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to upload LUT", e)
            false
        }
    }

    fun uploadCurves(curveData: FloatArray): Boolean = lease.withLease {
        if (!availableLocked() || curveData.size != 1028) return@withLease false
        lease.invalidate()
        try {
            nativeUploadCurves(nativeHandle, curveData)
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to upload curves", e)
            false
        }
    }

    /** Standalone dispatch is safe; filter callers must hold withSession around uploads as well. */
    fun processImage(
        imageData: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray
    ): ByteArray? = lease.withLease {
        val byteCount = width.toLong() * height * 4
        if (width <= 0 || height <= 0 || byteCount > Int.MAX_VALUE ||
            byteCount != imageData.size.toLong() || params.size != 57 || !availableLocked()
        ) return@withLease null
        try {
            nativeProcessImage(nativeHandle, imageData, width, height, params)
        } catch (e: Exception) {
            logger.e(TAG, "GPU processing failed", e)
            null
        }
    }

    private external fun nativeInit(shaderData: Map<String, ByteArray>): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeIsAvailable(handle: Long): Boolean
    private external fun nativeUploadLut(handle: Long, lutData: FloatArray, lutSize: Int, lutSlot: Int)
    private external fun nativeUploadCurves(handle: Long, curveData: FloatArray)
    private external fun nativeProcessImage(
        handle: Long,
        imageData: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray
    ): ByteArray?
}
