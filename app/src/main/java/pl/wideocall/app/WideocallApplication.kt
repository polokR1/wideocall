package pl.wideocall.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.notifications.ChatNotifications

class WideocallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppVisibility.initialize(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppVisibility)
        CallNotifications.createChannels(this)
        ChatNotifications.createChannel(this)
    }
}
