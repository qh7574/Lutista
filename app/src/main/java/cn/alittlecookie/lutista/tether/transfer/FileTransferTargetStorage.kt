package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.text.Normalizer
import java.util.Properties

/** Pure JVM implementation; Android supplies BitmapFactory JPEG validation. */
class FileTransferTargetStorage(
    private val directoryFor: suspend (TransferKind) -> File,
    private val stateDirectory: File,
    private val jpegValidator: (File) -> Boolean,
    private val suppression: TetherProcessingSuppression = TetherProcessingSuppression.shared,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) : TransferTargetStorage {
    override suspend fun prepare(key: String, info: CameraObject, kind: TransferKind): TransferTarget = withContext(Dispatchers.IO) {
        require(key.matches(Regex("[a-zA-Z0-9_-]+"))) { "Unsafe transfer key" }
        val directory = directoryFor(kind).canonicalFile
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create transfer directory")
        checkSpace(info.size, usableSpace(directory))
        val target = uniqueFile(directory, safeFilename(info.name))
        val part = File(directory, target.name + ".part")
        val journal = journalFile(stateDirectory, key, kind)
        // 上一次传输被打断（相机掉线/被杀）后，本目录会留下自己的 .part，而 journal 已在 abort/release 里删掉。
        // 没有 journal 就没有可恢复的传输，这份 partial 只能清掉；否则每次重试都会因它立刻失败
        // （真机 logs_1795847176992：P1028074 连续多次“开始传输”后瞬时失败，从未产生任何 USB 事务）。
        if (part.exists() && !journal.exists() && !part.delete()) {
            throw IOException("Cannot remove stale partial transfer")
        }
        val properties = Properties().apply {
            setProperty("target", target.absolutePath)
            setProperty("partial", part.absolutePath)
            setProperty("size", info.size.toString())
            setProperty("name", info.name)
            setProperty("format", info.format.toString())
        }
        val prepared = TransferPreparation.open(part, journal, properties, suppression,
            target.name, info.size, target.absolutePath)
        val reservation = prepared.reservation
        object : TransferTarget {
            override val destinationHint = target.absolutePath
            private val output = prepared.output
            private var finished = false
            private var committed = false
            override fun write(bytes: ByteArray, length: Int) { check(!finished); output.write(bytes, 0, length) }
            override fun finish() {
                if (finished) return
                output.flush(); output.fd.sync(); output.close(); finished = true
                if (part.length() != info.size) throw IOException("Partial file size mismatch")
                if (info.format == 0x3801L && !jpegValidator(part)) throw IOException("JPEG is not decodable")
            }
            override suspend fun commit(): DownloadedTransfer = withContext(Dispatchers.IO) {
                check(finished)
                if (target.exists()) throw IOException("Output name was occupied during transfer")
                properties.setProperty("ready", "true")
                writeJournal(journal, properties)
                // No REPLACE_EXISTING: an independently created file must never be overwritten.
                Files.move(part.toPath(), target.toPath())
                committed = true
                properties.setProperty("committed", "true")
                writeJournal(journal, properties)
                suppression.complete(reservation, target.absolutePath, target.length())
                DownloadedTransfer(target.absolutePath, target.absolutePath, target.length())
            }
            override suspend fun abort() = withContext(Dispatchers.IO) {
                close()
                if (!committed) {
                    if (part.exists() && !part.delete()) throw IOException("Cannot remove partial transfer")
                    journal.delete()
                    suppression.release(reservation)
                }
                Unit
            }
            override fun close() { output.close() }
        }
    }

    override suspend fun materializeOriginal(record: TransferRecord): String? = withContext(Dispatchers.IO) {
        record.targetPath.takeIf { it.isNotBlank() && File(it).isFile }
    }

    override suspend fun committedOriginalExists(record: TransferRecord, targetPath: String): Boolean =
        withContext(Dispatchers.IO) { targetPath.isNotBlank() && File(targetPath).isFile }

    override suspend fun recover(record: TransferRecord): DownloadedTransfer? = withContext(Dispatchers.IO) {
        val journal = journalFile(stateDirectory, record.key, record.kind)
        val properties = readJournal(journal)
        val targetPath = properties?.getProperty("target") ?: record.targetPath
        val target = targetPath.takeIf { it.isNotBlank() && !it.startsWith("content:") }?.let(::File)
        val partialExists = properties?.getProperty("partial")?.let(::File)?.exists() == true
        val ownsCommittedTarget = properties?.getProperty("committed") == "true" ||
            (properties?.getProperty("ready") == "true" && !partialExists) ||
            (properties == null && record.state in setOf(TransferState.DOWNLOADED, TransferState.SUBMITTING,
                TransferState.SUBMISSION_FAILED, TransferState.DONE))
        if (ownsCommittedTarget && target != null && target.isFile && target.length() == record.size &&
            (!record.name.isJpegName() || jpegValidator(target))) {
            properties?.getProperty("reservation")?.let { suppression.complete(it, target.absolutePath, target.length()) }
            return@withContext DownloadedTransfer(target.absolutePath, target.absolutePath, target.length())
        }
        // Never append to unknown partial bytes. Full-object protocol recovery always starts over.
        properties?.getProperty("partial")?.let { path ->
            val part = File(path)
            if (part.exists() && !part.delete()) throw IOException("Cannot clean interrupted partial transfer")
        }
        properties?.getProperty("reservation")?.let(suppression::release)
        journal.delete()
        null
    }

    companion object {
        fun safeFilename(raw: String): String {
            val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
            var name = Normalizer.normalize(leaf, Normalizer.Form.NFC)
                .replace(Regex("[\\x00-\\x1f\\x7f<>:\"/\\\\|?*]"), "_").trim().trimEnd('.', ' ')
            if (name.isBlank() || name == "." || name == "..") name = "camera-object"
            val stem = name.substringBefore('.').uppercase()
            if (stem in setOf("CON", "PRN", "AUX", "NUL") || stem.matches(Regex("(?:COM|LPT)[1-9]"))) name = "_$name"
            // Stay well below common filesystem byte limits while preserving the original extension.
            val extension = name.substringAfterLast('.', "").take(12)
            val suffix = if ('.' in name && extension.isNotEmpty()) ".$extension" else ""
            var base = if (suffix.isEmpty()) name else name.dropLast(suffix.length)
            while ((base + suffix).toByteArray(Charsets.UTF_8).size > 180) base = base.dropLast(1)
            return base + suffix
        }
        internal fun uniqueFile(directory: File, name: String): File {
            val extension = name.substringAfterLast('.', "")
            val base = if (extension.isEmpty()) name else name.dropLast(extension.length + 1)
            for (index in 0..100_000) {
                val suffix = if (index == 0) "" else "_$index"
                val candidate = File(directory, base + suffix + if (extension.isEmpty()) "" else ".$extension")
                if (!candidate.exists() && !File(directory, candidate.name + ".part").exists()) return candidate
            }
            throw IOException("Cannot allocate a unique output name")
        }
        fun checkSpace(size: Long, available: Long) {
            require(size >= 0) { "Invalid object size" }
            val margin = size / 10 + if (size % 10 == 0L) 0 else 1
            if (size > Long.MAX_VALUE - margin || size + margin >= available) throw IOException("Not enough free space (10% headroom required)")
        }
        internal fun journalFile(root: File, key: String, kind: TransferKind): File {
            val directory = File(root, ".tether-partials")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create transfer journal directory")
            return File(directory, "${kind.name}-$key.properties")
        }
        internal fun writeJournal(file: File, properties: Properties) {
            val part = File(file.parentFile, file.name + ".part")
            FileOutputStream(part).use { properties.store(it, "TETHER transaction"); it.fd.sync() }
            if (!part.renameTo(file)) {
                FileOutputStream(file).use { properties.store(it, "TETHER transaction"); it.fd.sync() }
                part.delete()
            }
        }
        internal fun readJournal(file: File): Properties? = if (file.isFile) Properties().apply {
            file.inputStream().use { load(it) }
        } else null
        internal fun String.isJpegName() = substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")
    }
}
