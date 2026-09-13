package pl.wideocall.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.BuildConfig
import pl.wideocall.app.call.TelecomCallBridge

class WideocallMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!BuildConfig.FCM_ENABLED) return
        CloudflarePushRegistry.register(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.FCM_ENABLED) return
        runCatching { handleMessage(message.data) }
            .onFailure { error ->
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
            "incoming_chat" -> if (!AppVisibility.isForeground) {
                ChatNotifications.showInvitation(this, callerId, callId)
            }
            "incoming_call" -> if (!AppVisibility.isForeground) {
                TelecomCallBridge.registerIncoming(callId, callerId)
                CallNotifications.showIncoming(this, callerId, callId)
            }
        }
    }
}
