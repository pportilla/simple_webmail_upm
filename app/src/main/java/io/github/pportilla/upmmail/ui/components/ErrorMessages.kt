package io.github.pportilla.upmmail.ui.components

import android.content.Context
import androidx.annotation.StringRes
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException

@StringRes
fun MailErrorKind.messageRes(): Int = when (this) {
    MailErrorKind.AUTHENTICATION -> R.string.error_authentication
    MailErrorKind.NETWORK -> R.string.error_network
    MailErrorKind.CERTIFICATE -> R.string.error_certificate
    MailErrorKind.SERVER -> R.string.error_server
    MailErrorKind.NOT_FOUND -> R.string.error_not_found
    MailErrorKind.INVALID_RECIPIENT -> R.string.error_invalid_recipient
    MailErrorKind.TOO_LARGE -> R.string.error_too_large
    MailErrorKind.UNKNOWN -> R.string.error_unknown
}

/** A short, localised explanation of any failure coming from the data layer. */
fun Context.errorMessage(error: Throwable?): String {
    val kind = (error as? MailException)?.kind ?: MailErrorKind.UNKNOWN
    return getString(kind.messageRes())
}
