package pl.wideocall.app.call

import pl.wideocall.app.data.Contact

/** A transport starting in the background does not own the currently viewed conversation. */
object ChatWindowPolicy {
    fun outgoing(current: AppUiState, contact: Contact, chatOnly: Boolean): AppUiState {
        if (chatOnly && current.chatPeerId != null) return current
        return current.copy(
            chatPeerId = contact.id,
            chatPeerName = contact.name,
            chatVisible = false,
            chatMessages = if (current.chatPeerId == contact.id) current.chatMessages else emptyList(),
            chatDraft = if (current.chatPeerId == contact.id) current.chatDraft else "",
        )
    }
}
