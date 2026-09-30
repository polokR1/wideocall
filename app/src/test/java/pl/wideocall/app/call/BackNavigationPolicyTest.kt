package pl.wideocall.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavigationPolicyTest {
    @Test fun offlineLocalChatMinimizesInsteadOfExiting() {
        for (phase in listOf(CallPhase.READY, CallPhase.SERVER_ERROR, CallPhase.CONNECTING_SERVER)) {
            assertEquals(BackNavigationAction.CLOSE_CHAT, BackNavigationPolicy.resolve(phase, true, true))
        }
    }
    @Test
    fun backFromChatReturnsToHome() {
        assertEquals(
            BackNavigationAction.CLOSE_CHAT,
            BackNavigationPolicy.resolve(CallPhase.IN_CHAT, chatVisible = true, callVisible = true),
        )
    }

    @Test
    fun backFromCallReturnsToHome() {
        assertEquals(
            BackNavigationAction.HIDE_CALL,
            BackNavigationPolicy.resolve(CallPhase.IN_CALL, chatVisible = false, callVisible = true),
        )
    }

    @Test
    fun backFromChatDuringCallReturnsToCall() {
        assertEquals(
            BackNavigationAction.CLOSE_CHAT,
            BackNavigationPolicy.resolve(CallPhase.IN_CALL, chatVisible = true, callVisible = true),
        )
    }

    @Test
    fun backFromHomeAsksBeforeExit() {
        assertEquals(
            BackNavigationAction.CONFIRM_EXIT,
            BackNavigationPolicy.resolve(CallPhase.READY, chatVisible = false, callVisible = false),
        )
        assertEquals(
            BackNavigationAction.CONFIRM_EXIT,
            BackNavigationPolicy.resolve(CallPhase.IN_CALL, chatVisible = false, callVisible = false),
        )
    }
}
