package io.github.pportilla.upmmail.data.files

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.util.UUID

/**
 * Layout of the app's private files, and who owns them:
 *
 * - `attachments/<messageId>/<attachmentId>/<name>`: downloaded attachments. A cache: any file
 *   may go at any time ([cleanupAttachments], [trimAttachments]), as it is downloaded again when
 *   needed. Drafts and queued messages never point here; the only exception is drafts and queued
 *   messages saved by older versions, whose files are kept while referenced.
 * - `compose/<uuid>/<name>`: the files of local drafts and outbox messages: everything attached
 *   while composing, including the attachments of a forwarded message or an edited server draft
 *   ([newComposeCopy]). Kept while a draft or outbox message refers to them ([cleanupCompose]),
 *   so a queued message can still be sent after a restart and after its source mail is gone.
 * - `sent/`: sent messages waiting for their copy in the Sent folder, owned by their pending
 *   operation (see the sync engine).
 */
class LocalFiles(val filesDir: File) {
    val attachmentsDir: File get() = File(filesDir, ATTACHMENTS)
    val composeDir: File get() = File(filesDir, COMPOSE)

    fun attachmentFile(messageId: Long, attachmentId: Long, fileName: String): File =
        File(File(File(attachmentsDir, messageId.toString()), attachmentId.toString()), sanitizeFileName(fileName))

    /** A new, empty location for a file attached while composing. */
    fun newComposeFile(fileName: String): File {
        val dir = File(composeDir, UUID.randomUUID().toString())
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        return File(dir, sanitizeFileName(fileName))
    }

    /**
     * A compose-area copy of [source] (e.g. a cached attachment), owned by a draft from now on. A
     * hard link where the filesystem allows one, so the data is not stored twice.
     */
    fun newComposeCopy(source: File, fileName: String = source.name): File {
        val target = newComposeFile(fileName)
        try {
            try {
                Files.createLink(target.toPath(), source.toPath())
            } catch (_: Exception) {
                source.copyTo(target, overwrite = true)
            }
        } catch (e: Exception) {
            target.parentFile?.deleteRecursively()
            throw e
        }
        return target
    }

    fun isComposeFile(file: File): Boolean = isInside(file, composeDir)

    /** True if [file] is inside the attachment cache or the compose area. */
    fun isServable(file: File): Boolean = isInside(file, attachmentsDir) || isInside(file, composeDir)

    /** Deletes the downloaded attachments of [messageIds]. */
    fun deleteMessageFiles(messageIds: Collection<Long>) {
        messageIds.forEach { File(attachmentsDir, it.toString()).deleteRecursively() }
    }

    /**
     * Deletes compose copies among [uris] that are not in [stillReferenced]. Files outside the
     * compose area (e.g. cached attachments referenced by an old draft) are never touched.
     */
    fun deleteComposeFiles(uris: Collection<String>, stillReferenced: Set<String>) {
        for (uri in uris) {
            if (uri in stillReferenced) continue
            val file = fileOf(uri) ?: continue
            if (!isComposeFile(file)) continue
            file.delete()
            val parent = file.parentFile
            if (parent != null && parent != composeDir && parent.list().isNullOrEmpty()) parent.delete()
        }
    }

    /**
     * Removes cached attachments of messages that no longer exist ([existing] filters ids), except
     * directories holding a file that a draft or outbox message still points to ([referenced]
     * URIs).
     */
    fun cleanupAttachments(referenced: Set<String>, existing: (Collection<Long>) -> Set<Long>) {
        val dirs = attachmentsDir.listFiles()?.filter { it.isDirectory } ?: return
        val kept = canonicalFiles(referenced)
        val removable = dirs.filter { dir -> kept.none { isInside(it, dir) } }
        val ids = removable.mapNotNull { dir -> dir.name.toLongOrNull()?.let { it to dir } }
        val alive = existing(ids.map { it.first })
        ids.filter { it.first !in alive }.forEach { it.second.deleteRecursively() }
        removable.filter { it.name.toLongOrNull() == null }.forEach { it.deleteRecursively() }
    }

