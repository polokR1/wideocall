package pl.wideocall.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.call.EphemeralChatMessage
import pl.wideocall.app.call.LocalChatStore
import pl.wideocall.app.notifications.ChatNotifications
import java.io.File
import java.util.UUID

class LocalChatStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun notificationAndDeliveryAckDoNotStartRecipientExpiry() = runBlocking {
        var now = 2_000_000L
        val fileName = "test-chat-${UUID.randomUUID()}.enc"
        val file = File(context.noBackupFilesDir, fileName)
        val peer = "ABCD-EF23"
        try {
            var store = LocalChatStore(context, fileName) { now }
            store.add(peer, EphemeralChatMessage("delivery-only", "Nie otwarto", false, now))
            ChatNotifications.showMessage(context, "Test", peer)
            // Delivery ACK can change only an outgoing entry, never the recipient's opened timestamp.
            store.acknowledged(peer, "delivery-only", now)
            now += 120_000L
            store = LocalChatStore(context, fileName) { now }
            store.prune()
            assertNull(store.read(peer).single().openedAt)
            assertEquals("Nie otwarto", store.read(peer).single().text)
            store.opened(peer, now)
            now += 59_999L
            assertEquals(1, store.read(peer).size)
            now += 1L
            assertTrue(store.read(peer).isEmpty())
        } finally {
            ChatNotifications.cancel(context, "local:$peer")
            file.delete(); File(file.path + ".bak").delete()
        }
    }

    @Test fun unreadAndPendingSurviveRestartAndExpireOnlyAfterOpening() = runBlocking {
        var now = 1_000_000L
        val fileName = "test-chat-${UUID.randomUUID()}.enc"
        val file = File(context.noBackupFilesDir, fileName)
        try {
            var store = LocalChatStore(context, fileName) { now }
            val peer = "ABCD-EF23"
            store.add(peer, EphemeralChatMessage("in", "tajna treść", false, now))
            store.add(peer, EphemeralChatMessage("out", "do wysłania", true, now, pending = true))
            assertFalse(String(file.readBytes()).contains("tajna treść"))
            now += 120_000
            store = LocalChatStore(context, fileName) { now }
            assertEquals(2, store.read(peer).size)
            store.opened(peer, now)
            now += 60_000
            store.prune()
            assertEquals(listOf("out"), store.read(peer).map { it.id })
            assertFalse(store.add(peer, EphemeralChatMessage("in", "ponowienie", false, now)))
            store.acknowledged(peer, "out", null)
            now += 120_000
            assertEquals(1, store.read(peer).size)
            store.opened(peer, now)
            now += 60_000
            assertTrue(store.read(peer).isEmpty())
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }

    @Test fun peersAreSeparatedAndPendingCanBeDiscarded() = runBlocking {
        val fileName = "test-chat-${UUID.randomUUID()}.enc"
        val file = File(context.noBackupFilesDir, fileName)
        try {
            val store = LocalChatStore(context, fileName)
            store.add("AAAA-BBBB", EphemeralChatMessage("same", "A", true, 0, pending = true))
            store.add("CCCC-DDDD", EphemeralChatMessage("same", "B", true, 0, pending = true))
            assertEquals("A", store.read("AAAA-BBBB").single().text)
            store.discardPending("AAAA-BBBB")
            assertTrue(store.read("AAAA-BBBB").isEmpty())
            assertEquals("B", store.read("CCCC-DDDD").single().text)
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
}
