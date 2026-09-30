package pl.wideocall.app.security

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class LocalChatCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripAndRandomIv() {
        val key = key()
        val plaintext = "Tajna wiadomość".toByteArray()
        val first = LocalChatCipher.encrypt(plaintext, key)
        val second = LocalChatCipher.encrypt(plaintext, key)
        assertArrayEquals(plaintext, LocalChatCipher.decrypt(first, key))
        assertFalse(first.contentEquals(second))
        assertFalse(String(first).contains("Tajna wiadomość"))
    }

    @Test fun tamperingFailsClosed() {
        val key = key()
        val encrypted = LocalChatCipher.encrypt("secret".toByteArray(), key)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { LocalChatCipher.decrypt(encrypted, key) }.isFailure)
    }

    @Test fun wrongKeyFailsClosed() {
        assertTrue(runCatching { LocalChatCipher.decrypt(LocalChatCipher.encrypt("secret".toByteArray(), key()), key()) }.isFailure)
    }

    @Test fun invalidFormatIsRejected() {
        assertTrue(runCatching { LocalChatCipher.decrypt(byteArrayOf(1, 2), key()) }.isFailure)
    }
}
