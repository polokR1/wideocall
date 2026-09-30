package pl.wideocall.app.call

import org.junit.Assert.*
import org.junit.Test

class ChatSendPolicyTest {
    @Test fun disconnectedChannelRejectsWithoutAttemptingSend() {
        var attempted = false
        val result = ChatSendPolicy.send("szkic", CallPhase.IN_CHAT, false) { attempted = true; true }
        assertTrue(result.isFailure)
        assertFalse(attempted)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Tekst zachowano"))
    }

    @Test fun channelRejectingSendReturnsFailureInsteadOfSuccess() {
        val result = ChatSendPolicy.send("szkic", CallPhase.IN_CHAT, true) { false }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Tekst zachowano"))
    }

    @Test fun channelExceptionIsReturnedToUi() {
        val result = ChatSendPolicy.send("szkic", CallPhase.IN_CALL, true) { error("test channel failure") }
        assertTrue(result.isFailure)
        assertEquals("test channel failure", result.exceptionOrNull()!!.message)
    }

    @Test fun successfulSendReturnsSanitizedText() {
        var sent = ""
        val result = ChatSendPolicy.send("  wiadomość  ", CallPhase.IN_CHAT, true) { sent = it; true }
        assertTrue(result.isSuccess)
        assertEquals("wiadomość", result.getOrThrow())
        assertEquals(result.getOrThrow(), sent)
    }

    @Test fun wrongSessionOrBlankTextNeverSends() {
        for (phase in listOf(CallPhase.READY, CallPhase.IN_FILE, CallPhase.CONNECTING_CHAT)) {
            assertTrue(ChatSendPolicy.send("szkic", phase, true) { fail("must not send"); true }.isFailure)
        }
        assertTrue(ChatSendPolicy.send("  ", CallPhase.IN_CHAT, true) { fail("must not send"); true }.isFailure)
    }
}
