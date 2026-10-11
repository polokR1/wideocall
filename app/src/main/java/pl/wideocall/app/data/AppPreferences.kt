package pl.wideocall.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class Contact(
    val id: String,
    val name: String,
)

data class FriendRequest(
    val requestId: String,
    val fromId: String,
    val displayName: String,
)

data class SentFriendRequest(
    val requestId: String,
    val toId: String,
    val createdAt: Long,
)

enum class VideoQualityMode {
    AUTO,
    DATA_SAVER,
    HIGH;

    companion object {
        fun fromStored(value: String?): VideoQualityMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("wideocall", Context.MODE_PRIVATE)

    init {
        // Privacy migration: drop call history created by earlier versions, never recreate it.
        if (preferences.contains(KEY_RECENT_CALLERS)) preferences.edit { remove(KEY_RECENT_CALLERS) }
        if (!preferences.getBoolean(KEY_PUSH_ONLY_MIGRATION, false)) {
            preferences.edit()
                .putBoolean(KEY_WATCH_ENABLED, false)
                .putBoolean(KEY_PUSH_ONLY_MIGRATION, true)
                .apply()
        }
    }

    val myId: String
        get() {
            val existing = preferences.getString(KEY_MY_ID, null)
            if (!existing.isNullOrBlank()) return existing
            return generateId().also { preferences.edit().putString(KEY_MY_ID, it).apply() }
        }

    var displayName: String
        get() = preferences.getString(KEY_DISPLAY_NAME, "Mój telefon") ?: "Mój telefon"
        set(value) = preferences.edit().putString(KEY_DISPLAY_NAME, value.trim()).apply()

    var watchEnabled: Boolean
        get() = preferences.getBoolean(KEY_WATCH_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_WATCH_ENABLED, value).apply()

    var videoQualityMode: VideoQualityMode
        get() = VideoQualityMode.fromStored(preferences.getString(KEY_VIDEO_QUALITY, null))
        set(value) = preferences.edit { putString(KEY_VIDEO_QUALITY, value.name) }

    var startWithFrontCamera: Boolean
        get() = preferences.getBoolean(KEY_FRONT_CAMERA, true)
        set(value) = preferences.edit { putBoolean(KEY_FRONT_CAMERA, value) }

    fun saveBackgroundIssue(message: String) {
        preferences.edit().putString(KEY_BACKGROUND_ISSUE, message.take(240)).apply()
    }

    fun consumeBackgroundIssue(): String? {
        val issue = preferences.getString(KEY_BACKGROUND_ISSUE, null)
        if (issue != null) preferences.edit().remove(KEY_BACKGROUND_ISSUE).apply()
        return issue
    }

    fun contacts(): List<Contact> {
        val raw = preferences.getString(KEY_CONTACTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(Contact(item.getString("id"), item.getString("name")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveContact(contact: Contact) {
        val normalized = contact.copy(
            id = normalizeId(contact.id),
            name = contact.name.trim().ifBlank { normalizeId(contact.id) },
        )
        val updated = contacts()
            .filterNot { it.id == normalized.id }
            .plus(normalized)
            .sortedBy { it.name.lowercase() }
        saveContacts(updated)
    }

    fun renameContact(id: String, rawName: String): Result<Contact> = runCatching {
        val normalizedId = normalizeId(id)
        val name = ContactNamePolicy.validate(rawName).getOrThrow()
        val existing = contacts()
        require(existing.any { it.id == normalizedId }) { "Kontakt nie jest już zapisany." }
        val renamed = Contact(normalizedId, name)
        saveContacts(existing.map { if (it.id == normalizedId) renamed else it }.sortedBy { it.name.lowercase() }, synchronous = true)
        renamed
    }

    fun removeContact(id: String) {
        saveContacts(contacts().filterNot { it.id == normalizeId(id) })
    }

    fun friendRequests(): List<FriendRequest> {
        val raw = preferences.getString(KEY_FRIEND_REQUESTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_FRIEND_REQUESTS)) {
                    val item = array.getJSONObject(index)
                    val requestId = item.optString("requestId")
                    val fromId = normalizeId(item.optString("fromId"))
                    if (requestId.length !in 8..128 || fromId.length != 9) continue
                    add(
                        FriendRequest(
                            requestId = requestId,
                            fromId = fromId,
                            displayName = item.optString("displayName").trim().take(80).ifBlank { fromId },
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveFriendRequest(request: FriendRequest) {
        val normalized = request.copy(
            fromId = normalizeId(request.fromId),
            displayName = request.displayName.trim().take(80).ifBlank { normalizeId(request.fromId) },
        )
        if (normalized.requestId.length !in 8..128 || normalized.fromId.length != 9) return
        saveFriendRequests(
            friendRequests()
                .filterNot { it.requestId == normalized.requestId || it.fromId == normalized.fromId }
                .plus(normalized)
                .takeLast(MAX_FRIEND_REQUESTS)
        )
    }

    fun removeFriendRequest(requestId: String) {
        saveFriendRequests(friendRequests().filterNot { it.requestId == requestId })
    }

    fun sentFriendRequests(): List<SentFriendRequest> {
        val raw = preferences.getString(KEY_SENT_FRIEND_REQUESTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_FRIEND_REQUESTS)) {
                    val item = array.getJSONObject(index)
                    val requestId = item.optString("requestId")
                    val toId = normalizeId(item.optString("toId"))
                    val createdAt = item.optLong("createdAt")
                    if (requestId.length !in 8..128 || toId.length != 9 || createdAt <= 0L) continue
                    add(SentFriendRequest(requestId, toId, createdAt))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveSentFriendRequest(request: SentFriendRequest) {
        val normalized = request.copy(toId = normalizeId(request.toId))
        if (normalized.requestId.length !in 8..128 || normalized.toId.length != 9 || normalized.createdAt <= 0L) return
        saveSentFriendRequests(
            sentFriendRequests()
                .filterNot { it.requestId == normalized.requestId || it.toId == normalized.toId }
                .plus(normalized)
                .takeLast(MAX_FRIEND_REQUESTS)
        )
    }

    fun removeSentFriendRequest(requestId: String) {
        saveSentFriendRequests(sentFriendRequests().filterNot { it.requestId == requestId })
    }

    private fun saveContacts(contacts: List<Contact>, synchronous: Boolean = false) {
        val array = JSONArray()
        contacts.forEach { contact ->
            array.put(JSONObject().put("id", contact.id).put("name", contact.name))
        }
        val edit = preferences.edit().putString(KEY_CONTACTS, array.toString())
        if (synchronous) check(edit.commit()) { "Nie udało się zapisać nazwy na telefonie." } else edit.apply()
    }

    private fun saveFriendRequests(requests: List<FriendRequest>) {
        val array = JSONArray()
        requests.forEach { request ->
            array.put(
                JSONObject()
                    .put("requestId", request.requestId)
                    .put("fromId", request.fromId)
                    .put("displayName", request.displayName)
            )
        }
        preferences.edit().putString(KEY_FRIEND_REQUESTS, array.toString()).apply()
    }

    private fun saveSentFriendRequests(requests: List<SentFriendRequest>) {
        val array = JSONArray()
        requests.forEach { request ->
            array.put(
                JSONObject()
                    .put("requestId", request.requestId)
                    .put("toId", request.toId)
                    .put("createdAt", request.createdAt)
            )
        }
        preferences.edit { putString(KEY_SENT_FRIEND_REQUESTS, array.toString()) }
    }

    companion object {
        private const val KEY_MY_ID = "my_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_FRIEND_REQUESTS = "friend_requests_v1"
        private const val KEY_SENT_FRIEND_REQUESTS = "sent_friend_requests_v1"
        private const val KEY_RECENT_CALLERS = "recent_callers_v1"
        private const val KEY_BACKGROUND_ISSUE = "background_issue_v1"
        private const val KEY_WATCH_ENABLED = "watch_enabled"
        private const val KEY_VIDEO_QUALITY = "video_quality_v1"
        private const val KEY_FRONT_CAMERA = "front_camera_v1"
        private const val KEY_PUSH_ONLY_MIGRATION = "push_only_migration_v1"
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()
        private const val MAX_FRIEND_REQUESTS = 20

        fun normalizeId(value: String): String = value
            .uppercase()
            .filter { it in ALPHABET }
            .chunked(4)
            .take(2)
            .joinToString("-")

        private fun generateId(): String = List(8) { ALPHABET[random.nextInt(ALPHABET.length)] }
            .joinToString("")
            .chunked(4)
            .joinToString("-")
    }
}
