package pl.wideocall.app.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectFileTransferManagerTest {
    @Test
    fun `sanitizes path separators and control characters from received name`() {
        assertEquals(
            "sekret_test_.pdf",
            DirectFileTransferManager.sanitizeFileName("../sekret\\test\u0000.pdf"),
        )
    }

    @Test
    fun `uses safe fallback for an empty file name`() {
        assertEquals("plik", DirectFileTransferManager.sanitizeFileName("\u0000/"))
    }

    @Test
    fun `accepts only nonempty files up to one hundred megabytes`() {
        assertFalse(DirectFileTransferManager.isSupportedSize(0))
        assertTrue(DirectFileTransferManager.isSupportedSize(DirectFileTransferManager.MAX_FILE_BYTES))
        assertFalse(DirectFileTransferManager.isSupportedSize(DirectFileTransferManager.MAX_FILE_BYTES + 1))
    }
}
