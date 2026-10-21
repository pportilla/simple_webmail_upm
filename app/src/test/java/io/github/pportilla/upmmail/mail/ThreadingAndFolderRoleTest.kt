package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.FolderRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadingTest {
    @Test
    fun usesRootReferenceThenInReplyToThenOwnId() {
        assertEquals("<root@upm.es>", Threading.threadId("<c@upm.es>", "<b@upm.es>", listOf("<ROOT@upm.es>", "<b@upm.es>"), "f"))
        assertEquals("<b@upm.es>", Threading.threadId("<c@upm.es>", "<B@upm.es>", emptyList(), "f"))
        assertEquals("<c@upm.es>", Threading.threadId("<C@UPM.ES>", null, emptyList(), "f"))
        assertEquals("fallback:1", Threading.threadId(null, " ", listOf(" "), "fallback:1"))
    }

    @Test
    fun aWholeConversationSharesOneKey() {
        val root = Threading.threadId("<A1@upm.es>", null, emptyList(), "x")
        val reply = Threading.threadId("<a2@upm.es>", "<a1@upm.es>", listOf("<a1@upm.es>"), "x")
        val replyToReply = Threading.threadId("<a3@upm.es>", "<a2@upm.es>", listOf("<a1@upm.es>", "<a2@upm.es>"), "x")
        assertEquals(root, reply)
        assertEquals(root, replyToReply)
    }

    @Test
    fun toleratesIdsWithoutBrackets() {
        assertEquals("<root@upm.es>", Threading.threadId(null, "root@upm.es", emptyList(), "x"))
        assertEquals("<a@x>", Threading.threadId(null, "<a@x> (comment)", emptyList(), "x"))
    }
}

class FolderRoleDetectorTest {
    @Test
    fun inboxIsAlwaysInbox() {
        assertEquals(FolderRole.INBOX, FolderRoleDetector.detect("INBOX", emptySet()))
        assertEquals(FolderRole.INBOX, FolderRoleDetector.detect("Inbox", setOf("\\sent")))
    }

    @Test
    fun specialUseWins() {
        assertEquals(FolderRole.SENT, FolderRoleDetector.detect("Correo/Salida", setOf("\\hasnochildren", "\\sent")))
        assertEquals(FolderRole.DRAFTS, FolderRoleDetector.detect("X", setOf("\\Drafts")))
        assertEquals(FolderRole.TRASH, FolderRoleDetector.detect("Enviados", setOf("\\trash")))
        assertEquals(FolderRole.JUNK, FolderRoleDetector.detect("X", setOf("\\junk")))
        assertEquals(FolderRole.ARCHIVE, FolderRoleDetector.detect("X", setOf("\\archive")))
    }

    @Test
    fun englishAndSpanishNames() {
        val cases = mapOf(
            "Sent" to FolderRole.SENT,
            "INBOX.Sent Items" to FolderRole.SENT,
            "[Gmail]/Sent Mail" to FolderRole.SENT,
            "Enviados" to FolderRole.SENT,
            "Elementos enviados" to FolderRole.SENT,
            "INBOX/Mensajes enviados" to FolderRole.SENT,
            "Drafts" to FolderRole.DRAFTS,
            "Borradores" to FolderRole.DRAFTS,
            "Trash" to FolderRole.TRASH,
            "Deleted Items" to FolderRole.TRASH,
            "Papelera" to FolderRole.TRASH,
            "Elementos eliminados" to FolderRole.TRASH,
            "Junk" to FolderRole.JUNK,
            "Spam" to FolderRole.JUNK,
            "Junk E-mail" to FolderRole.JUNK,
            "Correo no deseado" to FolderRole.JUNK,
            "Bulk Mail" to FolderRole.JUNK,
            "Archive" to FolderRole.ARCHIVE,
            "Archivo" to FolderRole.ARCHIVE,
            "INBOX.Archivados" to FolderRole.ARCHIVE,
            "ELEMENTOS  ENVIADOS" to FolderRole.SENT,
            "Proyectos" to FolderRole.OTHER,
            "Sent/Proyectos" to FolderRole.OTHER,
            "" to FolderRole.OTHER,
        )
        cases.forEach { (name, role) -> assertEquals(name, role, FolderRoleDetector.detect(name, emptySet())) }
    }
}
