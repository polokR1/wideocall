package pl.wideocall.app.notifications

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

object AppVisibility : DefaultLifecycleObserver {
    private var context: android.content.Context? = null

    fun initialize(context: android.content.Context) {
        this.context = context.applicationContext
    }

    @Volatile
    var isForeground: Boolean = false
        private set

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        context?.let { PresenceVisibility.update(it, online = true) }
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        context?.let { PresenceVisibility.update(it, online = false) }
    }
}
