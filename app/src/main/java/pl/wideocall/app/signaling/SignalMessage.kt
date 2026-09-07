package pl.wideocall.app.signaling

import org.json.JSONObject

data class SignalMessage(
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val callId: String? = null,
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val reason: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type)
        from?.let { put("from", it) }
        to?.let { put("to", it) }
        callId?.let { put("callId", it) }
        sdp?.let { put("sdp", it) }
        sdpType?.let { put("sdpType", it) }
        candidate?.let { put("candidate", it) }
        sdpMid?.let { put("sdpMid", it) }
        sdpMLineIndex?.let { put("sdpMLineIndex", it) }
        reason?.let { put("reason", it) }
    }.toString()

    companion object {
        fun fromJson(raw: String): SignalMessage {
            val json = JSONObject(raw)
            return SignalMessage(
                type = json.getString("type"),
                from = json.optString("from").ifBlank { null },
                to = json.optString("to").ifBlank { null },
                callId = json.optString("callId").ifBlank { null },
                sdp = json.optString("sdp").ifBlank { null },
                sdpType = json.optString("sdpType").ifBlank { null },
                candidate = json.optString("candidate").ifBlank { null },
                sdpMid = json.optString("sdpMid").ifBlank { null },
                sdpMLineIndex = if (json.has("sdpMLineIndex")) json.optInt("sdpMLineIndex") else null,
                reason = json.optString("reason").ifBlank { null },
            )
        }
    }
}
