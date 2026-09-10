package pl.wideocall.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class Contact(
    val id: String,
    val name: String,
)

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("wideocall", Context.MODE_PRIVATE)

    init {
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

    fun removeContact(id: String) {
        saveContacts(contacts().filterNot { it.id == normalizeId(id) })
    }

    private fun saveContacts(contacts: List<Contact>) {
        val array = JSONArray()
        contacts.forEach { contact ->
            array.put(JSONObject().put("id", contact.id).put("name", contact.name))
        }
        preferences.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    companion object {
        private const val KEY_MY_ID = "my_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_WATCH_ENABLED = "watch_enabled"
        private const val KEY_PUSH_ONLY_MIGRATION = "push_only_migration_v1"
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

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
