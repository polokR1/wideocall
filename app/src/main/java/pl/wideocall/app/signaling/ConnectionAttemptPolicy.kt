package pl.wideocall.app.signaling

/** A network callback must not cancel a healthy or still authenticating socket. */
object ConnectionAttemptPolicy {
    fun shouldStart(requestedId: String, currentId: String?, connecting: Boolean, ready: Boolean, force: Boolean): Boolean =
        force || requestedId != currentId || (!connecting && !ready)
}
