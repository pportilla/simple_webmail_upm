package io.github.pportilla.upmmail.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.pportilla.upmmail.data.files.LocalFiles
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access for other apps (viewers, share targets) to downloaded attachments and compose
 * copies, i.e. files under `filesDir/attachments` and `filesDir/compose`. The provider is not
 * exported: callers get access through `FLAG_GRANT_READ_URI_PERMISSION` on the intent.
 *
 * URIs look like `content://<package>.attachments/attachments/12/34/report.pdf`, the path being
 * relative to filesDir. Every path is canonicalised and checked, so `..` cannot escape.
 */
class AttachmentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri) ?: return null
        val columns = projection
            ?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray()
            ?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column -> if (column == OpenableColumns.DISPLAY_NAME) file.name else file.length() }
        return MatrixCursor(columns, 1).apply { addRow(row.toTypedArray()) }
    }

    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment ?: return null
        return guessMimeType(name) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Attachments are read-only")
        val file = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Attachments are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Attachments are read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Attachments are read-only")

    /** The file behind [uri] if it is a regular file inside a servable area, else null. */
    private fun fileFor(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.isNullOrEmpty() || segments.any { it == ".." || it == "." || it.isEmpty() }) return null
        val file = runCatching { File(context.filesDir, segments.joinToString(File.separator)).canonicalFile }.getOrNull()
            ?: return null
        val files = LocalFiles(context.filesDir)
        return file.takeIf { files.isServable(it) && it.isFile }
    }

    companion object {
        /** content:// URI for a file under filesDir/attachments or filesDir/compose. */
        fun uriFor(context: Context, file: File): Uri {
            val root = context.filesDir.canonicalFile
            val target = file.canonicalFile
            val prefix = root.path + File.separator
            require(target.path.startsWith(prefix) && LocalFiles(context.filesDir).isServable(target)) {
                "Not a shareable attachment: $file"
            }
            val builder = Uri.Builder().scheme("content").authority(context.packageName + AUTHORITY_SUFFIX)
            target.path.removePrefix(prefix).split(File.separatorChar).forEach { builder.appendPath(it) }
            return builder.build()
        }

        private const val AUTHORITY_SUFFIX = ".attachments"
    }
}
