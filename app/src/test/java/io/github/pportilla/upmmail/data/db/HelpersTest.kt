package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.data.files.LocalFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HelpersTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun foldingAndMatchExpressions() {
        assertEquals("perez nunez", SearchText.fold("Pérez NÚÑEZ"))
        assertEquals("reunion* depart*", SearchText.matchExpression("Reunión  DEPART"))
        assertEquals("a* b*", SearchText.matchExpression("\"a\" :(b*) -a"))
        assertNull(SearchText.matchExpression("  \"*()  "))
        assertTrue(SearchText.matchesWordPrefixes("ana per", "Ana Pérez", "ana.perez@upm.es"))
        assertTrue(SearchText.matchesWordPrefixes("perez@", null, "ana.perez@upm.es"))
        assertFalse(SearchText.matchesWordPrefixes("nap", "Ana Pérez", "ana.perez@upm.es"))
        assertEquals("100\\%\\_\\\\", SearchText.escapeLike("100%_\\"))
    }

    @Test
    fun fileNamesAreSanitised() {
        assertEquals("passwd", LocalFiles.sanitizeFileName("../../etc/passwd"))
        assertEquals("a_b_c.txt", LocalFiles.sanitizeFileName("a:b*c.txt"))
        assertEquals("attachment", LocalFiles.sanitizeFileName(" .. "))
        val long = "x".repeat(300) + ".pdf"
        val cleaned = LocalFiles.sanitizeFileName(long)
        assertTrue(cleaned.length <= 120)
        assertTrue(cleaned.endsWith(".pdf"))
    }

    @Test
    fun fileUrisRoundTripAndComposeCleanupSparesReferencedFiles() {
        val files = LocalFiles(temp.root)
        val keep = files.newComposeFile("informe final.pdf").apply { writeText("keep") }
        val drop = files.newComposeFile("borrar.txt").apply { writeText("drop") }
        val uri = LocalFiles.fileUri(keep)
        assertTrue(uri.startsWith("file:///"))
        assertEquals(keep.canonicalFile, LocalFiles.fileOf(uri)!!.canonicalFile)

        files.cleanupCompose(setOf(uri), now = System.currentTimeMillis() + 2 * 86_400_000L, minAgeMillis = 86_400_000L)
        assertTrue(keep.exists())
        assertFalse(drop.exists())

        files.deleteComposeFiles(listOf(uri), stillReferenced = setOf(uri))
        assertTrue(keep.exists())
        files.deleteComposeFiles(listOf(uri), stillReferenced = emptySet())
        assertFalse(keep.exists())

        val outside = File(temp.root, "upmmail.db").apply { writeText("secret") }
        files.deleteComposeFiles(listOf(LocalFiles.fileUri(outside)), emptySet())
        assertTrue("files outside the compose area are never deleted", outside.exists())
        assertFalse(files.isServable(outside))
        assertFalse(files.isServable(File(files.attachmentsDir, "../upmmail.db")))
    }

    @Test
    fun attachmentCacheCleanupRemovesOrphans() {
        val files = LocalFiles(temp.root)
        val alive = files.attachmentFile(1, 10, "a.pdf").apply { parentFile.mkdirs(); writeText("a") }
        val orphan = files.attachmentFile(2, 20, "b.pdf").apply { parentFile.mkdirs(); writeText("b") }
        files.cleanupAttachments { ids -> ids.filter { it == 1L }.toSet() }
        assertTrue(alive.exists())
        assertFalse(orphan.exists())
    }
}
