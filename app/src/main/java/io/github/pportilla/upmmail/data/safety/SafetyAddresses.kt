package io.github.pportilla.upmmail.data.safety

import java.util.Locale

fun normalizedSafetyAddress(address: String): String = address.trim().lowercase(Locale.ROOT)
