package pl.wideocall.app.signaling

import org.junit.Assert.*
import org.junit.Test

class ConnectionAttemptPolicyTest {
    @Test fun networkCallbackDoesNotInterruptAuthentication() {
        assertFalse(ConnectionAttemptPolicy.shouldStart("A", "A", connecting = true, ready = false, force = false))
    }
    @Test fun healthySocketIsNotReplacedByRepeatedCallbacks() {
        assertFalse(ConnectionAttemptPolicy.shouldStart("A", "A", connecting = false, ready = true, force = false))
    }
    @Test fun failedSocketCanReconnect() {
        assertTrue(ConnectionAttemptPolicy.shouldStart("A", "A", connecting = false, ready = false, force = false))
    }
    @Test fun actualNetworkChangeAndManualRetryCanReplaceSocket() {
        assertTrue(ConnectionAttemptPolicy.shouldStart("A", "A", connecting = true, ready = false, force = true))
        assertTrue(ConnectionAttemptPolicy.shouldStart("A", "A", connecting = false, ready = true, force = true))
    }
    @Test fun differentIdentityNeedsNewAuthentication() {
        assertTrue(ConnectionAttemptPolicy.shouldStart("B", "A", connecting = false, ready = true, force = false))
    }
}
