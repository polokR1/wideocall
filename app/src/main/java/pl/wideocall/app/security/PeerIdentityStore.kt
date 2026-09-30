package pl.wideocall.app.security

import android.content.Context
import org.json.JSONObject

enum class PeerIdentityStatus {
    FIRST_SEEN,
    TRUSTED,
    CHANGED,
}

data class PeerIdentityTrust(
    val status: PeerIdentityStatus,
    val safetyNumber: String?,
    val manuallyVerified: Boolean,
)

/** Trust-on-first-use pinning. Public identity keys are not secret, but key changes are blocked. */
class PeerIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("wideocall", Context.MODE_PRIVATE)

    @Synchronized
    fun checkAndPin(remoteId: String, publicKey: String): PeerIdentityTrust {
        val identities = readIdentities()
        val existing = identities.optJSONObject(remoteId)
        if (existing != null && existing.optString("key") != publicKey) {
            return PeerIdentityTrust(PeerIdentityStatus.CHANGED, null, false)
        }
        val firstSeen = existing == null
        if (firstSeen) {
            identities.put(
                remoteId,
                JSONObject()
                    .put("key", publicKey)
                    .put("verified", false),
            )
            preferences.edit().putString(KEY_IDENTITIES, identities.toString()).apply()
        }
        val verified = existing?.optBoolean("verified", false) == true
        val safetyNumber = runCatching { IdentityKeyManager.safetyNumber(publicKey) }.getOrNull()
        return PeerIdentityTrust(
            if (firstSeen) PeerIdentityStatus.FIRST_SEEN else PeerIdentityStatus.TRUSTED,
            safetyNumber,
            verified,
        )
    }

    @Synchronized
    fun markVerified(remoteId: String): Boolean {
        val identities = readIdentities()
        val existing = identities.optJSONObject(remoteId) ?: return false
        existing.put("verified", true)
        // Report storage failure rather than claiming a durable manual verification.
        return preferences.edit().putString(KEY_IDENTITIES, identities.toString()).commit()
    }

    private fun readIdentities(): JSONObject = runCatching {
        JSONObject(preferences.getString(KEY_IDENTITIES, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    companion object {
        private const val KEY_IDENTITIES = "peer_identities_v1"
    }
}
