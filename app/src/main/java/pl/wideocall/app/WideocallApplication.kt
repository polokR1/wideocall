package pl.wideocall.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import pl.wideocall.app.call.TelecomCallBridge
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.notifications.ChatNotifications
import pl.wideocall.app.notifications.FileTransferNotifications
import pl.wideocall.app.call.LocalChatStore
import pl.wideocall.app.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class WideocallApplication : Application() {
    val localChatStore by lazy { LocalChatStore(this) }
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppVisibility)
        CallNotifications.createChannels(this)
        ChatNotifications.createChannel(this)
        ChatNotifications.removeLegacyMinimizedNotifications(this)
        FileTransferNotifications.createChannel(this)
        TelecomCallBridge.initialize(this)
        maintenanceScope.launch {
            while (true) {
                runCatching { localChatStore.prune() }.onFailure {
                    AppPreferences(this@WideocallApplication).saveBackgroundIssue("Lokalny czat: ${it.javaClass.simpleName}. Nie usunięto danych.")
                }
                delay(15_000)
            }
        }
    }
}
