package cn.alittlecookie.lutista.service

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** A TETHER output owns a newly created file and the stream that created it. */
internal object TetherOutputAllocator {
    class Allocation internal constructor(val file: File, val stream: OutputStream) : Closeable {
        override fun close() = stream.close()
    }

    /** Canonical paths include symlink aliases. CREATE_NEW also rejects existing final symlinks.
     * Keep the returned stream open through encoding/writing: never reopen the chosen filename.
     */
    fun allocateFile(
        requested: File,
        sourcePath: String,
        originalSourcePath: String?,
        explicit: Boolean,
        beforeCreate: (File) -> Unit = {},
    ): Allocation {
        val originals = listOfNotNull(sourcePath, originalSourcePath)
            .filterNot { it.startsWith("content://") }
            .map { File(it).canonicalFile }
            .toSet()
        val desired = requested.canonicalFile
        val parent = desired.parentFile ?: throw IOException("Output needs a parent directory")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create output directory")
        }
        val extension = desired.extension.let { if (it.isEmpty()) "" else ".$it" }
        val base = if (extension.isEmpty()) desired.name else desired.name.dropLast(extension.length)
        for (index in 0..100_000) {
            val candidate = if (index == 0) desired else File(parent, "${base}_$index$extension")
            if (candidate.canonicalFile in originals) {
                if (explicit) throw IOException("TETHER output must not overwrite an original")
                continue
            }
            beforeCreate(candidate)
            try {
                val stream = Files.newOutputStream(
                    candidate.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                )
                return Allocation(candidate, stream)
            } catch (e: FileAlreadyExistsException) {
                if (explicit) throw IOException("Explicit TETHER output already exists", e)
            }
        }
        throw IOException("Cannot allocate a new TETHER output filename")
    }

    /** Existing document URIs have no CREATE_NEW equivalent; only createDocument results may be used. */
    fun requireNewSafDestination(explicitOutputPath: String) {
        if (explicitOutputPath.startsWith("content://")) {
            throw IOException("TETHER output requires a newly created SAF document")
        }
    }

    /** Call before taking ownership of a provider result, so a rejected original is never cleaned up. */
    fun requireUnprotectedDocument(createdIdentity: String, protectedIdentities: Set<String>) {
        if (createdIdentity in protectedIdentities) {
            throw IOException("SAF output must not reuse an original document")
        }
    }
}
