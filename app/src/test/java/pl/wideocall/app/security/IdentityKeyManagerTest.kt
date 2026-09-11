package pl.wideocall.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.wideocall.app.signaling.SignalMessage
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class IdentityKeyManagerTest {
    @Test
    fun signatureProtectsEveryCanonicalSignalField() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val message = SignalMessage(
            type = "offer",
            from = "ABCD-EF23",
            to = "WXYZ-4567",
            callId = "call_12345678",
            sdpType = "offer",
            sdp = "v=0\r\n",
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(message.signingPayload(publicKey))
            Base64.getEncoder().encodeToString(sign())
        }

        assertTrue(IdentityKeyManager.verify(publicKey, message.signingPayload(publicKey), signature))
        assertFalse(
            IdentityKeyManager.verify(
                publicKey,
                message.copy(to = "AAAA-BBBB").signingPayload(publicKey),
                signature,
            ),
        )
    }

    @Test
    fun safetyNumberIsSymmetricAndHasSixtyDigits() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val first = Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
        val second = Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)

        val forward = IdentityKeyManager.safetyNumberForKeys(first, second)
        val reverse = IdentityKeyManager.safetyNumberForKeys(second, first)

        assertEquals(forward, reverse)
        assertEquals(60, forward.count(Char::isDigit))
        assertEquals(12, forward.split(' ').size)
    }
}
