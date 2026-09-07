package pl.wideocall.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.notifications.CallNotifications

class WideocallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppVisibility)
        CallNotifications.createChannels(this)
    }
}
