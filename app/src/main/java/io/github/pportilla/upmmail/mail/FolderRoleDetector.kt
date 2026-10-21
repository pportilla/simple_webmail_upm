package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.FolderRole
import java.util.Locale

object FolderRoleDetector {
    private val SPECIAL_USE = mapOf(
        "\\sent" to FolderRole.SENT,
        "\\drafts" to FolderRole.DRAFTS,
        "\\trash" to FolderRole.TRASH,
        "\\junk" to FolderRole.JUNK,
        "\\archive" to FolderRole.ARCHIVE,
    )

    private val NAMES: Map<String, FolderRole> = buildMap {
        listOf(
            "sent", "sent items", "sent messages", "sent mail", "sent-mail", "sentmail",
            "enviados", "enviado", "elementos enviados", "mensajes enviados", "correo enviado", "correos enviados",
        ).forEach { put(it, FolderRole.SENT) }
        listOf("drafts", "draft", "borradores", "borrador").forEach { put(it, FolderRole.DRAFTS) }
        listOf(
            "trash", "deleted", "deleted items", "deleted messages", "bin", "papelera", "papelera de reciclaje",
            "elementos eliminados", "eliminados", "mensajes eliminados", "correo eliminado",
        ).forEach { put(it, FolderRole.TRASH) }
        listOf(
            "junk", "spam", "junk e-mail", "junk email", "junk mail", "bulk mail", "correo no deseado",
            "no deseado", "correo basura", "spam mail",
        ).forEach { put(it, FolderRole.JUNK) }
        listOf("archive", "archives", "archivo", "archivos", "archivados", "archivado").forEach {
            put(it, FolderRole.ARCHIVE)
        }
    }

    /**
     * Role from SPECIAL-USE attributes (\Sent, \Drafts, \Trash, \Junk, \Archive) or, failing
     * that, from well-known English/Spanish names of the last path segment ("Enviados",
     * "Elementos enviados", "Papelera", "Correo no deseado"...). "INBOX" is always INBOX.
     */
    fun detect(fullName: String, attributes: Set<String>): FolderRole {
        val trimmed = fullName.trim()
        if (trimmed.equals("INBOX", ignoreCase = true)) return FolderRole.INBOX
        for (attribute in attributes) {
            SPECIAL_USE[attribute.trim().lowercase(Locale.ROOT)]?.let { return it }
        }
        val lastSegment = trimmed.split('/', '.', '\\').lastOrNull { it.isNotBlank() } ?: return FolderRole.OTHER
        val normalized = lastSegment.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return NAMES[normalized] ?: FolderRole.OTHER
    }
}
