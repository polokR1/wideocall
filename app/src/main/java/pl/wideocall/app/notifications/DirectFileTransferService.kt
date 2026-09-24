package pl.wideocall.app.notifications

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Keeps an explicitly started file transfer alive while its screen is minimized. */
class DirectFileTransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = intent?.getStringExtra(EXTRA_PEER) ?: return START_NOT_STICKY
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
        val notification = FileTransferNotifications.activeNotification(this, peer, sessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 47_912
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_SESSION_ID = "session_id"

        fun start(context: Context, peer: String, sessionId: String) {
            val intent = Intent(context, DirectFileTransferService::class.java).apply {
                putExtra(EXTRA_PEER, peer)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, DirectFileTransferService::class.java))
        }
    }
}
