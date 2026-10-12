package pl.wideocall.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.BuildConfig
import pl.wideocall.app.call.TelecomCallBridge
import pl.wideocall.app.signaling.SignalMessage
import android.util.Log

class WideocallMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!BuildConfig.FCM_ENABLED) return
        CloudflarePushRegistry.register(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.FCM_ENABLED) return
        Log.i("WideocallPush", "wake_received foreground=${AppVisibility.isForeground} priority=${message.priority} originalPriority=${message.originalPriority}")
        runCatching { handleMessage(message.data) }
            .onFailure { error ->
                Log.w("WideocallPush", "wake_handler_failed ${error.javaClass.simpleName}")
                AppPreferences(this).saveBackgroundIssue(
                    "Powiadomienia w tle: ${error.javaClass.simpleName}. Otwórz aplikację i spróbuj ponownie.",
                )
            }
    }

    private fun handleMessage(data: Map<String, String>) {
        val type = data["type"] ?: return
        val callerId = data["callerId"] ?: return
        val recipientId = data["recipientId"] ?: return
        val callId = data["callId"] ?: return
        if (recipientId != AppPreferences(this).myId) return
        when (type) {
            "call_cancelled" -> {
                CallNotifications.cancel(this, callId)
                TelecomCallBridge.disconnectRemote(callId)
            }
            "chat_cancelled" -> ChatNotifications.cancel(this, callId)
            "file_cancelled" -> FileTransferNotifications.cancel(this, callId)
            "contact_request" -> if (!AppVisibility.isForeground) {
                FriendNotifications.showRequest(this, callerId, callId)
            }
            "contact_accepted" -> if (!AppVisibility.isForeground) {
                FriendNotifications.showResponse(this, true, callerId, callId)
            }
            "contact_rejected" -> if (!AppVisibility.isForeground) {
                FriendNotifications.showResponse(this, false, callerId, callId)
            }
            "contact_cancelled" -> {
                val preferences = AppPreferences(this)
                val request = preferences.friendRequests().firstOrNull {
                    it.requestId == callId && it.fromId == callerId
                }
                if (request != null) preferences.removeFriendRequest(request.requestId)
                FriendNotifications.cancel(this, callId)
            }
            "incoming_chat" -> ChatNotifications.showInvitation(this, callerId, callId)
            "incoming_file" -> FileTransferNotifications.showInvitation(this, callerId, callId)
            "incoming_call" -> if (!AppVisibility.isForeground) {
                if (CallNotifications.isHandled(this, callId)) {
                    Log.i("WideocallPush", "late_call_wake_suppressed")
                    return
                }
                val video = data["sessionKind"] != SignalMessage.SESSION_AUDIO
                TelecomCallBridge.registerIncoming(callId, callerId, video)
                CallNotifications.showIncoming(this, callerId, callId, video)
            }
        }
    }
}
