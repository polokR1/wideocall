package pl.wideocall.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pl.wideocall.app.data.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && AppPreferences(context).watchEnabled) {
            CallWatcherService.start(context)
        }
    }
}
