package pl.wideocall.app.notifications

import android.annotation.SuppressLint
import android.app.Service
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class OngoingCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerId = intent?.getStringExtra(CallNotifications.EXTRA_CALLER_ID) ?: return START_NOT_STICKY
        val callId = intent.getStringExtra(CallNotifications.EXTRA_CALL_ID) ?: return START_NOT_STICKY
        val notification = CallNotifications.ongoingNotification(this, callerId, callId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                ONGOING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ONGOING_NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 7643

        fun start(context: Context, callerId: String, callId: String) {
            val intent = Intent(context, OngoingCallService::class.java).apply {
                putExtra(CallNotifications.EXTRA_CALLER_ID, callerId)
                putExtra(CallNotifications.EXTRA_CALL_ID, callId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        @SuppressLint("ImplicitSamInstance") // stopService matches the explicit service component, not object identity.
        fun stop(context: Context) {
            context.stopService(Intent(context, OngoingCallService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(ONGOING_NOTIFICATION_ID)
        }
    }
}
