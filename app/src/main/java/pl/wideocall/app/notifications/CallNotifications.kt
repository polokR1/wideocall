package pl.wideocall.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import pl.wideocall.app.MainActivity
import pl.wideocall.app.R
import pl.wideocall.app.data.AppPreferences

object CallNotifications {
    const val ACTION_ANSWER = "pl.wideocall.app.ANSWER_CALL"
    const val ACTION_DECLINE = "pl.wideocall.app.DECLINE_CALL"
    const val ACTION_HANG_UP = "pl.wideocall.app.HANG_UP"
    const val ACTION_TELECOM_ANSWER = "pl.wideocall.app.TELECOM_ANSWER_CALL"
    const val ACTION_TELECOM_DECLINE = "pl.wideocall.app.TELECOM_DECLINE_CALL"
    const val ACTION_TELECOM_HANG_UP = "pl.wideocall.app.TELECOM_HANG_UP"
    private const val ACTION_SHOW_INCOMING = "pl.wideocall.app.SHOW_INCOMING_CALL"
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_CALLER_ID = "caller_id"
    // New id reapplies the corrected ringtone defaults for existing installations.
    private const val INCOMING_CHANNEL = "incoming_calls_v2"
    private const val ONGOING_CHANNEL = "ongoing_calls"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val incoming = NotificationChannel(
            INCOMING_CHANNEL,
            "Połączenia przychodzące",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alarmy przychodzących wideorozmów"
            setSound(ringtone, audio)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(
            ONGOING_CHANNEL,
            "Trwające rozmowy",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Sterowanie trwającą wideorozmową"
            setSound(null, null)
        }
        manager.createNotificationChannels(listOf(incoming, ongoing))
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    @Synchronized fun showIncoming(context: Context, callerId: String, callId: String) {
        val handled = context.getSharedPreferences("handled_calls", Context.MODE_PRIVATE)
        val posted = context.getSharedPreferences("posted_incoming_calls", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (handled.getLong(callId, 0L) > now || posted.getLong(callId, 0L) > now) {
            Log.i("CallNotifications", "incoming_notification_duplicate_suppressed")
            return
        }
        createChannels(context)
        val person = Person.Builder().setName(callerId).setImportant(true).build()
        val answer = callIntent(context, ACTION_ANSWER, callerId, callId, 10)
        val decline = callIntent(context, ACTION_DECLINE, callerId, callId, 20)
        val showIncoming = callIntent(context, ACTION_SHOW_INCOMING, callerId, callId, 5)
        val notification = NotificationCompat.Builder(context, INCOMING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Połączenie wideo")
            .setContentText(callerId)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(35_000L)
            .setFullScreenIntent(showIncoming, true)
            .setContentIntent(showIncoming)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .build()
        notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
        context.getSystemService(NotificationManager::class.java).notify(notificationId(callId), notification)
        // Reposting ONLY_ALERT_ONCE + INSISTENT can stop an existing system ringtone.
        // Deduplicate before notify, including FCM/WebSocket races before activeNotifications updates.
        posted.edit().apply {
            posted.all.forEach { (key, value) -> if ((value as? Long ?: 0L) <= now) remove(key) }
            putLong(callId, now + 120_000L)
        }.apply()
        Log.i("CallNotifications", "incoming_notification_posted")
    }

    @Synchronized fun cancel(context: Context, callId: String?) {
        if (callId == null) return
        val handled = context.getSharedPreferences("handled_calls", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        handled.edit().apply {
            handled.all.forEach { (key, value) -> if ((value as? Long ?: 0L) <= now) remove(key) }
            putLong(callId, now + 120_000L)
        }.apply()
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(callId))
        context.getSharedPreferences("posted_incoming_calls", Context.MODE_PRIVATE).edit().remove(callId).apply()
        Log.i("CallNotifications", "incoming_notification_cancelled")
    }

    fun dispatchAction(context: Context, action: String, callerId: String, callId: String) {
        runCatching { callIntent(context, action, callerId, callId, 60).send() }
            .onFailure { error ->
                AppPreferences(context).saveBackgroundIssue(
                    "Sterowanie połączeniem z ekranu blokady: ${error.javaClass.simpleName}.",
                )
            }
    }

    fun ongoingNotification(context: Context, callerId: String, callId: String): android.app.Notification {
        createChannels(context)
        val person = Person.Builder().setName(callerId).setImportant(true).build()
        val hangUp = callIntent(context, ACTION_HANG_UP, callerId, callId, 30)
        return NotificationCompat.Builder(context, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Trwa wideorozmowa")
            .setContentText(callerId)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .setContentIntent(callIntent(context, Intent.ACTION_MAIN, callerId, callId, 40))
            .build()
    }

    private fun callIntent(
        context: Context,
        action: String,
        callerId: String,
        callId: String,
        offset: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId(callId) + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(callId: String): Int = callId.hashCode() and Int.MAX_VALUE
}
