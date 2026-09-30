package pl.wideocall.app.call

import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.signaling.SignalMessage

class SessionStatePolicyTest {
    private fun state(phase: CallPhase) = AppUiState("ABCD-EF23", "Test", emptyList(), phase = phase, callId = "session-1234", signalingConnected = true)

    @Test fun refreshAndReadyPreserveEveryActivePhaseAndSessionId() {
        val phases = CallPhase.entries.filter { it !in setOf(CallPhase.READY, CallPhase.SERVER_ERROR, CallPhase.CONNECTING_SERVER) }
        for (phase in phases) {
            val refreshed = SessionStatePolicy.refreshing(state(phase), true)
            assertEquals(phase, refreshed.phase)
            assertFalse(refreshed.signalingConnected)
            assertEquals("session-1234", refreshed.callId)
            val restored = SessionStatePolicy.signalingReady(refreshed, true, false, SignalMessage.SESSION_VIDEO)
            assertEquals(phase, restored.phase)
            assertTrue(restored.signalingConnected)
        }
    }

    @Test fun hangupBetweenDisconnectAndReconnectDoesNotBecomeReadyOffline() {
        val disconnected = SessionStatePolicy.signalingLost(state(CallPhase.IN_CALL), true, "offline")
        assertEquals(CallPhase.IN_CALL, disconnected.phase)
        val ended = disconnected.copy(callId = null, phase = SessionStatePolicy.idlePhase(disconnected.signalingConnected))
        assertEquals(CallPhase.SERVER_ERROR, ended.phase)
        assertFalse(ended.signalingConnected)
        val retry = SessionStatePolicy.refreshing(ended, false)
        assertEquals(CallPhase.CONNECTING_SERVER, retry.phase)
        val restored = SessionStatePolicy.signalingReady(retry, false, false, SignalMessage.SESSION_VIDEO)
        assertEquals(CallPhase.READY, restored.phase)
        assertTrue(restored.signalingConnected)
        assertNull(restored.callId)
    }

    @Test fun repeatedIdleFailuresStayRetryable() {
        var current = state(CallPhase.READY).copy(callId = null)
        repeat(5) {
            current = SessionStatePolicy.signalingLost(current, false, "offline")
            assertEquals(CallPhase.SERVER_ERROR, current.phase)
            current = SessionStatePolicy.refreshing(current, false)
            assertEquals(CallPhase.CONNECTING_SERVER, current.phase)
            assertFalse(current.signalingConnected)
        }
    }

    @Test fun iceReconnectKeepsChatAndFileWindowsMinimized() {
        for (kind in listOf(SignalMessage.SESSION_CHAT, SignalMessage.SESSION_FILE, SignalMessage.SESSION_VIDEO)) {
            val minimized = state(CallPhase.CONNECTING_CHAT).copy(chatVisible = false, fileVisible = false, callVisible = false, chatDraft = "szkic")
            val connected = SessionStatePolicy.transportConnected(minimized, kind)
            assertFalse(connected.chatVisible)
            assertFalse(connected.fileVisible)
            assertFalse(connected.callVisible)
            assertEquals("szkic", connected.chatDraft)
        }
    }

    @Test fun iceConnectPreservesExplicitlyOpenedWindowsAndCorrectSessionKind() {
        val open = state(CallPhase.CONNECTING_CHAT).copy(chatVisible = true, fileVisible = true)
        assertEquals(CallPhase.IN_CHAT, SessionStatePolicy.transportConnected(open, SignalMessage.SESSION_CHAT).phase)
        assertEquals(CallPhase.IN_FILE, SessionStatePolicy.transportConnected(open, SignalMessage.SESSION_FILE).phase)
        assertEquals(CallPhase.IN_CALL, SessionStatePolicy.transportConnected(open, SignalMessage.SESSION_VIDEO).phase)
        assertTrue(SessionStatePolicy.transportConnected(open, SignalMessage.SESSION_CHAT).chatVisible)
        assertTrue(SessionStatePolicy.transportConnected(open, SignalMessage.SESSION_FILE).fileVisible)
    }

    @Test fun setupTimeoutAppliesToBothSidesAndAllSessionTypes() {
        for (phase in listOf(CallPhase.OUTGOING, CallPhase.CONNECTING_CALL, CallPhase.CONNECTING_CHAT, CallPhase.CONNECTING_FILE)) {
            assertTrue(SessionStatePolicy.shouldExpireSetup("session-1234", "session-1234", phase))
        }
    }

    @Test fun oldTimeoutCannotEndConnectedOrReplacementSession() {
        for (phase in CallPhase.entries) {
            assertFalse(SessionStatePolicy.shouldExpireSetup("old-session", "new-session", phase))
            assertFalse(SessionStatePolicy.shouldExpireSetup("old-session", null, phase))
        }
        for (phase in listOf(CallPhase.IN_CALL, CallPhase.IN_CHAT, CallPhase.IN_FILE, CallPhase.READY, CallPhase.INCOMING)) {
            assertFalse(SessionStatePolicy.shouldExpireSetup("session-1234", "session-1234", phase))
        }
    }
}
