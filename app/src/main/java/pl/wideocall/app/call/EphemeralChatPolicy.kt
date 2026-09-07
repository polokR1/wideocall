package pl.wideocall.app.call

object EphemeralChatPolicy {
    const val LIFETIME_MS = 60_000L
    const val MAX_LENGTH = 1_000

    fun sanitize(raw: String): String = raw.trim().take(MAX_LENGTH)

    fun isExpired(createdAt: Long, now: Long): Boolean =
        now.coerceAtLeast(createdAt) - createdAt >= LIFETIME_MS
}
