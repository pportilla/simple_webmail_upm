package io.github.pportilla.upmmail.ui.safety

/** Require an exact address, never a display-name expression or a list of recipients. */
fun isSingleSafetyAddress(address: String): Boolean =
    address.length in 3..254 &&
        Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}$")
            .matches(address)
