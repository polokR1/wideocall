package pl.wideocall.app.call

object ChatSendPolicy {
    /** Success means accepted by the local channel, not delivered/read by the peer. */
    fun send(raw: String, phase: CallPhase, ready: Boolean, sendText: (String) -> Boolean): Result<String> = runCatching {
        check(phase in setOf(CallPhase.IN_CALL, CallPhase.IN_CHAT) && ready) {
            "Czat jest rozłączony. Tekst zachowano — spróbuj ponownie po połączeniu."
        }
        val text = EphemeralChatPolicy.sanitize(raw)
        require(text.isNotBlank()) { "Wiadomość jest pusta." }
        check(sendText(text)) { "Nie udało się wysłać wiadomości. Tekst zachowano — spróbuj ponownie." }
        text
    }
}
