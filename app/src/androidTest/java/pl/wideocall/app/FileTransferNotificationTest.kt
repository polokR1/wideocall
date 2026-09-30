package pl.wideocall.app

import android.app.NotificationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.notifications.DirectFileTransferService
import pl.wideocall.app.notifications.FileTransferNotifications
import java.util.UUID

class FileTransferNotificationTest {
    @Test fun finishedInvitationCannotReappearFromDelayedPush() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = UUID.randomUUID().toString()
        val notificationId = ((id.hashCode() and Int.MAX_VALUE) + 47_911) and Int.MAX_VALUE
        try {
            FileTransferNotifications.showInvitation(context, "Test", id)
            await { manager.activeNotifications.any { it.id == notificationId } }
            FileTransferNotifications.cancel(context, id)
            await { manager.activeNotifications.none { it.id == notificationId } }
            FileTransferNotifications.showInvitation(context, "Opóźniony sygnał", id)
            Thread.sleep(200)
            assertTrue(manager.activeNotifications.none { it.id == notificationId })
        } finally { FileTransferNotifications.cancel(context, id) }
    }

    @Test fun endingForegroundTransferRemovesItsOwnNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { DirectFileTransferService.start(it, "Test", UUID.randomUUID().toString()) }
                await { manager.activeNotifications.any { it.id == 47_912 } }
                scenario.onActivity { DirectFileTransferService.stop(it) }
                await { manager.activeNotifications.none { it.id == 47_912 } }
                assertTrue(manager.activeNotifications.none { it.id == 47_912 })
            } finally { DirectFileTransferService.stop(context) }
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Powiadomienie nie osiągnęło oczekiwanego stanu", condition())
    }
}
