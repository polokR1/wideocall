package pl.wideocall.app

import android.app.Notification
import android.app.NotificationManager
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.notifications.ChatConnectionService

class ChatConnectionServiceTest {
    @Test fun foregroundChatNoticeIsSilentRedactedAndRemovedAtEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { ChatConnectionService.start(it) }
                await { manager.activeNotifications.any { it.id == ChatConnectionService.NOTIFICATION_ID } }

                val entry = manager.activeNotifications.single { it.id == ChatConnectionService.NOTIFICATION_ID }
                val notification = entry.notification
                assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
                assertNull(notification.fullScreenIntent)
                assertNull(notification.sound)
                assertEquals("Szyfrowany czat aktywny", notification.extras.getString(Notification.EXTRA_TITLE))
                assertFalse(notification.extras.containsKey("peer"))
                assertFalse(notification.extras.containsKey("message"))
                assertEquals(NotificationManager.IMPORTANCE_LOW,
                    manager.getNotificationChannel("active_private_chat_v1")?.importance)

                val power = context.getSystemService(PowerManager::class.java)
                assertTrue(power.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK))
                scenario.onActivity { ChatConnectionService.stop(it) }
                await { manager.activeNotifications.none { it.id == ChatConnectionService.NOTIFICATION_ID } }
                assertTrue(manager.activeNotifications.none { it.id == ChatConnectionService.NOTIFICATION_ID })
            } finally {
                ChatConnectionService.stop(context)
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Powiadomienie czatu nie osiągnęło oczekiwanego stanu", condition())
    }
}
