package io.github.pportilla.upmmail.ui.safety

import org.junit.Assert.*
import org.junit.Test

class SafetyLogicTest {
    @Test fun contactsCannotSmuggleMultipleRecipientsOrHeaderData() {
        assertTrue(isSingleSafetyAddress("sender@example.invalid"))
        assertFalse(isSingleSafetyAddress("one@example.invalid,two@example.invalid"))
        assertFalse(isSingleSafetyAddress("Name <one@example.invalid>"))
        assertFalse(isSingleSafetyAddress("one@example.invalid\nBcc: other@example.invalid"))
        assertFalse(isSingleSafetyAddress("https://example.invalid"))
    }
}
