package pl.wideocall.app.call

import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.data.Contact

class ChatWindowPolicyTest {
    private val a = Contact("ABCD-EF23", "A")
    private val b = Contact("GHJK-LM45", "B")
    private fun state(peer: Contact, visible: Boolean) = AppUiState("MNPR-ST67", "Test", emptyList(),
        chatPeerId = peer.id, chatPeerName = peer.name, chatVisible = visible, chatDraft = "Szkic")

    @Test fun delayedSendDoesNotReopenMinimizedWindow() {
        val before = state(a, false)
        assertEquals(before, ChatWindowPolicy.outgoing(before, a, chatOnly = true))
    }

    @Test fun queuedConnectionDoesNotStealAnotherLocalConversation() {
        val before = state(b, true)
        assertEquals(before, ChatWindowPolicy.outgoing(before, a, chatOnly = true))
    }

    @Test fun videoOrTransferUsesItsPeerWithoutShowingChat() {
        val next = ChatWindowPolicy.outgoing(state(a, true), b, chatOnly = false)
        assertFalse(next.chatVisible)
        assertEquals(b.id, next.chatPeerId)
        assertEquals("", next.chatDraft)
    }
}
