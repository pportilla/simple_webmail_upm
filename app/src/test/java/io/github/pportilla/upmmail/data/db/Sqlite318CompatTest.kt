package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.NOT_PERSONAL_ATTRIBUTE
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.mail.toIndexedHeader
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.sql.Driver
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Android 8 ships SQLite 3.18; the JDBC driver of the other tests is far newer and accepts syntax
 * 3.18 rejects (UPSERT, window functions, RETURNING...). This runs the version 1 schema, the
 * migration and every query added for the header index on sqlite-jdbc 3.18.0, whose jar the
 * Gradle build passes as -Dsqlite318.jar. The test is skipped without it, and on machines the old
 * jar has no native library for (ARM Macs, for example); CI runs it on Linux x86-64.
 */
class Sqlite318CompatTest {

    @Test
    fun schemaMigrationAndQueriesRunOnTheSqliteOfAndroid8() {
        val jar = System.getProperty("sqlite318.jar")
        assumeTrue("-Dsqlite318.jar is not set", !jar.isNullOrBlank())
        assumeTrue("sqlite-jdbc 3.18.0 has no native library for this machine", hasNativeLibrary(File(jar)))
        // The platform loader as parent: the newer driver on the test classpath must not be seen.
        // Called reflectively: AGP compiles unit tests against android.jar, whose ClassLoader
        // lacks this Java 9 method (the tests themselves run on a full JDK).
        val platformLoader = ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader
        URLClassLoader(arrayOf(File(jar).toURI().toURL()), platformLoader).use { loader ->
            val driver = Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance() as Driver
            val db = JdbcSqlDatabase.wrap(driver.connect("jdbc:sqlite::memory:", Properties()))
            try {
                assertEquals("3.18.0", db.query("SELECT sqlite_version() AS v", emptyList()) { it.string("v") }.single())
                db.transaction { MailSchema.createVersion1(db) }
                val seeded = Version1Fixture.seed(db)
                val counts = Version1Fixture.rowCounts(db)
                db.transaction { MailSchema.migrate(db, 1, MailSchema.VERSION) }
                assertEquals(counts, Version1Fixture.rowCounts(db))
                Version1Fixture.assertMigrated(db, seeded)
                DaoScenario.run(MailStore(db, TableObserver()) { DaoScenario.NOW })
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun scenarioPassesOnTheDefaultDriver() {
        DaoScenario.run(MailStore(JdbcSqlDatabase.inMemory(), TableObserver()) { DaoScenario.NOW })
    }

    /** Whether the jar has a native library for this OS and architecture, named as sqlite-jdbc does. */
    private fun hasNativeLibrary(jar: File): Boolean {
        val osName = System.getProperty("os.name").orEmpty()
        val os = when {
            osName.startsWith("Windows") -> "Windows"
            osName.startsWith("Mac") || osName.startsWith("Darwin") -> "Mac"
            else -> osName
        }
        val arch = when (val name = System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "i386", "i486", "i586", "i686", "x86" -> "x86"
            else -> name
        }
        return ZipFile(jar).use { zip -> zip.entries().asSequence().any { !it.isDirectory && it.name.startsWith("org/sqlite/native/$os/$arch/") } }
    }
}

/** Runs every query added or changed for the header index and detached rows, with light checks. */
internal object DaoScenario {
    const val NOW = 1_800_000_000_000L

    fun run(store: MailStore) {
        val account = store.addTestAccount(email = "escenario@upm.es")
        folders(store, account.id)
        val byName = store.folders.getByAccount(account.id).associateBy { it.fullName }
        detachedRows(store, account.id, byName.getValue("INBOX").id)
        headerIndex(store, account.id, byName.getValue("Archive").id, byName.getValue("Sent").id, byName.getValue("Trash").id)
    }

    private fun entry(name: String, role: FolderRole, attributes: Set<String> = emptySet()) =
        FolderListEntry(name, name.substringAfterLast('/'), '/', attributes, true, 0, role)

    private fun folders(store: MailStore, accountId: Long) {
        store.folders.applyServerList(
            accountId,
            listOf(
                entry("INBOX", FolderRole.INBOX),
                entry("Sent", FolderRole.SENT, setOf("\\Sent")),
                entry("Archive", FolderRole.ARCHIVE),
                entry("Trash", FolderRole.TRASH, setOf("\\Trash")),
                entry("Drafts", FolderRole.DRAFTS, setOf("\\Drafts")),
                entry("All", FolderRole.OTHER, setOf("\\All")),
                entry("Shared/Team", FolderRole.OTHER, setOf(NOT_PERSONAL_ATTRIBUTE)),
            ),
        )
        assertEquals(listOf("INBOX", "Sent", "Archive", "Trash"), store.folders.indexableFolders(accountId).map { it.fullName })
        assertEquals(setOf("\\sent"), store.folders.attributesOf(store.folders.findByFullName(accountId, "Sent")!!.id))
        assertFalse(store.folders.hasUnlistedFolders(accountId))
    }

    private fun detachedRows(store: MailStore, accountId: Long, inbox: Long) {
        val messages = store.messages
        val ids = messages.upsertHeaders(
            accountId,
            inbox,
            (1L..4L).map { remoteMessage(it, subject = "Reunión $it", messageId = "<s$it@x>", references = listOf("<s1@x>")) },
        )
        messages.upsertHeaders(accountId, inbox, listOf(remoteMessage(90, subject = "Reunión antigua", messageId = "<s90@x>")), detachedAt = 5)
        assertEquals(listOf(4L, 3L, 2L, 1L), messages.list(inbox, 10, conversations = false).map { it.uid })
        assertEquals(4, messages.list(inbox, 10, conversations = true).single().threadCount)
        assertEquals(4, messages.expandToThreads(listOf(ids[0])).size)
        assertEquals(4, messages.needingBody(inbox, 10, Long.MAX_VALUE).size)
        assertEquals(5, messages.searchHits("reunion", SearchFilters(), 10).size)
        assertEquals(listOf(90L, 1L), messages.cachedHits(inbox, listOf(1L, 90L, 999L)).map { it.key.uid }.sortedDescending())
        assertEquals(setOf(SearchKey(inbox, 1)), messages.existingKeys(listOf(SearchKey(inbox, 1), SearchKey(inbox, 999))))
        assertEquals(setOf(1L, 2L), messages.existingUids(inbox, listOf(1L, 2L, 999L)))

        assertEquals(1, messages.detachOutsideWindow(inbox, 2, 4, 10))
        assertEquals(listOf(1L, 90L), messages.extras(inbox).map { it.uid })
        val flags = messages.applyServerFlagsFor(inbox, listOf(1L, 90L), mapOf(1L to RemoteFlags(seen = true)), emptySet())
        assertEquals(FlagSyncResult(updated = 1, removed = 1), flags)
        assertEquals(1, messages.touchDetachedThread(ids[1], 20))
        val unprotected = ProtectedUids(emptySet(), wholeFolder = false)
        assertEquals("used recently", 0, messages.evictExtras(inbox, 0, 15, unprotected))
        assertEquals(1, messages.evictExtras(inbox, 0, 100, unprotected))
        messages.upsertHeaders(accountId, inbox, listOf(remoteMessage(80)), detachedAt = 50)
        assertEquals("the recently used row is spared", 2, messages.deleteVisibleExcept(inbox, setOf(4L), spareDetachedSince = 40))
        assertEquals(listOf(80L, 4L), messages.existingUids(inbox, listOf(2L, 3L, 4L, 80L)).sortedDescending())
    }

    private fun headerIndex(store: MailStore, accountId: Long, archive: Long, sent: Long, trash: Long) {
        val index = store.headerIndex
        assertNotNull(index.ensureState(archive, 5, 3_000, 2_300))
        val headers = (1L..2_100L).map { uid ->
            remoteMessage(uid, subject = if (uid % 2 == 0L) "Junta electoral $uid" else "Elecciones $uid", messageId = "<a$uid@x>")
                .toIndexedHeader()
        }
        assertEquals(2_100, index.insertHeaders(archive, 5, headers))
        assertEquals("known rows only get flags", 0, index.insertHeaders(archive, 5, listOf(headers[0].copy(flags = RemoteFlags(seen = true)))))
        assertEquals("stale batch", -1, index.insertHeaders(archive, 6, headers.take(1)))
        assertEquals(2_100, index.state(archive)!!.indexedCount)
        assertEquals(2_100, index.accountRowCount(accountId))

        // Both search plans: few candidates (sorted) and many (date index).
        assertEquals(listOf(2_099L, 2_097L), index.search("elecciones 209", SearchFilters(), 2).map { it.uid })
        assertEquals(listOf(2_099L), index.search("elecciones 2099", SearchFilters(), 10).map { it.uid })
        assertEquals(listOf(2_100L, 2_099L, 2_098L), index.search("ele", SearchFilters(accountId = accountId), 3).map { it.uid })
        assertEquals(listOf(2_100L), index.search("junta", SearchFilters(folderId = archive, unreadOnly = true, starredOnly = false), 1).map { it.uid })
        assertTrue(index.search("ju", SearchFilters(), 10).isEmpty())

        index.updateFlags(archive, mapOf(2L to RemoteFlags(flagged = true), 3L to RemoteFlags(deleted = true)))
        index.setFlag(archive, listOf(4L), LocalFlag.STARRED, true)
        index.markFolderRead(archive)
        assertTrue(index.findByUids(archive, listOf(2L, 4L)).all { it.isStarred && it.isRead })
        assertEquals(2_099, index.indexedUids(archive).size)
        assertEquals(2_100L, index.maxUid(archive))
        assertNotNull(index.oldestDate(archive))
        val diff = index.applyDiff(archive, (5L..2_100L).toList().toLongArray(), below = 2_101, keepNewest = 2_000)
        assertEquals(listOf(101L, 2_100L), listOf(diff.remaining.first(), diff.remaining.last()))
        assertEquals(1, index.deleteMissingInRange(archive, 2_000L..2_010L, (2_000L..2_010L).toSet() - 2_005L))
        assertEquals(1, index.deleteByMessageIds(archive, listOf("<A2004@X>")))
        assertEquals(1, index.deleteUids(archive, listOf(2_003L)))
        val row = index.findByMessageId(accountId, "<A2002@X>").single()
        assertEquals(row, index.get(row.indexId))

        index.updateState(archive, uidNext = 3_001, complete = true, capped = true, checkedAt = NOW, updatedAt = NOW)
        assertNotNull(index.ensureState(sent, 3, 10, 1))
        index.upsertFromSync(sent, 3, listOf(remoteMessage(7, to = listOf(EmailAddress("carlos@upm.es", "Carlos")))))
        assertEquals("carlos@upm.es", index.findByUids(sent, listOf(7L)).single().to.single().address)
        index.markDirty(listOf(sent, trash))
        assertEquals(listOf(sent), index.dirtyFolders(accountId))
        assertEquals(listOf(sent), index.dirtyFolders())
        assertEquals(2, index.statesOf(accountId).size)
        val status = index.status().single { it.accountId == accountId }
        assertFalse("INBOX and Trash have no state yet", status.complete)
        index.mergeSegments()
        index.clearRows(sent)
        index.resetFolder(sent)
        store.folders.delete(archive)
        assertEquals(0L, store.sql.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        assertEquals(0, index.accountRowCount(accountId))
    }
}
