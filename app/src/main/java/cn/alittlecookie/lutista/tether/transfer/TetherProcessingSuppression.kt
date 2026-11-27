package cn.alittlecookie.lutista.tether.transfer

import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

/** Shared by TETHER imports, processing outputs and BOTH monitor implementations.
 * Reservations are durable and NOT consumed by a scan: recursive MediaStore rescans
 * must keep excluding the same original and processed output after a process restart.
 */
class TetherProcessingSuppression {
    private data class Entry(val name: String, val size: Long?, val path: String?)
    private val entries = linkedMapOf<String, Entry>()
    private var journal: File? = null
    private var unavailableCause: Throwable? = null

    @Synchronized
    fun configure(file: File) {
        if (journal?.absolutePath == file.absolutePath) return
        check(journal == null) { "Suppression journal already configured" }
        journal = file
        try {
            if (file.exists()) {
                if (!file.isFile) throw IOException("Suppression journal is not a regular file")
                val properties = Properties().apply { file.inputStream().use { load(it) } }
                properties.stringPropertyNames().filter { it.endsWith(".name") }.forEach { field ->
                    val token = field.removeSuffix(".name")
                    entries.putIfAbsent(token, Entry(
                        properties.getProperty(field), properties.getProperty("$token.size")?.toLongOrNull(),
                        properties.getProperty("$token.path")?.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        } catch (failure: Exception) {
            unavailableCause = failure
        }
    }

    @Synchronized
    fun isAvailable(): Boolean = unavailableCause == null

    @Synchronized
    fun availabilityError(): String? = unavailableCause?.message ?: unavailableCause?.javaClass?.simpleName

    /** Call BEFORE creating a visible JPEG/SAF document (unknown size is allowed). */
    @Synchronized
    fun reserve(displayName: String, expectedSize: Long? = null, path: String? = null): String {
        requireAvailable()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(displayName, expectedSize, path)
        try { persist() }
        catch (failure: Exception) {
            entries.remove(token)
            throw failure
        }
        return token
    }

    @Synchronized
    fun complete(token: String, path: String, size: Long) {
        requireAvailable()
        val old = entries[token] ?: return
        entries[token] = old.copy(path = path, size = size)
        persist()
    }

    /** Bind the provider's actual URI/name after create and before writing bytes. */
    @Synchronized
    fun bind(token: String, path: String, displayName: String? = null) {
        requireAvailable()
        val old = entries[token] ?: return
        entries[token] = old.copy(path = path, name = displayName?.takeIf { it.isNotBlank() } ?: old.name)
        persist()
    }

    @Synchronized
    fun release(token: String) {
        if (unavailableCause != null) return
        if (entries.remove(token) != null) persist()
    }

    @Synchronized
    fun shouldSuppress(path: String? = null, displayName: String? = null, size: Long? = null): Boolean {
        if (unavailableCause != null) return true
        if (path?.replace(92.toChar(), '/')?.let {
                it.contains("/.tether-partials/") || it.contains("/.tether-sources/") || it.endsWith(".part")
            } == true) return true
        return entries.values.any {
            (path != null && it.path == path) ||
                (displayName != null && it.name == displayName && (it.size == null || size == null || it.size == size))
        }
    }

    private fun persist() {
        val file = journal ?: return
        file.parentFile?.mkdirs()
        val properties = Properties()
        entries.forEach { (token, entry) ->
            properties["$token.name"] = entry.name
            entry.size?.let { properties["$token.size"] = it.toString() }
            entry.path?.let { properties["$token.path"] = it }
        }
        val part = File(file.parentFile, file.name + ".part")
        try {
            part.outputStream().use { properties.store(it, "TETHER monitor exclusions") }
            // Android/Linux rename replaces atomically; Windows may need the fallback.
            if (!part.renameTo(file)) {
                file.outputStream().use { properties.store(it, "TETHER monitor exclusions") }
            }
        } catch (failure: Exception) {
            unavailableCause = failure
            throw failure
        } finally {
            // A failed reservation must not itself leave a journal partial behind.
            if (part.exists() && !part.delete()) {
                unavailableCause = unavailableCause ?: IOException("Cannot remove suppression journal partial")
            }
        }
    }

    private fun requireAvailable() {
        val failure = unavailableCause ?: return
        throw IOException("Persistent tether monitor exclusions are unavailable; operation blocked", failure)
    }

    companion object { val shared = TetherProcessingSuppression() }
}
