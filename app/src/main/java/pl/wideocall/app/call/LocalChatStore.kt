package pl.wideocall.app.call

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.wideocall.app.security.LocalChatCipher
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class StoredChatMessage(val peerId: String, val message: EphemeralChatMessage)

/** Small encrypted inbox/outbox in noBackupFilesDir. Access is serialized on IO, not on the audio/UI thread. */
class LocalChatStore(context: Context, fileName: String = "local_chat_v1.enc", private val clock: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val lock = Any()
    private data class Receipt(val peer: String, val id: String, val at: Long)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("wideocall_local_chat_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("wideocall_local_chat_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    suspend fun read(peerId: String): List<EphemeralChatMessage> = edit { entries, _ ->
        entries.filter { it.peerId == peerId }.map { it.message }
    }

    suspend fun add(peerId: String, message: EphemeralChatMessage): Boolean = edit { entries, receipts ->
        if (entries.any { it.peerId == peerId && it.message.id == message.id } ||
            (!message.mine && receipts.any { it.peer == peerId && it.id == message.id })) false
        else {
            check(entries.size < 1_000) { "Lokalna skrzynka jest pełna. Usuń oczekujące wiadomości przed dodaniem kolejnych." }
            // IDs only, never message contents. Keep expired deliveries deduplicated across restarts.
            if (!message.mine) {
                check(receipts.size < 20_000) { "Limit potwierdzeń odbioru. Spróbuj później." }
                receipts.add(Receipt(peerId, message.id, clock()))
            }
            entries.add(StoredChatMessage(peerId, message))
            true
        }
    }

    suspend fun opened(peerId: String, now: Long) = edit { entries, _ ->
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (entry.peerId == peerId && !entry.message.pending && entry.message.openedAt == null) {
                entries[index] = entry.copy(message = entry.message.copy(openedAt = now))
            }
        }
    }

    suspend fun acknowledged(peerId: String, id: String, now: Long?) = edit { entries, _ ->
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (entry.peerId == peerId && entry.message.id == id && entry.message.mine && entry.message.pending) {
                entries[index] = entry.copy(message = entry.message.copy(pending = false, openedAt = now))
            }
        }
    }

    suspend fun prune() = edit { _, _ -> Unit }

    suspend fun discardPending(peerId: String) = edit { entries, _ ->
        entries.removeAll { it.peerId == peerId && it.message.pending }
    }

    private suspend fun <T> edit(block: (MutableList<StoredChatMessage>, MutableList<Receipt>) -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val entries = mutableListOf<StoredChatMessage>()
            val receipts = mutableListOf<Receipt>()
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val bytes = file.openRead().use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (output.size() <= 4_000_000) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, 4_000_001 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(bytes.size <= 4_000_000) { "Lokalna skrzynka przekracza dopuszczalny rozmiar." }
                val document = JSONObject(String(LocalChatCipher.decrypt(bytes, key()), Charsets.UTF_8))
                val array = document.getJSONArray("messages")
                require(array.length() <= 1_000)
                for (index in 0 until array.length()) {
                    val row = array.getJSONObject(index)
                    entries.add(StoredChatMessage(row.getString("peer"), EphemeralChatMessage(
                        row.getString("id"), row.getString("text"), row.getBoolean("mine"), row.getLong("created"),
                        if (row.isNull("opened")) null else row.getLong("opened"), row.optBoolean("pending"),
                    )))
                }
                val seen = document.optJSONArray("receipts") ?: JSONArray()
                require(seen.length() <= 20_000)
                for (index in 0 until seen.length()) {
                    val row = seen.getJSONObject(index)
                    receipts.add(Receipt(row.getString("peer"), row.getString("id"), row.getLong("at")))
                }
            }
            val original = entries.toList()
            val originalReceipts = receipts.toList()
            entries.removeAll { !it.message.pending && EphemeralChatPolicy.isExpired(it.message.openedAt, clock()) }
            receipts.removeAll { clock() - it.at >= 7 * 24 * 60 * 60 * 1_000L }
            val result = block(entries, receipts)
            if (entries != original || receipts != originalReceipts) {
                val array = JSONArray()
                entries.forEach { entry ->
                    array.put(JSONObject().put("peer", entry.peerId).put("id", entry.message.id).put("text", entry.message.text)
                        .put("mine", entry.message.mine).put("created", entry.message.createdAt)
                        .put("opened", entry.message.openedAt ?: JSONObject.NULL).put("pending", entry.message.pending))
                }
                val seen = JSONArray()
                receipts.forEach { seen.put(JSONObject().put("peer", it.peer).put("id", it.id).put("at", it.at)) }
                val encrypted = LocalChatCipher.encrypt(JSONObject().put("messages", array).put("receipts", seen).toString().toByteArray(Charsets.UTF_8), key())
                require(encrypted.size <= 4_000_000) { "Lokalna skrzynka jest pełna." }
                val stream = file.startWrite()
                try { stream.write(encrypted); file.finishWrite(stream) }
                catch (error: Exception) { file.failWrite(stream); throw error }
            }
            result
        }
    }
}
