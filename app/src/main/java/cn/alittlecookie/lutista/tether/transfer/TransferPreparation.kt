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
        // 真机 logs_1795847176992：上次传输被相机掉线打断后留下 .part，之后每次重下都在这里
        // 立刻抛异常（界面只显示失败、日志里没有任何 USB 活动），表现为「批量下载漏文件」
        // 且「重复下载报错」。.part 是本进程控制的私有暂存，没有 journal 就不可能有恢复价值，
        // 直接清掉重新开始；有 journal 时 recover() 已经先跑过一遍。
        // 上一次传输被打断后可能留下自己的 .part（真机 logs_1795847176992 的批量漏文件就是这个）。
        // 这里不能直接删：本函数不掌握「所有权」，可能是别的写入者刚创建的。
        // 属于我方的暂存清理在存储层（AndroidTransferTargetStorage/FileTransferTargetStorage）完成。
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
