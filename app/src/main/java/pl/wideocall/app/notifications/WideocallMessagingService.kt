package pl.wideocall.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pl.wideocall.app.data.AppPreferences

class WideocallMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushTokenRegistry.refreshAfterTokenChange(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "incoming_call") return
        val callerId = data["callerId"] ?: return
        val recipientId = data["recipientId"] ?: return
        val callId = data["callId"] ?: return
        if (recipientId != AppPreferences(this).myId || AppVisibility.isForeground) return
        CallNotifications.showIncoming(this, callerId, callId)
    }
}
