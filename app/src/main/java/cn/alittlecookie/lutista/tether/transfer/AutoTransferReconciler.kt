package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraObjectKeys
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey
import cn.alittlecookie.lutista.tether.objects.ObjectIdentity
import cn.alittlecookie.lutista.tether.objects.UnavailableDirectory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session-local compensation for cameras that omit C108. Only complete snapshots advance it. */
internal class AutoTransferReconciler(private val session: CameraSessionKey) {
    private val lock = Mutex()
    private var initialized = false
    private val seen = hashSetOf<String>()
    private var unavailable = emptySet<ObjectIdentity>()

    suspend fun reconcile(
        snapshot: List<CameraObject>,
        unavailableDirectories: List<UnavailableDirectory> = emptyList(),
        automaticEnabled: Boolean,
        onNewJpeg: suspend (CameraObject) -> Unit,
    ) = lock.withLock {
        val directories = snapshot.filter { it.isDir }.associateBy { ObjectIdentity(it.storageId, it.handle) }
        fun behindPreviouslyUnavailableDirectory(info: CameraObject): Boolean {
            var parent = ObjectIdentity(info.storageId, info.parent)
            val visited = hashSetOf<ObjectIdentity>()
            while (visited.add(parent)) {
                if (parent in unavailable) return true
                val directory = directories[parent] ?: return false
                parent = ObjectIdentity(directory.storageId, directory.parent)
            }
            return true // A malformed parent cycle can never authorize an automatic download.
        }
        for (info in snapshot) {
            if (info.isDir) continue
            val identity = CameraObjectKeys.transferDedupeKey(session.serial, info)
            if (identity in seen) continue
            if (initialized && automaticEnabled && !behindPreviouslyUnavailableDirectory(info) &&
                info.format == 0x3801L && info.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")) {
                // Mark only after the engine has durably admitted the original. A failure stays retryable.
                onNewJpeg(info)
            }
            seen += identity
        }
        unavailable = unavailableDirectories.map { ObjectIdentity(it.directory.storageId, it.directory.handle) }.toSet()
        initialized = true
    }
}
