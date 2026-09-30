package pl.wideocall.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.filetransfer.*
import java.security.MessageDigest

class FileTransferConsentTest {
    @Test fun metadataAndEarlyBytesCannotStartReceivingWithoutConsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controls = mutableListOf<JSONObject>()
        var state: FileTransferState? = null
        val manager = DirectFileTransferManager(context, scope,
            sendText = { controls.add(JSONObject(it)); true }, sendBinary = { true }, bufferedAmount = { 0L }, onState = { state = it })
        try {
            manager.onText("""{"kind":"wideocall-file-v1","action":"meta","id":"consent-test-123","name":"example.txt","mime":"text/plain","size":3}""")
            assertEquals(FileTransferStatus.WAITING_FOR_ACCEPTANCE, state?.status)
            assertEquals("example.txt", state?.fileName)
            assertTrue(controls.isEmpty())
            assertNull(state?.receivedFilePath)
            manager.onBinary(byteArrayOf(9, 9, 9))
            assertEquals(0L, state?.transferredBytes)
            manager.acceptReceivedFile()
            manager.acceptReceivedFile()
            assertEquals(1, controls.count { it.optString("action") == "accept" })
            assertEquals(FileTransferStatus.TRANSFERRING, state?.status)
            val bytes = byteArrayOf(1, 2, 3)
            manager.onBinary(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            manager.onText("""{"kind":"wideocall-file-v1","action":"complete","id":"consent-test-123","sha256":"$hash"}""")
            assertEquals(FileTransferStatus.COMPLETE, state?.status)
            assertEquals(3L, state?.transferredBytes)
            assertNotNull(state?.receivedFilePath)
            assertEquals(1, controls.count { it.optString("action") == "verified" })
        } finally { manager.reset(deleteCompleted = true); scope.cancel() }
    }

    @Test fun rejectingFileDoesNotAcknowledgeOrStoreItsBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controls = mutableListOf<JSONObject>()
        var state: FileTransferState? = null
        val manager = DirectFileTransferManager(context, scope,
            sendText = { controls.add(JSONObject(it)); true }, sendBinary = { true }, bufferedAmount = { 0L }, onState = { state = it })
        try {
            manager.onText("""{"kind":"wideocall-file-v1","action":"meta","id":"reject-test-123","name":"example.txt","size":3}""")
            manager.cancel()
            manager.acceptReceivedFile()
            manager.onBinary(byteArrayOf(1, 2, 3))
            assertEquals(FileTransferStatus.CANCELLED, state?.status)
            assertEquals(0L, state?.transferredBytes)
            assertNull(state?.receivedFilePath)
            assertEquals(0, controls.count { it.optString("action") == "accept" })
        } finally { manager.reset(deleteCompleted = true); scope.cancel() }
    }
}
