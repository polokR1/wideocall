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

object FriendNotifications {
    private const val CHANNEL_ID = "contact_invitations_v1"
    private const val ID_OFFSET = 44_731

    fun showRequest(context: Context, callerId: String, requestId: String) {
        show(context, "Nowe zaproszenie do kontaktów", callerId, requestId)
    }

    fun showResponse(context: Context, accepted: Boolean, callerId: String, requestId: String) {
        show(
            context,
            if (accepted) "Zaproszenie zaakceptowane" else "Zaproszenie odrzucone",
            callerId,
            requestId,
        )
    }

    private fun show(context: Context, title: String, text: String, requestId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zaproszenia do kontaktów", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Zaproszenia i odpowiedzi Wideocall"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            context,
            notificationId(requestId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationId(requestId),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notificationId(requestId: String): Int =
        ((requestId.hashCode() and Int.MAX_VALUE) + ID_OFFSET) and Int.MAX_VALUE
}
