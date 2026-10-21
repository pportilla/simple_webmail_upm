package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress

object AddressParser {
    data class ParseResult(val addresses: List<EmailAddress>, val invalid: List<String>)

    /** Lenient parse of "Ana <ana@upm.es>, b@upm.es; c@x.es"; unparseable tokens go to [ParseResult.invalid]. */
    fun parse(input: String): ParseResult = TODO()

    /** Syntactic check of a bare address (local@domain.tld). */
    fun isValidAddress(address: String): Boolean = TODO()

    /** RFC 5322 header form ("Ana Pérez" <ana@upm.es>, b@upm.es); round-trips with [parse]. */
    fun format(addresses: List<EmailAddress>): String = TODO()
}
