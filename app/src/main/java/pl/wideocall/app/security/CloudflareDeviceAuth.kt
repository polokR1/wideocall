package pl.wideocall.app.security

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Signs short-lived requests without putting a bearer credential in the APK. */
object CloudflareDeviceAuth {
    fun request(userId: String, operation: String, token: String? = null): JSONObject {
        val timestamp = System.currentTimeMillis()
        val identityKey = IdentityKeyManager.publicKeyBase64()
        return JSONObject()
            .put("userId", userId)
            .put("operation", operation)
            .put("timestamp", timestamp)
            .put("identityKey", identityKey)
            .put("signature", IdentityKeyManager.sign(payload(operation, userId, timestamp.toString(), identityKey)))
            .also { body -> token?.let { body.put("token", it) } }
    }

    fun challengeSignature(userId: String, nonce: String): Pair<String, String> {
        val identityKey = IdentityKeyManager.publicKeyBase64()
        return identityKey to IdentityKeyManager.sign(payload("socket", userId, nonce, identityKey))
    }

    private fun payload(operation: String, userId: String, nonceOrTimestamp: String, identityKey: String): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            listOf("wideocall-device-auth-v1", operation, userId, nonceOrTimestamp, identityKey).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        return output.toByteArray()
    }
}
