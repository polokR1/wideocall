package pl.wideocall.app.call

object CallScreenAwakePolicy {
    fun enabled(phase: CallPhase): Boolean = phase in setOf(
        CallPhase.OUTGOING, CallPhase.INCOMING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL,
    )
}
