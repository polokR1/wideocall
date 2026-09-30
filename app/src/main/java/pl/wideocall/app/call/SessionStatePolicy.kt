package pl.wideocall.app.call

import pl.wideocall.app.signaling.SignalMessage

/** Pure transitions shared by the UI controller and regression tests. */
object SessionStatePolicy {
    fun refreshing(state: AppUiState, activeSession: Boolean): AppUiState = state.copy(
        signalingConnected = false,
        phase = if (activeSession) state.phase else CallPhase.CONNECTING_SERVER,
        status = if (activeSession) "Sesja trwa • odnawiam sygnalizację…" else "Łączenie z usługą rozmów…",
        diagnostic = null,
    )

    fun signalingReady(state: AppUiState, activeSession: Boolean, mediaConnected: Boolean, kind: String): AppUiState =
        if (activeSession) state.copy(
            signalingConnected = true,
            status = if (mediaConnected) connectedStatus(kind) else state.status,
        ) else state.copy(signalingConnected = true, phase = CallPhase.READY, status = "Gotowy do rozmowy", diagnostic = null)

    fun signalingLost(state: AppUiState, activeSession: Boolean, reason: String): AppUiState = state.copy(
        signalingConnected = false,
        phase = if (activeSession) state.phase else CallPhase.SERVER_ERROR,
        status = if (activeSession) "Sesja trwa • odnawiam sygnalizację…" else "$reason • ponawiam połączenie…",
        diagnostic = "Sygnalizacja Cloudflare: $reason",
    )

    fun idlePhase(signalingConnected: Boolean): CallPhase =
        if (signalingConnected) CallPhase.READY else CallPhase.SERVER_ERROR

    fun transportConnected(state: AppUiState, kind: String): AppUiState = state.copy(
        phase = when (kind) {
            SignalMessage.SESSION_CHAT -> CallPhase.IN_CHAT
            SignalMessage.SESSION_FILE -> CallPhase.IN_FILE
            else -> CallPhase.IN_CALL
        },
        status = connectedStatus(kind),
        // Opening/minimizing a window is a user decision, not an ICE transition.
        diagnostic = if (state.signalingConnected) null else state.diagnostic,
    )

    fun shouldExpireSetup(expectedId: String, activeId: String?, phase: CallPhase): Boolean =
        expectedId == activeId && phase in setOf(
            CallPhase.OUTGOING, CallPhase.CONNECTING_CALL, CallPhase.CONNECTING_CHAT, CallPhase.CONNECTING_FILE,
        )

    private fun connectedStatus(kind: String): String = when (kind) {
        SignalMessage.SESSION_CHAT -> "Szyfrowany czat połączony"
        SignalMessage.SESSION_FILE -> "Prywatny transfer połączony"
        else -> "Połączono"
    }
}
