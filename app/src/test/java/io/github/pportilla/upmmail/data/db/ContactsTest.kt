package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.data.repository.ContactRepositoryImpl
import io.github.pportilla.upmmail.data.repository.DeviceContactsSource
import io.github.pportilla.upmmail.model.ContactSource
import io.github.pportilla.upmmail.model.EmailAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactsTest {
    private val clock = TestClock()
    private lateinit var store: MailStore

    @Before
    fun setUp() {
        store = newStore(clock::now)
    }

    @Test
    fun historyCountsSeenAndContactedSeparately() {
        val ana = EmailAddress("Ana.Perez@UPM.es", "Ana Pérez")
        store.contacts.recordSeen(listOf(ana, EmailAddress("me@upm.es"), EmailAddress("no-reply@upm.es")), 100, setOf("me@upm.es"))
        store.contacts.recordSeen(listOf(EmailAddress("ana.perez@upm.es")), 200, emptySet())
        store.contacts.recordContacted(listOf(ana), 300)
        val contact = store.contacts.get("ana.perez@upm.es")!!
        assertEquals("Ana Pérez", contact.name)
        assertEquals(2, contact.timesSeen)
        assertEquals(1, contact.timesContacted)
        assertEquals(300L, contact.lastUsed)
        assertEquals("own address is not recorded from received mail", null, store.contacts.get("me@upm.es"))
        assertEquals("automated senders are skipped", null, store.contacts.get("no-reply@upm.es"))
    }

    @Test
    fun suggestionsMatchWordPrefixesRankAndMergeDeviceContacts() = runBlocking {
        val now = clock.now()
        store.contacts.recordSeen(listOf(EmailAddress("jose.nunez@upm.es", "José Núñez")), now, emptySet())
        repeat(3) { store.contacts.recordContacted(listOf(EmailAddress("josefa@alumnos.upm.es", "Josefa Ruiz")), now) }
        store.contacts.recordSeen(listOf(EmailAddress("maria.jose@upm.es")), now - 400L * 86_400_000, emptySet())
        store.contacts.recordSeen(listOf(EmailAddress("pepe@upm.es", "Pepe")), now, emptySet())

        val device = DeviceContactsSource { _, _ ->
            listOf(EmailAddress("jose.nunez@upm.es", "José Núñez Díaz"), EmailAddress("jose.luis@gmail.com", "José Luis"))
        }
        val repository = ContactRepositoryImpl(store, device, clock = clock::now)

        val results = repository.suggest("jos")
        val addresses = results.map { it.address.address }
        assertEquals("josefa@alumnos.upm.es", addresses.first())
        assertTrue("jose.nunez@upm.es" in addresses)
        assertTrue("maria.jose@upm.es" in addresses)
        assertTrue("jose.luis@gmail.com" in addresses)
        assertFalse("pepe does not match", "pepe@upm.es" in addresses)
        assertEquals("deduplicated", addresses.distinct(), addresses)
        assertEquals(ContactSource.HISTORY, results.first { it.address.address == "jose.nunez@upm.es" }.source)
        assertEquals(ContactSource.DEVICE, results.first { it.address.address == "jose.luis@gmail.com" }.source)

        assertEquals(listOf("jose.nunez@upm.es"), repository.suggest("NÚÑEZ").map { it.address.address })
        assertEquals(listOf("jose.nunez@upm.es"), repository.suggest("jose nu").map { it.address.address })
        assertFalse("josefa@alumnos.upm.es" in repository.suggest("jos", exclude = setOf("josefa@alumnos.upm.es")).map { it.address.address })
        assertEquals(2, repository.suggest("jos", limit = 2).size)
        assertTrue(repository.suggest("   ").isEmpty())
    }

    @Test
    fun aPersonTypedInFullIsFoundAmongManyFrequentFirstWordMatches() = runBlocking {
        val now = clock.now()
        // Plenty of busier contacts containing "ana" (Mariana, Juana, Susana...).
        repeat(250) { i ->
            val address = EmailAddress("contacto$i@upm.es", listOf("Mariana", "Juana", "Susana", "Ana")[i % 4] + " López $i")
            repeat(5) { store.contacts.recordContacted(listOf(address), now) }
        }
        store.contacts.recordSeen(listOf(EmailAddress("ana.garcia@upm.es", "Ana García")), now, emptySet())

        assertEquals("ana.garcia@upm.es", store.contacts.candidates("ana garcia", 200).single().email)
        val repository = ContactRepositoryImpl(store, clock = clock::now)
        assertEquals(listOf("ana.garcia@upm.es"), repository.suggest("ana garc").map { it.address.address })
    }
}
