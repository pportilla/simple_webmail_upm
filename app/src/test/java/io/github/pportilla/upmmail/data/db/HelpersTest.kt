package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.model.EmailAddress
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
    fun foldingAndPhrases() {
        assertEquals("perez nunez", SearchText.fold("Pérez NÚÑEZ"))
        assertEquals("reunion*", SearchText.phrase("Reunión", prefixLast = true))
        assertEquals("cv", SearchText.phrase("CV", prefixLast = false))
        assertEquals("\"ana garcia upm es*\"", SearchText.phrase("Ana.García@UPM.es", prefixLast = true))
        assertEquals("\"acta de la\"", SearchText.phrase("acta de la", prefixLast = false))
        assertEquals("FTS syntax is dropped", "\"a b*\"", SearchText.phrase("\"a\" :(b*)", prefixLast = true))
        assertEquals("operators stay lower case words", "\"a or b*\"", SearchText.phrase("a OR b", prefixLast = true))
        assertNull(SearchText.phrase("  \"*()  ", prefixLast = true))
        assertEquals("at most 12 words", 12, SearchText.phrase((1..20).joinToString(" ") { "w$it" }, prefixLast = false)!!.split(' ').size)
        assertTrue(SearchText.matchesWordPrefixes("ana per", "Ana Pérez", "ana.perez@upm.es"))
        assertTrue(SearchText.matchesWordPrefixes("perez@", null, "ana.perez@upm.es"))
        assertFalse(SearchText.matchesWordPrefixes("nap", "Ana Pérez", "ana.perez@upm.es"))
        assertEquals("100\\%\\_\\\\", SearchText.escapeLike("100%_\\"))
    }

    @Test
    fun theIndexMatchesLikeTheServerFromThreeLettersOrAWholeAddress() {
        assertTrue(FtsQuery.matchesLikeServer("Junta", 3))
        assertTrue("letters are counted after folding", FtsQuery.matchesLikeServer("año", 3))
        assertTrue("only the last word continues", FtsQuery.matchesLikeServer("acta de la junta", 3))
        assertFalse(FtsQuery.matchesLikeServer("CV", 3))
        assertFalse(FtsQuery.matchesLikeServer("junta de", 3))
        assertTrue("a whole address", FtsQuery.matchesLikeServer("ana.garcia@upm.es", 3))
        assertFalse("part of an address", FtsQuery.matchesLikeServer("ana.garcia@upm.e", 3))
        assertTrue(FtsQuery.matchesLikeServer("garcia@", 3))
        assertFalse(FtsQuery.matchesLikeServer(" () ", 1))
    }

    @Test
    fun versionTwoPeopleTextsSplitAfterTheSender() {
        val from = AddressCodec.encode(EmailAddress("ana.garcia@upm.es", "García, Ana"))
        val people = SearchText.fold(
            AddressCodec.people(listOf(EmailAddress("ana.garcia@upm.es", "García, Ana")), listOf(EmailAddress("luis@upm.es", "Luis Núñez"))),
        )
        assertEquals("garcia, ana ana.garcia@upm.es" to "luis nunez luis@upm.es", FtsPeople.split(people, from))
        assertEquals("no sender", "" to "luis@upm.es", FtsPeople.split("luis@upm.es", ""))
        assertEquals("a sender only", "bea bea@upm.es" to "", FtsPeople.split("bea bea@upm.es", "Bea <bea@upm.es>"))
        assertEquals("cut inside the sender", "bea" to "", FtsPeople.split("bea", "Bea <bea@upm.es>"))
        assertEquals("an address the codec cannot read", "mailer-daemon" to "ana@upm.es", FtsPeople.split("mailer-daemon ana@upm.es", "<MAILER-DAEMON>"))
        assertEquals(
            "words that do not line up keep everything as recipients",
            "\"otro\" <otro@upm.es>" to "bea bea@upm.es luis@upm.es",
            FtsPeople.split("bea bea@upm.es luis@upm.es", "\"Otro\" <otro@upm.es>"),
        )
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
