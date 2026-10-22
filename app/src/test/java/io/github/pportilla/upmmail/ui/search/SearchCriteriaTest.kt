package io.github.pportilla.upmmail.ui.search

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class SearchCriteriaTest {

    private val today = LocalDate.of(2026, 10, 22)

    private fun chips(text: String) = criterionChips(SearchQuery.parse(text), today)

    /** The query of [tokens] read one by one, combined as [SearchQuery.parse] combines them. */
    private fun combined(tokens: List<String>): SearchQuery = tokens.map(SearchQuery::parse).fold(SearchQuery()) { a, b ->
        SearchQuery(
            words = a.words + b.words,
            from = a.from + b.from,
            to = a.to + b.to,
            subject = a.subject + b.subject,
            excluded = a.excluded + b.excluded,
            after = b.after ?: a.after,
            before = b.before ?: a.before,
            hasAttachment = a.hasAttachment || b.hasAttachment,
            unread = a.unread || b.unread,
            starred = a.starred || b.starred,
        )
    }

    @Test
    fun tokensAreSplitAsTheParserSplitsThem() {
        val texts = listOf(
            "",
            "   ",
            "elecciones junta",
            "from:ana@upm.es to:\"Carlos Pérez\" subject:\"fechas de examen\" examen",
            "\"two words\" -\"not this\" -spam after:1/6/2026 hasta:2026-06-30",
            "a\"b c\"d is:unread es:destacado tiene:adjunto",
            "  de:ana   \"\"  para: final \"unbalanced phrase is:unread",
            "subject:\" leading space\" \"quoted\"glued -",
        )
        for (text in texts) {
            assertEquals(text, SearchQuery.parse(text), combined(queryTokens(text)))
        }
        assertEquals(listOf("from:\"Ana García\"", "examen", "\"fecha final"), queryTokens(" from:\"Ana García\"  examen \"fecha final"))
    }

    @Test
    fun chipsShowEachCriterionOnceInFieldOrder() {
        val list = chips("palabra -spam before:2026-06-30 subject:examen to:carlos from:ana from:ANA after:2026-06-01 is:unread -SPAM")
        assertEquals(
            listOf(
                CriterionChip.Person(PersonField.FROM, "ana"),
                CriterionChip.Person(PersonField.TO, "carlos"),
                CriterionChip.Subject("examen"),
                CriterionChip.Dates(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null),
                CriterionChip.Excluded("spam"),
            ),
            list,
        )
        assertTrue("plain words and flags have no criterion chip", chips("elecciones is:starred has:attachment").isEmpty())
        assertEquals(
            "a preset counted back from today",
            listOf(CriterionChip.Dates(LocalDate.of(2026, 9, 23), null, DateChoice.LAST_30_DAYS)),
            chips("after:2026-09-23"),
        )
        assertEquals(listOf(CriterionChip.Dates(null, LocalDate.of(2026, 1, 31), null)), chips("before:31/1/2026"))
    }

    @Test
    fun removingAChipRemovesOnlyItsTokens() {
        val text = "de:ana examen from:\"Ana García\" FROM:Ana -spam desde:1/6/2026 to:ana"
        assertEquals(
            "examen from:\"Ana García\" -spam desde:1/6/2026 to:ana",
            withoutChip(text, CriterionChip.Person(PersonField.FROM, "ana")),
        )
        assertEquals(
            "de:ana examen FROM:Ana -spam desde:1/6/2026 to:ana",
            withoutChip(text, CriterionChip.Person(PersonField.FROM, "ana garcía")),
        )
        assertEquals("de:ana examen from:\"Ana García\" FROM:Ana desde:1/6/2026 to:ana", withoutChip(text, CriterionChip.Excluded("spam")))
        assertEquals(
            "hola after:junk",
            withoutChip("after:1/6/2026 hola hasta:30/6/2026 after:junk", CriterionChip.Dates(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null)),
        )
        assertEquals("examen", withoutChip("subject:\"fecha final\" examen", CriterionChip.Subject("fecha final")))
        assertEquals("", withoutChip("to:carlos", CriterionChip.Person(PersonField.TO, "Carlos")))
    }

    @Test
    fun chipsAlwaysMatchTheTextAfterAnEdit() {
        val texts = listOf(
            "from:ana to:carlos subject:examen after:2026-06-01 before:2026-06-30 -spam notas is:unread",
            "de:ana para:carlos asunto:\"fecha final\" desde:1/6/2026 -\"no esto\" es:destacado tiene:adjunto",
            "\"frase sin cerrar from:ana",
        )
        for (text in texts) {
            for (chip in chips(text)) {
                val edited = withoutChip(text, chip)
                val after = chips(edited)
                assertFalse("$chip removed from $text", chip in after)
                assertEquals("the other chips stay", chips(text) - chip, after)
            }
            for (flag in SearchFlag.entries) {
                for (on in listOf(true, false)) {
                    val edited = withFlag(text, flag, on)
                    val query = SearchQuery.parse(edited)
                    assertEquals("$flag $on in $text", on, flag.isSet(query))
                    assertEquals("the chips stay", chips(text), chips(edited))
                }
            }
        }
    }

    @Test
    fun flagChipsAddOrRemoveTheirOperator() {
        assertEquals("is:unread", withFlag("", SearchFlag.UNREAD, true))
        assertEquals("examen is:unread", withFlag("examen ", SearchFlag.UNREAD, true))
        assertEquals("examen", withFlag("examen es:noleido", SearchFlag.UNREAD, false))
        assertEquals("unchanged when already on", "examen es:noleido", withFlag("examen es:noleido", SearchFlag.UNREAD, true))
        assertEquals("unchanged when already off", "examen  x", withFlag("examen  x", SearchFlag.STARRED, false))
        assertEquals("a b", withFlag("a has:attachment b tiene:adjuntos", SearchFlag.ATTACHMENT, false))
        // The operator must not end up inside an unfinished phrase.
        val edited = withFlag("\"fecha exa", SearchFlag.STARRED, true)
        assertEquals("\"fecha exa\" is:starred", edited)
        assertEquals(SearchQuery(words = listOf("fecha exa"), starred = true), SearchQuery.parse(edited))
    }

    @Test
    fun filterStateFollowsTheText() {
        val state = filterStateOf(SearchQuery.parse("examen es:destacado has:attachment"), thisFolderOnly = true)
        assertEquals(SearchFilterState(unreadOnly = false, starredOnly = true, withAttachments = true, thisFolderOnly = true), state)
    }

    @Test
    fun presetsAreThePeriodsEndingToday() {
        assertEquals(LocalDate.of(2026, 10, 16), DateChoice.LAST_7_DAYS.start(today))
        assertEquals(LocalDate.of(2026, 9, 23), DateChoice.LAST_30_DAYS.start(today))
        assertEquals(LocalDate.of(2026, 4, 23), DateChoice.LAST_6_MONTHS.start(today))
        assertEquals(LocalDate.of(2025, 10, 23), DateChoice.LAST_YEAR.start(today))
        assertNull(DateChoice.ANY.start(today))
        assertNull(DateChoice.CUSTOM.start(today))
        assertEquals(DateChoice.LAST_7_DAYS, presetOf(LocalDate.of(2026, 10, 16), null, today))
        assertNull("an end date is not a preset", presetOf(LocalDate.of(2026, 10, 16), today, today))
        assertNull(presetOf(LocalDate.of(2026, 10, 15), null, today))
        assertNull(presetOf(null, null, today))
    }

    @Test
    fun thePanelShowsTheQueryAndGivesItBack() {
        val queries = listOf(
            SearchQuery(),
            SearchQuery(words = listOf("elecciones")),
            SearchQuery(
                words = listOf("notas", "acta final", "re:", "-5%"),
                from = listOf("ana.garcia@upm.es", "Carlos Pérez"),
                to = listOf("garcia@"),
                subject = listOf("examen", "fecha final"),
                excluded = listOf("spam", "no leer"),
                after = LocalDate.of(2026, 6, 1),
                before = LocalDate.of(2026, 6, 30),
                hasAttachment = true,
                unread = true,
                starred = true,
            ),
            SearchQuery(subject = listOf("acta"), after = LocalDate.of(2026, 4, 23)),
            SearchQuery(before = LocalDate.of(2026, 1, 31)),
            SearchQuery(after = LocalDate.of(2026, 10, 22)),
        )
        for (query in queries) {
            assertEquals(query, AdvancedSearchForm.of(query, today).toQuery(today))
        }
        val preset = AdvancedSearchForm.of(SearchQuery(after = LocalDate.of(2026, 4, 23)), today)
        assertEquals(DateChoice.LAST_6_MONTHS, preset.date)
        assertNull(preset.customAfter)
        val custom = AdvancedSearchForm.of(SearchQuery(before = LocalDate.of(2026, 1, 31)), today)
        assertEquals(DateChoice.CUSTOM, custom.date)
        assertEquals(LocalDate.of(2026, 1, 31), custom.customBefore)
        assertTrue(AdvancedSearchForm.of(SearchQuery(), today).isEmpty)
    }

    @Test
    fun thePanelWritesTheCanonicalText() {
        val form = AdvancedSearchForm(
            from = listOf("ana.garcia@upm.es"),
            fromInput = " carlos ",
            subject = "examen \"fecha final\"",
            words = "notas",
            excluded = "-spam borrador",
            date = DateChoice.LAST_30_DAYS,
            hasAttachment = true,
        )
        val text = form.toQuery(today).format()
        assertEquals(
            "from:ana.garcia@upm.es from:carlos subject:examen subject:\"fecha final\" after:2026-09-23 has:attachment notas -spam -borrador",
            text,
        )
        // The panel, opened on that text, shows the same fields (the typed sender now a chip).
        assertEquals(form.copy(from = listOf("ana.garcia@upm.es", "carlos"), fromInput = "", excluded = "spam borrador"), AdvancedSearchForm.of(SearchQuery.parse(text), today))
    }

    @Test
    fun hasTheWordsReadsOperatorsLikeTheSearchBox() {
        val query = AdvancedSearchForm(from = listOf("ana"), words = "notas de:pepe -spam is:unread").toQuery(today)
        assertEquals(SearchQuery(words = listOf("notas"), from = listOf("ana", "pepe"), excluded = listOf("spam"), unread = true), query)
        val custom = AdvancedSearchForm(date = DateChoice.CUSTOM, customAfter = LocalDate.of(2026, 3, 1)).toQuery(today)
        assertEquals(SearchQuery(after = LocalDate.of(2026, 3, 1)), custom)
        assertEquals("a custom choice without dates is any time", SearchQuery(), AdvancedSearchForm(date = DateChoice.CUSTOM).toQuery(today))
    }

    @Test
    fun personFieldsTakeChipsAndTypedText() {
        var form = AdvancedSearchForm().withInput(PersonField.FROM, "ana, carl")
        assertEquals(listOf("ana"), form.from)
        assertEquals("carl", form.fromInput)
        form = form.commitInput(PersonField.FROM)
        assertEquals(listOf("ana", "carl"), form.from)
        assertEquals("", form.fromInput)
        form = form.withInput(PersonField.TO, "Ana; ").withPerson(PersonField.TO, "carlos@upm.es").withPerson(PersonField.TO, "ANA")
        assertEquals("repeats are dropped", listOf("Ana", "carlos@upm.es"), form.to)
        assertEquals(listOf("ana", "carl"), form.from)
        form = form.withoutPerson(PersonField.TO, 0).withoutPerson(PersonField.TO, 5)
        assertEquals(listOf("carlos@upm.es"), form.to)
        assertEquals("a blank input adds nothing", form, form.withInput(PersonField.FROM, "  ").commitInput(PersonField.FROM))
    }

    @Test
    fun pickedDaysAreUtcMidnights() {
        val day = LocalDate.of(2026, 6, 1)
        assertEquals(1_780_272_000_000L, pickerMillis(day))
        assertEquals(day, pickerDate(pickerMillis(day)))
        assertEquals(day, pickerDate(pickerMillis(day) + 5 * 3_600_000L))
        assertEquals(LocalDate.of(1969, 12, 31), pickerDate(-1))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun theRangePickerOnlyStartsWithDaysItAccepts() {
        val years = DatePickerDefaults.YearRange
        fun start(text: String): PickerStart {
            val form = AdvancedSearchForm.of(SearchQuery.parse(text), today)
            assertEquals(text, DateChoice.CUSTOM, form.date)
            val start = pickerStart(form.customAfter, form.customBefore, years)
            // The picker fails on a day outside its years, or a last day without a first one or before it.
            val state = DateRangePickerState(Locale.ROOT, start.start, start.end, start.shownMonth, years)
            assertEquals(text, start.start, state.selectedStartDateMillis)
            assertEquals(text, start.end, state.selectedEndDateMillis)
            return start
        }
        val june1 = pickerMillis(LocalDate.of(2026, 6, 1))
        val june30 = pickerMillis(LocalDate.of(2026, 6, 30))
        assertEquals(PickerStart(june1, june30, june1), start("after:1/6/2026 before:30/6/2026"))
        assertEquals(PickerStart(null, null, june30), start("before:30/6/2026"))
        assertEquals("the last day before the first", PickerStart(june1, null, june1), start("after:1/6/2026 before:1/1/2026"))
        assertEquals(PickerStart(null, null, null), start("before:1/1/2200"))
        assertEquals(PickerStart(null, null, null), start("after:1/6/1206"))
        assertEquals(PickerStart(june1, null, june1), start("after:1/6/2026 before:31/12/2150"))
        assertEquals(PickerStart(null, null, june30), start("after:1/6/1850 before:30/6/2026"))
    }

    @Test
    fun rowsHighlightTheWordsSubjectAndPeopleOnly() {
        assertEquals("notas fecha final ana", highlightText("from:ana subject:\"fecha final\" -spam is:unread notas after:2026-01-01"))
        assertEquals("", highlightText(""))
    }

    @Test
    fun theOperatorTipGoesOnceAnOperatorWasUsed() {
        assertTrue(showOperatorTip(emptyList()))
        assertTrue(showOperatorTip(listOf("elecciones", "ana@upm.es")))
        assertFalse(showOperatorTip(listOf("elecciones", "de:ana")))
        assertFalse(showOperatorTip(listOf("is:unread")))
    }

    @Test
    fun namesOfAddressesComeFromTheMatches() {
        assertTrue(isFullAddress("ana.garcia@upm.es"))
        assertTrue(isFullAddress(" Ana@UPM.es "))
        assertFalse(isFullAddress("garcia@"))
        assertFalse(isFullAddress("ana@upm"))
        assertFalse(isFullAddress("Ana García"))
        val message = MessageSummary(
            id = 1, accountId = 1, folderId = 1, uid = 1, threadId = "t", subject = "s",
            from = EmailAddress("Ana.Garcia@upm.es", "Ana García"),
            to = listOf(EmailAddress("carlos@upm.es", " "), EmailAddress("luis@upm.es", "Luis")),
            date = 1, snippet = "", isRead = true, isStarred = false, isAnswered = false, isForwarded = false,
            isDraft = false, hasAttachments = false,
        )
        val hits = listOf<SearchHit>(SearchHit.Cached(message, null))
        assertEquals(
            mapOf("ana.garcia@upm.es" to "Ana García", "luis@upm.es" to "Luis"),
            namesFromHits(setOf("ana.garcia@upm.es", "carlos@upm.es", "luis@upm.es"), hits),
        )
        assertEquals("Ana García", personLabel("ANA.GARCIA@upm.es", mapOf("ana.garcia@upm.es" to "Ana García")))
        assertEquals("garcia@", personLabel("garcia@", mapOf("ana.garcia@upm.es" to "Ana García")))
    }

    @Test
    fun anAttachmentFilterAloneIsNotSearchedOnTheServerByItself() {
        assertFalse(shouldAutoSearch("has:attachment", localCount = 0, alreadySearched = false))
        assertTrue(shouldAutoSearch("has:attachment from:ana", localCount = 0, alreadySearched = false))
        assertTrue(shouldAutoSearch("is:unread", localCount = 0, alreadySearched = false))
    }
}
