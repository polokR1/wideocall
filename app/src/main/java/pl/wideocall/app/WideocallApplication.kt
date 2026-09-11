package pl.wideocall.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import pl.wideocall.app.call.TelecomCallBridge
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.notifications.ChatNotifications

class WideocallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance(),
        )
        AppVisibility.initialize(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppVisibility)
        CallNotifications.createChannels(this)
        ChatNotifications.createChannel(this)
        TelecomCallBridge.initialize(this)
    }
}
