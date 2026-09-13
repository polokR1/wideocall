package pl.wideocall.app.call

object EphemeralChatPolicy {
    const val LIFETIME_MS = 60_000L
    const val MAX_LENGTH = 1_000

    fun sanitize(raw: String): String = raw.trim().take(MAX_LENGTH)

    fun isExpired(openedAt: Long?, now: Long): Boolean =
        openedAt != null && now.coerceAtLeast(openedAt) - openedAt >= LIFETIME_MS
}
