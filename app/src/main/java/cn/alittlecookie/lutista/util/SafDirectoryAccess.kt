package cn.alittlecookie.lutista.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** The picker and background transfers must use the same read/write tree grant. */
object SafDirectoryAccess {
    private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private const val RESELECT = "所选文件夹缺少读写权限，请重新选择并授权；自动回传请在主页重选输入文件夹"

    /** Call before saving a picker result. Never save a tree whose grant could not be retained. */
    fun persistReadWrite(context: Context, treeUri: Uri) {
        requireTree(treeUri)
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, READ_WRITE)
            requireWritableTree(context, treeUri)
        } catch (failure: SecurityException) {
            throw permissionFailure(failure)
        }
    }

    /** Existing read-only settings can be upgraded only while a real write grant is still held. */
    fun writableDirectory(context: Context, location: String): DocumentFile {
        val path = SafDirectoryPath.parse(location)
        val treeUri = Uri.parse(path.treeUri)
        requireTree(treeUri)
        try {
            if (hasReadWrite(context, treeUri)) {
                // A current picker grant may contain WRITE even when an old app retained READ only.
                // takePersistableUriPermission cannot manufacture access once that grant is gone.
                try {
                    context.contentResolver.takePersistableUriPermission(treeUri, READ_WRITE)
                } catch (_: SecurityException) {
                    // An already valid grant can be used for this transaction; recheck on every use.
                }
            }
            var directory = requireWritableTree(context, treeUri)
            for (segment in path.subdirectories) {
                val existing = directory.findFile(segment)
                directory = if (existing != null) {
                    if (!existing.isDirectory) throw IOException("SAF 子路径已被同名文件占用")
                    existing
                } else {
                    directory.createDirectory(segment) ?: throw IOException("无法创建 SAF 子文件夹")
                }
                requireCreatableDirectory(context, directory)
            }
            return directory
        } catch (failure: SecurityException) {
            throw permissionFailure(failure)
        }
    }

    fun permissionFailure(cause: SecurityException): IOException = IOException(RESELECT, cause)

    private fun requireWritableTree(context: Context, treeUri: Uri): DocumentFile {
        if (!hasReadWrite(context, treeUri)) throw IOException(RESELECT)
        val directory = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法打开 SAF 文件夹，请重新选择")
        requireCreatableDirectory(context, directory)
        return directory
    }

    private fun requireCreatableDirectory(context: Context, directory: DocumentFile) {
        if (!directory.isDirectory || !directory.canRead() || !directory.canWrite()) throw IOException(RESELECT)
        val flags = context.contentResolver.query(
            directory.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0 } ?: 0
        // DocumentFile.canWrite also accepts delete-only documents, which cannot receive a new photo.
        if (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
            throw IOException("所选 SAF 文件夹不允许创建文件，请重新选择可写文件夹")
        }
    }

    private fun hasReadWrite(context: Context, uri: Uri): Boolean =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun requireTree(uri: Uri) {
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) {
            throw IOException("不是有效的 SAF 文件夹，请重新选择")
        }
    }
}

/** Keep the persisted tree URI intact; the optional suffix is an app-owned relative directory path. */
internal data class SafDirectoryPath(val treeUri: String, val subdirectories: List<String>) {
    companion object {
        fun parse(location: String): SafDirectoryPath {
            val tree = location.substringBefore('|')
            require(tree.startsWith("content://")) { "Not a SAF location" }
            val segments = location.substringAfter('|', "").split('/').filter(String::isNotEmpty)
            require(segments.none { it == "." || it == ".." || it.any { char -> char == '\\' || char == '|' || char == '\u0000' } }) {
                "Invalid SAF relative directory"
            }
            return SafDirectoryPath(tree, segments)
        }
    }
}
