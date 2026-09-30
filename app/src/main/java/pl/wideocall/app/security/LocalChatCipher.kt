package pl.wideocall.app.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a fresh provider-generated IV; no plaintext temporary files. */
object LocalChatCipher {
    private val aad = "wideocall-local-chat-v1".toByteArray(Charsets.UTF_8)
    fun encrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        require(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
    }
    fun decrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Nieprawidłowy format lokalnej skrzynki." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
    }
}
