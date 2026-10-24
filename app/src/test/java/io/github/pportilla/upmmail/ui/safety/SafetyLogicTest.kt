package io.github.pportilla.upmmail.ui.safety

import io.github.pportilla.upmmail.data.safety.AgeBand
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SafetyLogicTest {
    private val today = LocalDate.of(2026, 10, 24)

    @Test fun birthdaysUseFullDateNotYearAlone() {
        assertEquals(AgeBand.UNDER_13, ageBandForBirthDate(LocalDate.of(2013, 10, 25), today))
        assertEquals(AgeBand.TEEN, ageBandForBirthDate(LocalDate.of(2013, 10, 24), today))
        assertEquals(AgeBand.TEEN, ageBandForBirthDate(LocalDate.of(2008, 10, 25), today))
        assertEquals(AgeBand.ADULT, ageBandForBirthDate(LocalDate.of(2008, 10, 24), today))
    }

    @Test fun malformedAndFutureDatesDoNotSelectAnAudience() {
        assertNull(birthDateFromFields("31", "2", "2010", today))
        assertNull(birthDateFromFields("25", "10", "2026", today))
        assertNull(birthDateFromFields("", "10", "2000", today))
        assertNull(ageBandForBirthDate(today.plusDays(1), today))
        assertNull(ageBandForBirthDate(LocalDate.of(1899, 1, 1), today))
    }

    @Test fun contactsCannotSmuggleMultipleRecipientsOrHeaderData() {
        assertTrue(isSingleSafetyAddress("guardian@example.invalid"))
        assertFalse(isSingleSafetyAddress("one@example.invalid,two@example.invalid"))
        assertFalse(isSingleSafetyAddress("Name <one@example.invalid>"))
        assertFalse(isSingleSafetyAddress("one@example.invalid\nBcc: other@example.invalid"))
        assertFalse(isSingleSafetyAddress("https://example.invalid"))
    }
}
