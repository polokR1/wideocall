package pl.wideocall.app.signaling

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class SignalMessage(
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val callId: String? = null,
    val sessionKind: String = SESSION_VIDEO,
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val reason: String? = null,
    val identityKey: String? = null,
    val signature: String? = null,
    val localOnly: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type)
        from?.let { put("from", it) }
        to?.let { put("to", it) }
        callId?.let { put("callId", it) }
        put("sessionKind", sessionKind)
        sdp?.let { put("sdp", it) }
        sdpType?.let { put("sdpType", it) }
        candidate?.let { put("candidate", it) }
        sdpMid?.let { put("sdpMid", it) }
        sdpMLineIndex?.let { put("sdpMLineIndex", it) }
        reason?.let { put("reason", it) }
        identityKey?.let { put("identityKey", it) }
        signature?.let { put("signature", it) }
    }.toString()

    fun signingPayload(publicKey: String): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            listOf(
                "wideocall-signaling-v1",
                type,
                from.orEmpty(),
                to.orEmpty(),
                callId.orEmpty(),
                sessionKind,
                sdp.orEmpty(),
                sdpType.orEmpty(),
                candidate.orEmpty(),
                sdpMid.orEmpty(),
                sdpMLineIndex?.toString().orEmpty(),
                reason.orEmpty(),
                publicKey,
            ).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        return output.toByteArray()
    }

    companion object {
        const val SESSION_VIDEO = "video"
        const val SESSION_AUDIO = "audio"
        const val SESSION_CHAT = "chat"
        const val SESSION_FILE = "file"
        const val SESSION_CONTACT = "contact"

        fun fromJson(raw: String): SignalMessage {
            val json = JSONObject(raw)
            return SignalMessage(
                type = json.getString("type"),
                from = json.optString("from").ifBlank { null },
                to = json.optString("to").ifBlank { null },
                callId = json.optString("callId").ifBlank { null },
                sessionKind = json.optString("sessionKind", SESSION_VIDEO).ifBlank { SESSION_VIDEO },
                sdp = json.optString("sdp").ifBlank { null },
                sdpType = json.optString("sdpType").ifBlank { null },
                candidate = json.optString("candidate").ifBlank { null },
                sdpMid = json.optString("sdpMid").ifBlank { null },
                sdpMLineIndex = if (json.has("sdpMLineIndex")) json.optInt("sdpMLineIndex") else null,
                reason = json.optString("reason").ifBlank { null },
                identityKey = json.optString("identityKey").ifBlank { null },
                signature = json.optString("signature").ifBlank { null },
            )
        }
    }
}
