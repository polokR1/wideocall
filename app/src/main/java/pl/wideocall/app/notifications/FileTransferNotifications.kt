package pl.wideocall.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import pl.wideocall.app.MainActivity
import pl.wideocall.app.R

object FileTransferNotifications {
    const val ACTION_OPEN_FILE_TRANSFER = "pl.wideocall.app.action.OPEN_FILE_TRANSFER"
    const val EXTRA_SESSION_ID = "file_session_id"
    private const val CHANNEL_ID = "direct_file_transfer_v1"
    private const val ID_OFFSET = 47_911

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bezpośrednie pliki", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Prośby o prywatny transfer pliku bez serwera przechowującego treść"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun showInvitation(context: Context, callerId: String, sessionId: String) {
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FILE_TRANSFER
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            context,
            notificationId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Prywatny transfer pliku")
            .setContentText("$callerId chce przesłać plik")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId(sessionId), notification)
    }

    fun activeNotification(context: Context, callerId: String, sessionId: String): Notification {
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FILE_TRANSFER
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            context,
            notificationId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transfer pliku działa")
            .setContentText("$callerId • dotknij, aby zobaczyć postęp")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .build()
    }

    fun cancel(context: Context, sessionId: String?) {
        if (sessionId == null) return
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(sessionId))
    }

    private fun notificationId(sessionId: String): Int =
        ((sessionId.hashCode() and Int.MAX_VALUE) + ID_OFFSET) and Int.MAX_VALUE
}
