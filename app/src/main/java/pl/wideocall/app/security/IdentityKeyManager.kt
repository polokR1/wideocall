package pl.wideocall.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Long-term signing identity. The private key is non-exportable from Android Keystore. */
object IdentityKeyManager {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wideocall_identity_signing_v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val SAFETY_DIGITS = 60

    @Volatile
    private var keyPair: KeyPair? = null

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(getOrCreateKeyPair().public.encoded)

    fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(getOrCreateKeyPair().private)
        signature.update(payload)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun verify(publicKeyBase64: String, payload: ByteArray, signatureBase64: String): Boolean = runCatching {
        val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
        if (publicKeyBytes.size !in 70..256) return false
        val publicKey = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            .generatePublic(X509EncodedKeySpec(publicKeyBytes)) as? ECPublicKey ?: return false
        if (publicKey.params.curve.field.fieldSize != 256) return false
        val signatureBytes = Base64.getDecoder().decode(signatureBase64)
        if (signatureBytes.size !in 64..144) return false
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(payload)
            verify(signatureBytes)
        }
    }.getOrDefault(false)

    fun safetyNumber(peerPublicKeyBase64: String): String =
        safetyNumberForKeys(publicKeyBase64(), peerPublicKeyBase64)

    fun safetyNumberForKeys(firstPublicKeyBase64: String, secondPublicKeyBase64: String): String {
        val own = Base64.getDecoder().decode(firstPublicKeyBase64)
        val peer = Base64.getDecoder().decode(secondPublicKeyBase64)
        val ordered = listOf(own, peer).sortedWith(Comparator(::compareBytes))
        val digest = MessageDigest.getInstance("SHA-512").apply {
            update("Wideocall safety number v1".toByteArray(Charsets.UTF_8))
            ordered.forEach {
                update(it.size.toString().toByteArray(Charsets.US_ASCII))
                update(0.toByte())
                update(it)
            }
        }.digest()
        val digits = BigInteger(1, digest).toString(10).padStart(155, '0').take(SAFETY_DIGITS)
        return digits.chunked(5).joinToString(" ")
    }

    @Synchronized
    private fun getOrCreateKeyPair(): KeyPair {
        keyPair?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey).also { keyPair = it }
        }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKeyPair()
        }.also { keyPair = it }
    }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}
