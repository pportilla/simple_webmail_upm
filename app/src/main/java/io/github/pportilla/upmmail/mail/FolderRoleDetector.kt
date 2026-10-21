package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.FolderRole

object FolderRoleDetector {
    /**
     * Role from SPECIAL-USE attributes (\Sent, \Drafts, \Trash, \Junk, \Archive) or, failing
     * that, from well-known English/Spanish names of the last path segment ("Enviados",
     * "Elementos enviados", "Papelera", "Correo no deseado"...). "INBOX" is always INBOX.
     */
    fun detect(fullName: String, attributes: Set<String>): FolderRole = TODO()
}
