package pl.wideocall.app.data

import org.junit.Assert.*
import org.junit.Test

class ContactNamePolicyTest {
    @Test fun allowsPolishNamesAndTrimsWhitespace() {
        assertEquals("Żółta Motorola", ContactNamePolicy.validate("  Żółta Motorola  ").getOrThrow())
    }
    @Test fun rejectsBlankOverlongAndControlCharacters() {
        listOf("  ", "a".repeat(61), "Moto\nrola", "Moto\u0000rola").forEach {
            assertTrue(ContactNamePolicy.validate(it).isFailure)
        }
        assertTrue(ContactNamePolicy.validate("a".repeat(60)).isSuccess)
    }
}
