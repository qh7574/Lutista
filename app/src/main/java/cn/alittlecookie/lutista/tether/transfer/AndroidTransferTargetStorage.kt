package cn.alittlecookie.lutista.tether.transfer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.tether.objects.CameraObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/** Ordinary directories and persisted SAF trees share the same transactional contract.
 * SAF uses a durable local original (not cache); processing/EXIF never gets a content URI as File.
 */
class AndroidTransferTargetStorage(
    private val context: Context,
    private val settings: SettingsManager,
    private val suppression: TetherProcessingSuppression = TetherProcessingSuppression.shared,
    private val availableSafBytes: (Uri) -> Long? = { queryAvailableBytes(context, it) },
) : TransferTargetStorage {
    private val stateRoot = File(context.filesDir, "tether-transfer-state")
    private val files = FileTransferTargetStorage(
        directoryFor = { kind ->
            val location = location(kind)
            check(!location.startsWith("content://"))
            File(location)
        }, stateDirectory = stateRoot, jpegValidator = ::isDecodableJpeg, suppression = suppression
    )

    init { suppression.configure(File(context.filesDir, "tether-monitor-exclusions.properties")) }

    override suspend fun prepare(key: String, info: CameraObject, kind: TransferKind): TransferTarget = withContext(Dispatchers.IO) {
        val location = location(kind)
        if (!location.startsWith("content://")) return@withContext files.prepare(key, info, kind)
        require(key.matches(Regex("[a-zA-Z0-9_-]+")))
        val treeUri = Uri.parse(location.substringBefore('|'))
        var directory = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid SAF transfer directory")
        if (!directory.canWrite()) throw IOException("SAF transfer directory is not writable")
        location.substringAfter('|', "").split('/').filter { it.isNotEmpty() }.forEach { segment ->
            directory = directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(segment) ?: throw IOException("Cannot create SAF transfer directory")
        }
        val available = availableSafBytes(treeUri) ?: throw IOException("SAF provider does not report available space")
        FileTransferTargetStorage.checkSpace(info.size, available)
        val sourceDirectory = File(context.filesDir, ".tether-sources/${kind.name}-$key")
        if (!sourceDirectory.isDirectory && !sourceDirectory.mkdirs()) throw IOException("Cannot create original staging directory")
        FileTransferTargetStorage.checkSpace(info.size, sourceDirectory.usableSpace)
        val name = uniqueSafName(directory, FileTransferTargetStorage.safeFilename(info.name))
        val local = File(sourceDirectory, name)
        val part = File(sourceDirectory, name + ".part")
        val journal = FileTransferTargetStorage.journalFile(stateRoot, key, kind)
        val properties = Properties().apply {
            setProperty("type", "saf"); setProperty("partial", part.absolutePath)
            setProperty("local", local.absolutePath); setProperty("size", info.size.toString())
            setProperty("name", name); setProperty("format", info.format.toString())
        }
        val prepared = TransferPreparation.open(part, journal, properties, suppression, name, info.size)
        val token = prepared.reservation
        val destination = directory
        object : TransferTarget {
            override val destinationHint = treeUri.toString()
            private val output = prepared.output
            private var finished = false
            private var committed = false
            private var document: DocumentFile? = null
            override fun write(bytes: ByteArray, length: Int) { check(!finished); output.write(bytes, 0, length) }
            override fun finish() {
                if (finished) return
                output.flush(); output.fd.sync(); output.close(); finished = true
                if (part.length() != info.size) throw IOException("SAF staged file size mismatch")
                if (info.format == 0x3801L && !isDecodableJpeg(part)) throw IOException("JPEG is not decodable")
                if (!part.renameTo(local)) throw IOException("Cannot retain SAF original")
            }
            override suspend fun commit(): DownloadedTransfer = withContext(Dispatchers.IO) {
                check(finished)
                val doc = destination.createFile(mimeType(info), name) ?: throw IOException("Cannot create SAF output")
                document = doc
                suppression.bind(token, doc.uri.toString(), doc.name)
                // Persist URI before any payload: recovery deletes incomplete visible documents.
                properties.setProperty("safUri", doc.uri.toString())
                FileTransferTargetStorage.writeJournal(journal, properties)
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { sink ->
                    local.inputStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val length = source.read(buffer)
                            if (length < 0) break
                            sink.write(buffer, 0, length)
                        }
                    }
                } ?: throw IOException("Cannot open SAF output")
                if (documentSize(context, doc.uri) != info.size) throw IOException("SAF output size mismatch")
                properties.setProperty("committed", "true")
                FileTransferTargetStorage.writeJournal(journal, properties)
                committed = true
                suppression.complete(token, doc.uri.toString(), info.size)
                DownloadedTransfer(doc.uri.toString(), local.absolutePath, info.size)
            }
            override suspend fun abort() = withContext(Dispatchers.IO) {
                close()
                if (!committed) {
                    val doc = document
                    if (doc != null && doc.exists() && !doc.delete()) throw IOException("Cannot clean partial SAF output")
                    if (part.exists() && !part.delete()) throw IOException("Cannot clean partial SAF staging")
                    local.delete(); journal.delete(); suppression.release(token)
                }
                Unit
            }
            override fun close() { output.close() }
        }
    }

    override suspend fun recover(record: TransferRecord): DownloadedTransfer? = withContext(Dispatchers.IO) {
        val journal = FileTransferTargetStorage.journalFile(stateRoot, record.key, record.kind)
        val properties = FileTransferTargetStorage.readJournal(journal)
        if (properties?.getProperty("type") != "saf") return@withContext files.recover(record)
        val local = properties.getProperty("local")?.let(::File)
        val uri = properties.getProperty("safUri")?.let(Uri::parse)
        val format = properties.getProperty("format")?.toLongOrNull()
        if (local?.isFile == true && local.length() == record.size && uri != null &&
            documentSize(context, uri) == record.size && (format != 0x3801L || isDecodableJpeg(local))) {
            properties.getProperty("reservation")?.let { suppression.complete(it, uri.toString(), record.size) }
            return@withContext DownloadedTransfer(uri.toString(), local.absolutePath, record.size)
        }
        if (uri != null) {
            val doc = DocumentFile.fromSingleUri(context, uri)
            if (doc?.exists() == true && !doc.delete()) throw IOException("Cannot remove interrupted SAF output")
        }
        properties.getProperty("partial")?.let(::File)?.let { if (it.exists() && !it.delete()) throw IOException("Cannot clean SAF partial") }
        local?.delete()
        properties.getProperty("reservation")?.let(suppression::release)
        journal.delete()
        null
    }

    private suspend fun location(kind: TransferKind): String {
        val path = if (kind == TransferKind.AUTO) settings.monitorPath.first() else settings.outputPath.first()
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "LumixTether")
        if (kind != TransferKind.AUTO) return path.ifBlank { fallback.absolutePath }
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) // Phone date, not camera clock.
        return if (path.startsWith("content://")) "$path|LumixTether/$date"
        else File(File(path.ifBlank { fallback.parentFile!!.absolutePath }, "LumixTether"), date).absolutePath
    }

    companion object {
        private fun isDecodableJpeg(file: File): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 1_048_576L) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
            val valid = bitmap.width > 0 && bitmap.height > 0
            bitmap.recycle()
            return valid
        }
        private fun mimeType(info: CameraObject) = when {
            info.format == 0x3801L -> "image/jpeg"
            info.name.substringAfterLast('.', "").equals("mov", true) -> "video/quicktime"
            else -> "application/octet-stream"
        }
        private fun uniqueSafName(dir: DocumentFile, name: String): String {
            val ext = name.substringAfterLast('.', "")
            val base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
            for (index in 0..100_000) {
                val candidate = base + (if (index == 0) "" else "_$index") + (if (ext.isEmpty()) "" else ".$ext")
                if (dir.findFile(candidate) == null) return candidate
            }
            throw IOException("Cannot allocate a unique SAF output name")
        }
        private fun documentSize(context: Context, uri: Uri): Long? =
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        private fun queryAvailableBytes(context: Context, tree: Uri): Long? {
            return try {
            val authority = tree.authority ?: return null
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            context.contentResolver.query(DocumentsContract.buildRootsUri(authority), arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val root = cursor.getString(0) ?: continue
                    if ((treeId == root || treeId.startsWith("$root/") ||
                                (root.endsWith(':') && treeId.startsWith(root))) && !cursor.isNull(1)) {
                        return@use cursor.getLong(1).takeIf { it >= 0 }
                    }
                }
                null
            }
            } catch (_: Exception) { null }
        }
    }
}
