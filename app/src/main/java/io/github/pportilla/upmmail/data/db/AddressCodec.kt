package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.model.EmailAddress

/**
 * Address lists are stored as RFC 5322 header strings ([AddressParser.format]). Parsing them is
 * the hot path of every list query, so results are memoised in a small LRU cache.
 */
internal object AddressCodec {
    private const val CACHE_SIZE = 1024

    private val cache = object : LinkedHashMap<String, List<EmailAddress>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EmailAddress>>?): Boolean =
            size > CACHE_SIZE
    }

    fun encode(addresses: List<EmailAddress>): String =
        if (addresses.isEmpty()) "" else AddressParser.format(addresses)

    fun encode(address: EmailAddress?): String = if (address == null) "" else encode(listOf(address))

    fun decode(value: String?): List<EmailAddress> {
        if (value.isNullOrBlank()) return emptyList()
        synchronized(cache) { cache[value]?.let { return it } }
        val parsed = runCatching { AddressParser.parse(value).addresses }.getOrDefault(emptyList())
        synchronized(cache) { cache[value] = parsed }
        return parsed
    }

    /** Message-ID lists (References) are stored space separated; ids never contain spaces. */
    fun encodeIds(ids: List<String>): String = ids.filter { it.isNotBlank() }.joinToString(" ") { it.trim() }

    fun decodeIds(value: String?): List<String> =
        value?.split(' ', '\n', '\t')?.filter { it.isNotBlank() } ?: emptyList()

    /** Names and addresses as one string, for the full-text "people" column. */
    fun people(vararg lists: List<EmailAddress>): String =
        lists.asSequence().flatten().joinToString(" ") { address ->
            if (address.name.isNullOrBlank()) address.address else "${address.name} ${address.address}"
        }
}