    /**
     * Keeps the attachment cache under [maxBytes]: the least recently used files go first (a file
     * is "used" when it is downloaded or opened again, see [touch]). Files younger than
     * [minAgeMillis] (possibly open in a viewer right now) and files in [referenced] stay.
     * Returns how many files were deleted.
     */
    fun trimAttachments(referenced: Set<String>, maxBytes: Long, now: Long, minAgeMillis: Long): Int {
        val all = attachmentsDir.walkTopDown().filter { it.isFile }.toList()
        var total = all.sumOf { it.length() }
        if (total <= maxBytes) return 0
        val kept = canonicalFiles(referenced)
        var deleted = 0
        for (file in all.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (now - file.lastModified() < minAgeMillis || file.canonicalFile in kept) continue
            val size = file.length()
            if (file.delete()) {
                total -= size
                deleted++
                // Empty <attachmentId> and <messageId> directories go as well.
                var dir = file.parentFile
                while (dir != null && dir != attachmentsDir && dir.list().isNullOrEmpty()) {
                    dir.delete()
                    dir = dir.parentFile
                }
            }
        }
        return deleted
    }

    /** Marks a cached file as just used, for [trimAttachments]. */
    fun touch(file: File, now: Long) {
        file.setLastModified(now)
    }

    /**
     * Removes compose copies that no draft or outbox message references, once they are older
     * than [minAgeMillis] (younger ones may belong to a compose screen not saved yet).
     */
    fun cleanupCompose(referenced: Set<String>, now: Long, minAgeMillis: Long) {
        val referencedFiles = canonicalFiles(referenced)
        composeDir.listFiles()?.forEach { dir ->
            if (now - dir.lastModified() < minAgeMillis) return@forEach
            val files = dir.listFiles()?.toList().orEmpty()
            if (files.none { it.canonicalFile in referencedFiles }) dir.deleteRecursively()
        }
    }

    private fun canonicalFiles(uris: Collection<String>): Set<File> =
        uris.mapNotNullTo(HashSet()) { uri -> fileOf(uri)?.let { runCatching { it.canonicalFile }.getOrNull() } }

    private fun isInside(file: File, dir: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val root = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        return path.startsWith(root + File.separator)
    }

    companion object {
        private const val ATTACHMENTS = "attachments"
        private const val COMPOSE = "compose"
        private const val MAX_NAME_LENGTH = 120
        private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        /** A file name safe on every filesystem: no separators or reserved characters, bounded length. */
        fun sanitizeFileName(name: String): String {
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(FORBIDDEN, "_")
                .trim()
                .trim('.', ' ')
            if (cleaned.isEmpty()) return "attachment"
            if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
            val dot = cleaned.lastIndexOf('.')
            val extension = if (dot > 0 && cleaned.length - dot <= 10) cleaned.substring(dot) else ""
            return cleaned.take(MAX_NAME_LENGTH - extension.length) + extension
        }

        /** `file:///…` URI for [file], percent-encoded. */
        fun fileUri(file: File): String = "file://" + file.absoluteFile.toURI().rawPath

        /** The file behind a `file:` URI, or null for any other scheme. */
        fun fileOf(uri: String): File? {
            if (!uri.startsWith("file:")) return null
            return runCatching { File(URI(uri)) }.getOrNull()
                ?: File(uri.removePrefix("file://").removePrefix("file:"))
        }
    }
}

/** Opens the data behind an attachment URI (file:// here; content:// on Android). */
fun interface AttachmentOpener {
    fun open(uri: String): InputStream
}

/** [AttachmentOpener] for file:// URIs only (JVM tests). */
object FileAttachmentOpener : AttachmentOpener {
    override fun open(uri: String): InputStream {
        val file = LocalFiles.fileOf(uri) ?: throw IOException("Unsupported attachment URI: $uri")
        return FileInputStream(file)
    }
}
