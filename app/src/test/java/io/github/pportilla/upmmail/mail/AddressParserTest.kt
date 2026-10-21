package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressParserTest {
    @Test
    fun parsesCommonForms() {
        val result = AddressParser.parse(
            "Ana Pérez <ana@upm.es>, bea@alumnos.upm.es; \"Gómez, Carlos\" <carlos@upm.es>\n<dani@upm.es>, " +
                "eva@upm.es (Eva Ruiz), mailto:fran@upm.es, 'Gil' <gil@upm.es>",
        )
        assertEquals(
            listOf(
                EmailAddress("ana@upm.es", "Ana Pérez"),
                EmailAddress("bea@alumnos.upm.es"),
                EmailAddress("carlos@upm.es", "Gómez, Carlos"),
                EmailAddress("dani@upm.es"),
                EmailAddress("eva@upm.es", "Eva Ruiz"),
                EmailAddress("fran@upm.es"),
                EmailAddress("gil@upm.es", "Gil"),
            ),
            result.addresses,
        )
        assertTrue(result.invalid.isEmpty())
    }

    @Test
    fun collectsInvalidTokens() {
        val result = AddressParser.parse("ana@upm.es, not an address, foo@, @upm.es, , x@y,  ; Bea <bea>, <a b@c.es>, Ana <ana@upm.es")
        assertEquals(listOf(EmailAddress("ana@upm.es")), result.addresses)
        assertEquals(listOf("not an address", "foo@", "@upm.es", "x@y", "Bea <bea>", "<a b@c.es>", "Ana <ana@upm.es"), result.invalid)
    }

    @Test
    fun lenientFormsWithoutBrackets() {
        assertEquals(
            listOf(EmailAddress("ana@upm.es", "Ana Pérez")),
            AddressParser.parse("Ana Pérez ana@upm.es").addresses,
        )
        assertEquals(
            listOf(EmailAddress("a@upm.es"), EmailAddress("b@upm.es")),
            AddressParser.parse("a@upm.es b@upm.es").addresses,
        )
        assertEquals(
            listOf(EmailAddress("ana@upm.es", "José Ñúñez")),
            AddressParser.parse("=?UTF-8?Q?Jos=C3=A9_=C3=91=C3=BA=C3=B1ez?= <ana@upm.es>").addresses,
        )
        assertEquals(
            listOf(EmailAddress("a@upm.es"), EmailAddress("b@upm.es")),
            AddressParser.parse("Equipo: a@upm.es, b@upm.es;").addresses,
        )
    }

    @Test
    fun validatesAddresses() {
        listOf("ana@upm.es", "ana.perez+tfg@alumnos.upm.es", "o'neil@example.co.uk", "josé@correo.es", "a@xn--bcher-kva.de")
            .forEach { assertTrue(it, AddressParser.isValidAddress(it)) }
        listOf("", "ana", "ana@", "@upm.es", "ana@upm", "ana@@upm.es", "ana @upm.es", "ana..p@upm.es", ".ana@upm.es",
            "ana@-upm.es", "ana@upm..es", "ana@upm.e", "ana@upm.123")
            .forEach { assertFalse(it, AddressParser.isValidAddress(it)) }
    }

    @Test
    fun formatQuotesWhenNeeded() {
        assertEquals(
            "\"Ana Pérez\" <ana@upm.es>, b@upm.es, Bea Gomez <bea@upm.es>, \"Gómez, \\\"Carlos\\\"\" <c@upm.es>",
            AddressParser.format(
                listOf(
                    EmailAddress("ana@upm.es", "Ana Pérez"),
                    EmailAddress("b@upm.es"),
                    EmailAddress("bea@upm.es", "Bea Gomez"),
                    EmailAddress("c@upm.es", "Gómez, \"Carlos\""),
                ),
            ),
        )
        assertEquals("", AddressParser.format(emptyList()))
        // Header addresses that are not valid for sending are bracketed so that parse keeps them.
        assertEquals("<root@localhost>", AddressParser.format(listOf(EmailAddress("root@localhost"))))
        assertEquals(listOf(EmailAddress("root@localhost")), AddressParser.parse("<root@localhost>").addresses)
        assertEquals(listOf("root@localhost"), AddressParser.parse("root@localhost").invalid)
    }

    @Test
    fun formatRoundTripsWithParse() {
        val addresses = listOf(
            EmailAddress("ana@upm.es", "Ana Pérez Núñez"),
            EmailAddress("b@upm.es"),
            EmailAddress("c@upm.es", "Gómez, Carlos"),
            EmailAddress("d@upm.es", "Dani \"El Jefe\" Ruiz"),
            EmailAddress("e@upm.es", "Back\\slash; and <angles>"),
            EmailAddress("f@upm.es", "f@upm.es"),
            EmailAddress("g@upm.es", "Équipe: Été (été)"),
            EmailAddress("h@upm.es", "O'Neil"),
            EmailAddress("root@localhost"),
            EmailAddress("srv@[10.0.0.1]", "Servidor"),
            EmailAddress("MAILER-DAEMON@mx"),
        )
        val formatted = AddressParser.format(addresses)
        val parsed = AddressParser.parse(formatted)
        assertEquals(addresses, parsed.addresses)
        assertTrue(parsed.invalid.isEmpty())
    }
}
