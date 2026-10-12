package pl.wideocall.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import pl.wideocall.app.MainActivity
import pl.wideocall.app.R

object ChatNotifications {
    const val ACTION_OPEN_CHAT = "pl.wideocall.app.action.OPEN_CHAT"
    const val EXTRA_SESSION_ID = "chat_session_id"
    const val EXTRA_PEER_ID = "chat_peer_id"
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

    @Synchronized fun showInvitation(context: Context, callerId: String, sessionId: String) {
        if (context.getSharedPreferences("handled_chat_invitations", Context.MODE_PRIVATE)
                .getLong(sessionId, 0L) > System.currentTimeMillis()) {
            Log.i("WideocallChat", "handled_invitation_suppressed")
            return
        }
        show(
            context = context,
            title = "Zaproszenie do szyfrowanego czatu",
            text = callerId,
            sessionId = sessionId,
            timeoutMs = 45_000L,
        )
    }

    fun showMessage(context: Context, callerId: String, peerId: String) {
        show(
            context = context,
            title = "Nowa znikająca wiadomość",
            text = "$callerId • treść ukryta",
            sessionId = "local:$peerId",
            timeoutMs = null,
            peerId = peerId,
        )
    }

    @Synchronized fun cancel(context: Context, sessionId: String?) {
        if (sessionId == null) return
        if (!sessionId.startsWith("local:")) {
            val handled = context.getSharedPreferences("handled_chat_invitations", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            handled.edit().apply {
                handled.all.forEach { (key, value) -> if ((value as? Long ?: 0L) <= now) remove(key) }
                putLong(sessionId, now + 120_000L)
            }.apply()
        }
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(sessionId))
    }

    fun removeLegacyMinimizedNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter {
            it.notification.channelId == CHANNEL_ID &&
                it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
                it.notification.extras.getString(Notification.EXTRA_TITLE) == "Szyfrowany czat jest zminimalizowany"
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun show(
        context: Context,
        title: String,
        text: String,
        sessionId: String,
        timeoutMs: Long?,
        peerId: String? = null,
    ) {
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_PEER_ID, peerId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            context,
            notificationId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
        timeoutMs?.let(builder::setTimeoutAfter)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId(sessionId), builder.build())
        // Requesting a notification is not proof that the OS displayed a banner or played sound.
        Log.i("WideocallChat", "notification_requested message=${peerId != null} enabled=${manager.areNotificationsEnabled()} channelImportance=${manager.getNotificationChannel(CHANNEL_ID)?.importance}")
    }

    private fun notificationId(sessionId: String): Int =
        ((sessionId.hashCode() and Int.MAX_VALUE) + ID_OFFSET) and Int.MAX_VALUE
}
