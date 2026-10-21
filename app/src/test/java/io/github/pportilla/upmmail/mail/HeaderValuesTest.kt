package io.github.pportilla.upmmail.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderValuesTest {
    @Test
    fun repairsRawUtf8ReadOneBytePerCharacter() {
        assertEquals("Reunión", HeaderValues.repairUtf8("ReuniÃ³n"))
        assertEquals("Ana Pérez Núñez", HeaderValues.repairUtf8("Ana PÃ©rez NÃºÃ±ez"))
        assertEquals("Reunión de departamento", HeaderValues.cleanDecodedSubject("ReuniÃ³n de\r\n departamento"))
    }

    @Test
    fun keepsTextThatIsNotRawUtf8() {
        // Real Latin-1 is not valid UTF-8.
        assertEquals("Año", HeaderValues.repairUtf8("Año"))
        assertEquals("Ñandú", HeaderValues.repairUtf8("Ñandú"))
        assertEquals("plain", HeaderValues.repairUtf8("plain"))
        // Characters above U+00FF mean the text was decoded properly.
        assertEquals("Ã³ €", HeaderValues.repairUtf8("Ã³ €"))
    }
}
