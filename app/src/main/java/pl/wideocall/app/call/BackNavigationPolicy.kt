package pl.wideocall.app.call

enum class BackNavigationAction {
    CLOSE_CHAT,
    HIDE_CALL,
    CONFIRM_EXIT,
}

object BackNavigationPolicy {
    fun resolve(phase: CallPhase, chatVisible: Boolean, callVisible: Boolean): BackNavigationAction = when {
        chatVisible && phase !in setOf(CallPhase.INCOMING, CallPhase.OUTGOING, CallPhase.CONNECTING_CALL, CallPhase.INCOMING_CHAT, CallPhase.INCOMING_FILE) ->
            BackNavigationAction.CLOSE_CHAT
        phase in setOf(CallPhase.OUTGOING, CallPhase.INCOMING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL) && callVisible ->
            BackNavigationAction.HIDE_CALL
        else -> BackNavigationAction.CONFIRM_EXIT
    }
}
