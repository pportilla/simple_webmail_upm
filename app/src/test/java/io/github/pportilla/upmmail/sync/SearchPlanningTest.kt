package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.IndexFolderState
import io.github.pportilla.upmmail.mail.FolderProbe
import io.github.pportilla.upmmail.mail.SearchRange
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlanningTest {
    private var nextId = 1L

    private fun folder(name: String, role: FolderRole = FolderRole.OTHER, selectable: Boolean = true) =
        Folder(nextId++, 1, name, name, role, 0, 0, 0, selectable, null)

    private fun state(
        folder: Folder,
        uidValidity: Long = 7,
        uidNext: Long = 100,
        complete: Boolean = true,
        capped: Boolean = false,
    ) = IndexFolderState(folder.id, 1, uidValidity, uidNext, 0, 0, complete, capped, false, null, null)

    /** The windows cover 1..count exactly: no gap, no overlap, newest first. */
    private fun assertExactCover(windows: List<SearchRange.Seq>, count: Int) {
        var expectedLast = count
        for (window in windows) {
            assertEquals(expectedLast, window.last)
            assertTrue(window.first <= window.last)
            expectedLast = window.first - 1
        }
        assertEquals(0, expectedLast)
    }

    @Test
    fun foldersAreSearchedInboxSentArchiveThenOthersInDrawerOrder() {
        val inbox = folder("INBOX", FolderRole.INBOX)
        val drafts = folder("Drafts", FolderRole.DRAFTS)
        val sent = folder("Sent", FolderRole.SENT)
        val archive = folder("Archive", FolderRole.ARCHIVE)
        val junk = folder("Junk", FolderRole.JUNK)
        val trash = folder("Trash", FolderRole.TRASH)
        val projects = folder("Proyectos")
        val all = folder("All mail")
        val parent = folder("Parent", selectable = false)
        val zeta = folder("Zeta")
        // Drawer order, as FolderDao lists them.
        val drawer = listOf(inbox, drafts, sent, archive, junk, trash, projects, all, parent, zeta)
        val indexable = setOf(inbox.id, sent.id, archive.id, junk.id, trash.id, projects.id, zeta.id)

        assertEquals(
            listOf(inbox, sent, archive, projects, zeta),
            folderOrder(drawer, indexable, ServerSearchScope(listOf(1))),
        )
        assertEquals(listOf(trash, junk), folderOrder(drawer, indexable, ServerSearchScope(listOf(1), trashAndJunkOnly = true)))
        assertEquals("a scoped folder is kept even if not indexed", listOf(drafts), folderOrder(drawer, indexable, ServerSearchScope(listOf(1), drafts.id)))
        assertEquals(listOf(all), folderOrder(drawer, indexable, ServerSearchScope(listOf(1), all.id)))
        assertEquals(listOf(trash), folderOrder(drawer, indexable, ServerSearchScope(listOf(1), trash.id)))
        assertTrue("a folder that cannot be selected never", folderOrder(drawer, indexable, ServerSearchScope(listOf(1), parent.id)).isEmpty())
        assertTrue("another account's folder", folderOrder(drawer, indexable, ServerSearchScope(listOf(1), 999)).isEmpty())
    }

    @Test
    fun sequenceWindowsCoverTheFolderExactlyNewestFirst() {
        assertTrue(seqWindows(0, 10).isEmpty())
        assertEquals(listOf(SearchRange.Seq(1, 7)), seqWindows(7, 10))
        assertEquals(listOf(SearchRange.Seq(1, 10)), seqWindows(10, 10))
        assertEquals(listOf(SearchRange.Seq(2, 11), SearchRange.Seq(1, 1)), seqWindows(11, 10))
        for (count in listOf(1, 1_999, 2_000, 2_001, 49_999, 50_000, 123_457)) {
            assertExactCover(seqWindows(count, TEXT_WINDOW), count)
            assertExactCover(seqWindows(count, HEADER_WINDOW), count)
        }
        assertEquals(25, seqWindows(50_000, TEXT_WINDOW).size)
        assertEquals(SearchRange.Seq(48_001, 50_000), seqWindows(50_000, TEXT_WINDOW).first())
    }

    @Test
    fun aCompleteIndexOnlyLeavesNewMailForTheServer() {
        val inbox = folder("INBOX", FolderRole.INBOX)
        val probe = FolderProbe(uidValidity = 7, uidNext = 130, messageCount = 25_000)

        val steps = headerSteps(inbox, probe, state(inbox, uidNext = 120), cap = 50_000)
        assertEquals(listOf<SearchRange>(SearchRange.UidsFrom(120)), steps.map { it.range })
        assertTrue(steps.all { it.phase == SearchPhase.HEADERS && it.folder == inbox && it.depth == 0 })

        val capped = headerSteps(inbox, probe.copy(messageCount = 72_000), state(inbox, uidNext = 120, capped = true), cap = 50_000)
        assertEquals(
            "new mail, then the messages older than the cap",
            listOf(SearchRange.UidsFrom(120), SearchRange.Seq(12_001, 22_000), SearchRange.Seq(2_001, 12_000), SearchRange.Seq(1, 2_000)),
            capped.map { it.range },
        )
        assertEquals(
            "a state row without a uid boundary starts at 1",
            listOf<SearchRange>(SearchRange.UidsFrom(1)),
            headerSteps(inbox, probe, state(inbox, uidNext = 0), cap = 50_000).map { it.range },
        )
    }

    @Test
    fun anIncompleteOrStaleIndexSearchesTheWholeFolderInHeaderWindows() {
        val archive = folder("Archive", FolderRole.ARCHIVE)
        val probe = FolderProbe(uidValidity = 7, uidNext = 31_000, messageCount = 30_500)
        val expected = listOf(
            SearchRange.Seq(20_501, 30_500),
            SearchRange.Seq(10_501, 20_500),
            SearchRange.Seq(501, 10_500),
            SearchRange.Seq(1, 500),
        )
        assertEquals(expected, headerSteps(archive, probe, null, cap = 50_000).map { it.range })
        assertEquals(expected, headerSteps(archive, probe, state(archive, complete = false), cap = 50_000).map { it.range })
        assertEquals(
            "another UIDVALIDITY",
            expected,
            headerSteps(archive, probe, state(archive, uidValidity = 6), cap = 50_000).map { it.range },
        )
        assertTrue(headerSteps(archive, probe.copy(messageCount = 0), null, cap = 50_000).isEmpty())
    }

    @Test
    fun textStepsComeInTiersNewestWindowsOfEveryFolderFirst() {
        val inbox = folder("INBOX", FolderRole.INBOX)
        val sent = folder("Sent", FolderRole.SENT)
        val archive = folder("Archive", FolderRole.ARCHIVE)
        val probed = listOf(
            inbox to FolderProbe(7, 1, 3_000),
            sent to FolderProbe(7, 1, 500),
            archive to FolderProbe(7, 1, 13_000),
        )
        val steps = textSteps(probed)
        assertTrue(steps.all { it.phase == SearchPhase.TEXT && it.depth == 0 })
        val plan = steps.map { it.folder.fullName to it.range }
        assertEquals(
            listOf(
                // Tier 1: the newest window of every folder.
                "INBOX" to SearchRange.Seq(1_001, 3_000),
                "Sent" to SearchRange.Seq(1, 500),
                "Archive" to SearchRange.Seq(11_001, 13_000),
                // Tier 2: up to four more windows of each folder, back to back.
                "INBOX" to SearchRange.Seq(1, 1_000),
                "Archive" to SearchRange.Seq(9_001, 11_000),
                "Archive" to SearchRange.Seq(7_001, 9_000),
                "Archive" to SearchRange.Seq(5_001, 7_000),
                "Archive" to SearchRange.Seq(3_001, 5_000),
                // Tier 3: the rest.
                "Archive" to SearchRange.Seq(1_001, 3_000),
                "Archive" to SearchRange.Seq(1, 1_000),
            ),
            plan,
        )
        for ((folder, probe) in probed) {
            assertExactCover(steps.filter { it.folder == folder }.map { it.range as SearchRange.Seq }.sortedByDescending { it.last }, probe.messageCount)
        }
        assertTrue(textSteps(listOf(inbox to FolderProbe(7, 1, 0))).isEmpty())
    }

    @Test
    fun aWindowThatTimedOutIsHalvedNewerHalfFirstDownToTheMinimum() {
        val inbox = folder("INBOX", FolderRole.INBOX)
        val step = PlannedStep(inbox, SearchPhase.TEXT, SearchRange.Seq(1_001, 3_000))

        val halves = split(step)!!
        assertEquals(listOf(SearchRange.Seq(2_001, 3_000), SearchRange.Seq(1_001, 2_000)), halves.map { it.range })
        assertEquals(listOf(1, 1), halves.map { it.depth })
        assertTrue(halves.all { it.folder == inbox && it.phase == SearchPhase.TEXT })

        val quarters = split(halves[0])!!
        assertEquals(listOf(SearchRange.Seq(2_501, 3_000), SearchRange.Seq(2_001, 2_500)), quarters.map { it.range })
        assertNull("depth limit", split(quarters[0]))
        assertNull("halves would be under the minimum", split(step.copy(range = SearchRange.Seq(1, 999))))
        assertEquals(2, split(step.copy(range = SearchRange.Seq(1, 1_000)))!!.size)
        assertEquals(
            "odd sizes keep an exact cover",
            listOf(SearchRange.Seq(1_001, 2_001), SearchRange.Seq(1, 1_000)),
            split(step.copy(range = SearchRange.Seq(1, 2_001)))!!.map { it.range },
        )
        assertNull("uid ranges are not split", split(step.copy(range = SearchRange.UidsFrom(10))))
    }

    @Test
    fun laterWindowsOfAFolderCanBePlannedAtTheSmallerSize() {
        val inbox = folder("INBOX", FolderRole.INBOX)
        val step = PlannedStep(inbox, SearchPhase.TEXT, SearchRange.Seq(1, 2_000))
        assertEquals(listOf(step), refine(step, 0))
        assertEquals(
            listOf(SearchRange.Seq(1_501, 2_000), SearchRange.Seq(1_001, 1_500), SearchRange.Seq(501, 1_000), SearchRange.Seq(1, 500)),
            refine(step, 2).map { it.range },
        )
        assertEquals(
            "stops at the minimum window",
            listOf(SearchRange.Seq(601, 1_200), SearchRange.Seq(1, 600)),
            refine(step.copy(range = SearchRange.Seq(1, 1_200)), 2).map { it.range },
        )
    }
}
