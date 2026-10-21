package io.github.pportilla.upmmail.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.pportilla.upmmail.data.files.AttachmentOpener
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.repository.AttachmentImporter
import io.github.pportilla.upmmail.model.OutgoingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale

/**
 * Opens attachment data for sending: content:// through the ContentResolver, file:// only inside
 * the app's own attachment and compose areas (a shared file:// URI must never make the app attach
 * its private files, such as the database).
 */
class AndroidAttachmentOpener(private val context: Context, private val files: LocalFiles) : AttachmentOpener {
    override fun open(uri: String): InputStream {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "content" -> context.contentResolver.openInputStream(parsed) ?: throw FileNotFoundException(uri)
            "file" -> {
                val file = LocalFiles.fileOf(uri) ?: throw FileNotFoundException(uri)
                if (!files.isServable(file)) throw SecurityException("Not an app attachment: $uri")
                FileInputStream(file)
            }
            else -> throw FileNotFoundException("Unsupported attachment URI: $uri")
        }
    }
}

/**
 * Copies picked or shared content into `filesDir/compose/<uuid>/<name>` and describes the copy,
 * so the message can still be sent after a restart, when the original grant is gone.
 */
class AndroidAttachmentImporter(private val context: Context, private val files: LocalFiles) : AttachmentImporter {

    override suspend fun import(uri: String): OutgoingAttachment? = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "file" -> describeOwnFile(uri)
            "content" -> copyContent(parsed)
            else -> null
        }
    }

    /** Only the app's own files (e.g. cached attachments of a forwarded message) are accepted. */
    private fun describeOwnFile(uri: String): OutgoingAttachment? {
        val file = LocalFiles.fileOf(uri) ?: return null
        if (!files.isServable(file) || !file.isFile) return null
        return OutgoingAttachment(LocalFiles.fileUri(file), file.name, mimeTypeOf(file.name, null), file.length())
    }

    private fun copyContent(uri: Uri): OutgoingAttachment? {
        val resolver = context.contentResolver
        var displayName: String? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {
            // Some providers do not support queries; the name falls back to the URI.
        }
        val reportedType = runCatching { resolver.getType(uri) }.getOrNull()
        var name = LocalFiles.sanitizeFileName(displayName ?: uri.lastPathSegment ?: "attachment")
        if (!name.contains('.') && reportedType != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(reportedType)?.let { name = "$name.$it" }
        }
        val target = files.newComposeFile(name)
        try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            target.parentFile?.deleteRecursively()
            return null
        }
        return OutgoingAttachment(
            uri = LocalFiles.fileUri(target),
            fileName = target.name,
            mimeType = mimeTypeOf(target.name, reportedType),
            size = target.length(),
        )
    }

    private fun mimeTypeOf(name: String, reported: String?): String {
        if (!reported.isNullOrBlank() && reported != "application/octet-stream") return reported
        return guessMimeType(name) ?: reported ?: "application/octet-stream"
    }
}

/** MIME type for a file name's extension, or null when unknown. */
fun guessMimeType(fileName: String): String? {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}
