package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.data.safety.Audience
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.SearchText
import io.github.pportilla.upmmail.model.ContactSource
import io.github.pportilla.upmmail.model.ContactSuggestion
import io.github.pportilla.upmmail.model.EmailAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

/** Address book of the device (empty when the permission is missing). */
fun interface DeviceContactsSource {
    /** Contacts whose name or address matches [query], at most [limit]. */
    fun search(query: String, limit: Int): List<EmailAddress>
}

/**
 * Recipient suggestions: the address history in the cache (fed by sync and by sent mail) merged
 * with the device contacts. Matching is by word prefix on name and address, case- and
 * accent-insensitive; ranking favours addresses the user writes to, then those seen often and
 * recently.
 */
class ContactRepositoryImpl(
    private val store: MailStore,
    private val deviceContacts: DeviceContactsSource = DeviceContactsSource { _, _ -> emptyList() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ContactRepository {

    private class Scored(val suggestion: ContactSuggestion, val score: Double)

    override suspend fun suggest(query: String, exclude: Set<String>, limit: Int): List<ContactSuggestion> {
        val text = query.trim()
        if (text.isEmpty() || limit <= 0) return emptyList()
        return withContext(io) {
            val safety = store.safety.state.value
            if (!safety.termsAccepted || safety.audience == Audience.UNCONFIGURED) return@withContext emptyList()
            if (safety.audience == Audience.SUPERVISED) return@withContext safety.approvedContacts
                .filter { it !in safety.blockedSenders && it !in exclude && it.contains(text, ignoreCase = true) }
                .take(limit).map { ContactSuggestion(EmailAddress(it), ContactSource.HISTORY) }
            val excluded = exclude.mapTo(HashSet()) { it.trim().lowercase() } + safety.blockedSenders
            val now = clock()
            val merged = LinkedHashMap<String, Scored>()

            for (contact in store.contacts.candidates(text, HISTORY_CANDIDATES)) {
                val email = contact.email.lowercase()
                if (email in excluded || !SearchText.matchesWordPrefixes(text, contact.name, contact.email)) continue
                val ageDays = ((now - contact.lastUsed).coerceAtLeast(0)) / DAY.toDouble()
                val score = contact.timesContacted * 3.0 + contact.timesSeen + RECENCY_WEIGHT * exp(-ageDays / RECENCY_DAYS)
                merged[email] = Scored(ContactSuggestion(EmailAddress(contact.email, contact.name), ContactSource.HISTORY), score)
            }

            val device = try {
                deviceContacts.search(text, DEVICE_CANDIDATES)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            for (address in device) {
                val email = address.address.trim().lowercase()
                if (email.isEmpty() || email in excluded) continue
                if (!SearchText.matchesWordPrefixes(text, address.name, address.address)) continue
                val known = merged[email]
                if (known != null) {
                    // Same person: keep the history entry, borrow the address book's name if needed.
                    val current = known.suggestion.address
                    val named = if (current.name.isNullOrBlank() && !address.name.isNullOrBlank()) {
                        known.suggestion.copy(address = current.copy(name = address.name))
                    } else {
                        known.suggestion
                    }
                    merged[email] = Scored(named, known.score + DEVICE_BONUS)
                } else {
                    val suggestion = ContactSuggestion(address.copy(address = address.address.trim()), ContactSource.DEVICE)
                    merged[email] = Scored(suggestion, DEVICE_BONUS)
                }
            }

            merged.values
                .sortedWith(
                    compareByDescending<Scored> { it.score }
                        .thenBy { it.suggestion.address.displayName.lowercase() },
                )
                .take(limit)
                .map { it.suggestion }
                .filter { store.safety.state.value.audience == Audience.ADULT && !store.safety.isSenderBlocked(it.address.address) }
        }
    }

    private companion object {
        const val HISTORY_CANDIDATES = 200
        const val DEVICE_CANDIDATES = 50
        const val DAY = 86_400_000L
        const val RECENCY_DAYS = 30.0
        const val RECENCY_WEIGHT = 5.0
        const val DEVICE_BONUS = 1.5
    }
}
