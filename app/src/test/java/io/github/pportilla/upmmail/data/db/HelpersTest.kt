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
    fun shortTermsCanBeExactWords() {
        assertEquals("reunion* de depart*", SearchText.matchExpression("Reunión de DEPART", minPrefixLength = 3))
        assertEquals("acta* a la", SearchText.matchExpression("acta a la", minPrefixLength = 3))
        assertEquals("the default keeps every word a prefix", "a* la*", SearchText.matchExpression("a la"))
        assertNull(SearchText.matchExpression(" () ", minPrefixLength = 3))
        assertTrue(SearchText.allTermsHaveLength("Junta electoral", 3))
        assertFalse(SearchText.allTermsHaveLength("de la Junta", 3))
        assertTrue("letters are counted after folding", SearchText.allTermsHaveLength("año", 3))
        assertFalse(SearchText.allTermsHaveLength("CV", 3))
        assertFalse(SearchText.allTermsHaveLength(" () ", 1))
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
    fun attachmentCacheCleanupRemovesOrphansButNotReferencedFiles() {
        val files = LocalFiles(temp.root)
        val alive = files.attachmentFile(1, 10, "a.pdf").apply { parentFile.mkdirs(); writeText("a") }
        val orphan = files.attachmentFile(2, 20, "b.pdf").apply { parentFile.mkdirs(); writeText("b") }
        // Attached to a draft saved by an older version, which pointed into the cache.
        val referenced = files.attachmentFile(3, 30, "c.pdf").apply { parentFile.mkdirs(); writeText("c") }
        files.cleanupAttachments(setOf(LocalFiles.fileUri(referenced))) { ids -> ids.filter { it == 1L }.toSet() }
        assertTrue(alive.exists())
        assertFalse(orphan.exists())
        assertTrue("a draft or queued message still needs it", referenced.exists())
    }

    @Test
    fun attachmentCacheIsTrimmedLeastRecentlyUsedFirst() {
        val files = LocalFiles(temp.root)
        val now = 1_750_000_000_000L
        val hour = 60 * 60_000L
        fun cached(messageId: Long, name: String, size: Int, age: Long): File =
            files.attachmentFile(messageId, messageId * 10, name).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(size))
                setLastModified(now - age)
            }
        val oldest = cached(1, "oldest.pdf", 400, 10 * hour)
        val referenced = cached(2, "referenced.pdf", 400, 9 * hour)
        val older = cached(3, "older.pdf", 400, 8 * hour)
        val recent = cached(4, "recent.pdf", 400, 2 * hour)
        val open = cached(5, "open.pdf", 400, 1_000)

        val deleted = files.trimAttachments(setOf(LocalFiles.fileUri(referenced)), maxBytes = 1_200, now = now, minAgeMillis = hour)
        assertEquals(2, deleted)
        assertFalse(oldest.exists())
        assertFalse("empty directories go too", File(files.attachmentsDir, "1").exists())
        assertTrue(referenced.exists())
        assertFalse(older.exists())
        assertTrue("the cache is small enough again", recent.exists())
        // Over a lower limit, but the rest is referenced, or used lately and possibly open in a viewer.
        files.touch(recent, now)
        assertEquals(0, files.trimAttachments(setOf(LocalFiles.fileUri(referenced)), maxBytes = 1_000, now = now, minAgeMillis = hour))
        assertTrue(recent.exists())
        assertTrue(open.exists())
        assertEquals("under the limit nothing goes", 0, files.trimAttachments(emptySet(), maxBytes = 10_000, now = now, minAgeMillis = 0))
    }

    @Test
    fun composeCopiesOutliveTheCachedFile() {
        val files = LocalFiles(temp.root)
        val cachedFile = files.attachmentFile(7, 70, "acta.pdf").apply { parentFile.mkdirs(); writeText("contenido") }
        val copy = files.newComposeCopy(cachedFile, "Acta: final.pdf")
        assertTrue(files.isComposeFile(copy))
        assertEquals("Acta_ final.pdf", copy.name)
        files.deleteMessageFiles(listOf(7))
        files.cleanupAttachments(emptySet()) { emptySet() }
        assertFalse(cachedFile.exists())
        assertEquals("contenido", copy.readText())
    }
}
