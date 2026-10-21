package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailException

object MailErrors {
    /** Maps Jakarta Mail / IO / TLS exceptions to a MailException with the right kind. */
    fun map(t: Throwable): MailException = TODO()
}
