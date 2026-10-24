package io.github.pportilla.upmmail.ui.safety

import io.github.pportilla.upmmail.data.safety.AgeBand
import java.time.LocalDate
import java.time.Period

/** A date is used only in memory to derive a band; the birth date is never persisted. */
fun birthDateFromFields(day: String, month: String, year: String, today: LocalDate): LocalDate? =
    runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
        ?.takeIf { !it.isBefore(today.minusYears(120)) && !it.isAfter(today) }

fun ageBandForBirthDate(date: LocalDate, today: LocalDate): AgeBand? {
    if (date.isBefore(today.minusYears(120)) || date.isAfter(today)) return null
    return when (Period.between(date, today).years) {
        in 0..12 -> AgeBand.UNDER_13
        in 13..17 -> AgeBand.TEEN
        else -> AgeBand.ADULT
    }
}

/** Require an exact address, never a display-name expression or a list of recipients. */
fun isSingleSafetyAddress(address: String): Boolean =
    address.length in 3..254 &&
        Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}$")
            .matches(address)
