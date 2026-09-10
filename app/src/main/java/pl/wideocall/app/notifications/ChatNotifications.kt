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

object ChatNotifications {
    private const val CHANNEL_ID = "encrypted_chat_secret_v2"
    private const val ID_OFFSET = 31_337

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Szyfrowany czat",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Zaproszenia i nowe wiadomości znikającego czatu"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }

    fun showInvitation(context: Context, callerId: String, sessionId: String) {
        show(
            context = context,
            title = "Zaproszenie do szyfrowanego czatu",
            text = callerId,
            sessionId = sessionId,
            timeoutMs = 45_000L,
        )
    }

    fun showMessage(context: Context, callerId: String, sessionId: String?) {
        if (sessionId == null) return
        show(
            context = context,
            title = "Nowa znikająca wiadomość",
            text = "$callerId • treść ukryta",
            sessionId = sessionId,
            timeoutMs = 60_000L,
        )
    }

    fun cancel(context: Context, sessionId: String?) {
        if (sessionId == null) return
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(sessionId))
    }

    private fun show(context: Context, title: String, text: String, sessionId: String, timeoutMs: Long) {
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
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
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMs)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId(sessionId), notification)
    }

    private fun notificationId(sessionId: String): Int =
        ((sessionId.hashCode() and Int.MAX_VALUE) + ID_OFFSET) and Int.MAX_VALUE
}
