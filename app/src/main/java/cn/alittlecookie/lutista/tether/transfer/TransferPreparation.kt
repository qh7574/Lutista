package cn.alittlecookie.lutista.tether.transfer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/** Shared by ordinary output and SAF's local original: journal first, then owned partial bytes. */
internal object TransferPreparation {
    data class Opened(val output: FileOutputStream, val reservation: String)

    fun open(
        part: File,
        journal: File,
        properties: Properties,
        suppression: TetherProcessingSuppression,
        displayName: String,
        size: Long,
        targetPath: String? = null,
        openOutput: (File) -> FileOutputStream = { FileOutputStream(it) },
        writeJournal: (File, Properties) -> Unit = { file, values -> FileTransferTargetStorage.writeJournal(file, values) },
    ): Opened {
        if (part.exists()) throw IOException("Partial output already exists; recovery must clean it first")
        // Failed reservation/journal persistence must happen before any partial bytes exist.
        val reservation = suppression.reserve(displayName, size, targetPath)
        var ownsPart = false
        try {
            properties.setProperty("reservation", reservation)
            writeJournal(journal, properties)
            if (!part.createNewFile()) throw IOException("Partial output was occupied during preparation")
            ownsPart = true
            return Opened(openOutput(part), reservation)
        } catch (failure: Exception) {
            val removed = !ownsPart || !part.exists() || part.delete()
            if (removed) {
                // Never delete a part created by another actor during the allocation race.
                cleanup(failure) { if (journal.exists() && !journal.delete()) throw IOException("Cannot remove preparation journal") }
                cleanup(failure) {
                    val journalPart = File(journal.parentFile, journal.name + ".part")
                    if (journalPart.exists() && !journalPart.delete()) throw IOException("Cannot remove preparation journal partial")
                }
                cleanup(failure) { suppression.release(reservation) }
            } else {
                // Keep journal + exclusion: reconnect/manual recovery can still find this partial.
                failure.addSuppressed(IOException("Cannot remove prepared partial; journal retained for recovery"))
            }
            throw failure
        }
    }

    private inline fun cleanup(original: Exception, action: () -> Unit) {
        try { action() } catch (cleanup: Exception) { original.addSuppressed(cleanup) }
    }
}
