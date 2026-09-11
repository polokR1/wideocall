package pl.wideocall.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.BuildConfig
import pl.wideocall.app.call.TelecomCallBridge

class WideocallMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!BuildConfig.FCM_ENABLED) return
        PushTokenRegistry.refreshAfterTokenChange(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.FCM_ENABLED) return
        val data = message.data
        val type = data["type"] ?: return
        if (type !in setOf("incoming_call", "incoming_chat")) return
        val callerId = data["callerId"] ?: return
        val recipientId = data["recipientId"] ?: return
        val callId = data["callId"] ?: return
        if (recipientId != AppPreferences(this).myId || AppVisibility.isForeground) return
        if (type == "incoming_chat") {
            ChatNotifications.showInvitation(this, callerId, callId)
        } else {
            TelecomCallBridge.registerIncoming(callId, callerId)
            CallNotifications.showIncoming(this, callerId, callId)
        }
    }
}
