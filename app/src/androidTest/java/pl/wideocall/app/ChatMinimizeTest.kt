package pl.wideocall.app

import android.app.Notification
import android.app.NotificationManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.call.AppUiState
import pl.wideocall.app.call.CallPhase
import pl.wideocall.app.call.CallViewModel
import pl.wideocall.app.notifications.ChatNotifications
import pl.wideocall.app.signaling.SignalMessage
import java.util.UUID

class ChatMinimizeTest {
    @Test fun updateRemovesOnlyLegacyStickyChatNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val peer = "GHJK-LM45"
        val legacyId = 931337
        try {
            ChatNotifications.createChannel(context)
            manager.notify(legacyId, Notification.Builder(context, "encrypted_chat_secret_v2")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Szyfrowany czat jest zminimalizowany").setOngoing(true).build())
            ChatNotifications.showMessage(context, "Test", peer)
            val messageId = (("local:$peer".hashCode() and Int.MAX_VALUE) + 31_337) and Int.MAX_VALUE
            repeat(30) { if (manager.activeNotifications.none { it.id == legacyId }) Thread.sleep(100) }
            assertTrue(manager.activeNotifications.any { it.id == legacyId })
            ChatNotifications.removeLegacyMinimizedNotifications(context)
            repeat(30) { if (manager.activeNotifications.any { it.id == legacyId }) Thread.sleep(100) }
            assertTrue(manager.activeNotifications.none { it.id == legacyId })
            assertTrue(manager.activeNotifications.any { it.id == messageId })
        } finally {
            manager.cancel(legacyId)
            ChatNotifications.cancel(context, "local:$peer")
        }
    }

    @Test fun minimizeIsSilentAndAnotherLocalConversationDoesNotReplaceItsTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val session = UUID.randomUUID().toString()
        val peer = "ABCD-EF23"
        val otherPeer = "GHJK-LM45"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[CallViewModel::class.java]
                // Inject an established data-only session without calling any real contact.
                setField(model, "activeCallId", session)
                setField(model, "activeRemoteId", peer)
                setField(model, "activeSessionKind", SignalMessage.SESSION_CHAT)
                @Suppress("UNCHECKED_CAST")
                val state = CallViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(model) as MutableStateFlow<AppUiState>
                state.value = state.value.copy(phase = CallPhase.IN_CHAT, callId = session, remoteId = peer,
                    chatVisible = true, chatPeerId = peer)
                model.closeChatLocally()
                assertFalse(model.state.value.chatVisible)
                val id = ((session.hashCode() and Int.MAX_VALUE) + 31_337) and Int.MAX_VALUE
                assertTrue(manager.activeNotifications.none { it.id == id })
                model.openLocalChat(otherPeer)
                model.onAppVisible()
                assertTrue(model.state.value.chatVisible)
                assertEquals(otherPeer, model.state.value.chatPeerId)
                assertEquals(peer, model.state.value.remoteId)
                assertEquals(session, model.state.value.callId)
                setField(model, "activeCallId", null)
                setField(model, "activeRemoteId", null)
                setField(model, "activeSessionKind", SignalMessage.SESSION_VIDEO)
            }
        }
    }

    @Test fun realUnreadMessageIsPrivateDismissibleAndNeverFullScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val peer = "GHJK-LM45"
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            ChatNotifications.showMessage(context, "Test", peer)
            val id = (("local:$peer".hashCode() and Int.MAX_VALUE) + 31_337) and Int.MAX_VALUE
            repeat(30) { if (manager.activeNotifications.none { it.id == id }) Thread.sleep(100) }
            val notification = manager.activeNotifications.single { it.id == id }.notification
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
            assertNull(notification.fullScreenIntent)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        } finally { ChatNotifications.cancel(context, "local:$peer") }
    }

    private fun setField(model: CallViewModel, name: String, value: Any?) {
        CallViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
    }
}
