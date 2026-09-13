package pl.wideocall.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralChatPolicyTest {
    @Test
    fun messageExpiresExactlyAfterOneMinute() {
        assertFalse(EphemeralChatPolicy.isExpired(openedAt = null, now = 100_000L))
        assertFalse(EphemeralChatPolicy.isExpired(openedAt = 1_000L, now = 60_999L))
        assertTrue(EphemeralChatPolicy.isExpired(openedAt = 1_000L, now = 61_000L))
    }

    @Test
    fun messageIsTrimmedAndBounded() {
        assertEquals("hej", EphemeralChatPolicy.sanitize("  hej  "))
        assertEquals(1_000, EphemeralChatPolicy.sanitize("x".repeat(1_200)).length)
    }
}
