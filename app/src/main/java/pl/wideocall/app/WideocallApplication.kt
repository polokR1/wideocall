package pl.wideocall.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import pl.wideocall.app.call.TelecomCallBridge
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.notifications.ChatNotifications

class WideocallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppVisibility)
        CallNotifications.createChannels(this)
        ChatNotifications.createChannel(this)
        TelecomCallBridge.initialize(this)
    }
}
