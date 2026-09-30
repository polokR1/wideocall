package pl.wideocall.app

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.notifications.CallNotifications
import java.util.UUID

class IncomingNotificationTest {
    @Test fun repeatedIncomingDoesNotCreateAnotherAlertAndHandledCallCannotReappear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = UUID.randomUUID().toString()
        val notificationId = id.hashCode() and Int.MAX_VALUE
        try {
            CallNotifications.showIncoming(context, "Test", id)
            CallNotifications.showIncoming(context, "Test", id)
            repeat(10) {
                if (manager.activeNotifications.none { it.id == notificationId }) Thread.sleep(100)
            }
            val active = manager.activeNotifications.filter { it.id == notificationId }
            assertEquals(1, active.size)
            assertTrue(active.single().notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertTrue(active.single().notification.flags and Notification.FLAG_INSISTENT != 0)
            CallNotifications.cancel(context, id)
            CallNotifications.showIncoming(context, "Spóźniony sygnał", id)
            repeat(10) {
                if (manager.activeNotifications.any { it.id == notificationId }) Thread.sleep(100)
            }
            assertTrue(manager.activeNotifications.none { it.id == notificationId })
        } finally { CallNotifications.cancel(context, id) }
    }
}
