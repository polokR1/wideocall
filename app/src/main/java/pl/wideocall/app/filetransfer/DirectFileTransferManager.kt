package pl.wideocall.app.filetransfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

enum class FileTransferDirection { SEND, RECEIVE }

enum class FileTransferStatus {
    IDLE,
    WAITING_FOR_PEER,
    TRANSFERRING,
    VERIFYING,
    COMPLETE,
    CANCELLED,
    FAILED,
}

data class FileTransferState(
    val direction: FileTransferDirection,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val status: FileTransferStatus = FileTransferStatus.WAITING_FOR_PEER,
    val error: String? = null,
    val receivedFilePath: String? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/**
 * Streams one file directly over the reliable WebRTC DataChannel.
 * Only control metadata is JSON; file bytes are never uploaded to Firebase or Cloudflare.
 */
class DirectFileTransferManager(
    context: Context,
    private val scope: CoroutineScope,
    private val sendText: (String) -> Boolean,
    private val sendBinary: (ByteArray) -> Boolean,
    private val bufferedAmount: () -> Long,
    private val onState: (FileTransferState) -> Unit,
) {
    private val appContext = context.applicationContext
    private var state: FileTransferState? = null
    private var outgoingUri: Uri? = null
    private var fileId: String? = null
    private var sendJob: Job? = null
    private var receiveStream: OutputStream? = null
    private var receiveDigest: MessageDigest? = null
    private var receivedBytes = 0L
    private var receivedTempFile: File? = null

    fun prepareOutgoing(uri: Uri): Result<FileTransferState> = runCatching {
        reset(deleteCompleted = true)
        val resolver = appContext.contentResolver
        var name = "plik"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifBlank { "plik" }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size < 0L) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(isSupportedSize(size)) { "Plik musi mieć od 1 B do ${MAX_FILE_BYTES / (1024 * 1024)} MB." }
        val prepared = FileTransferState(
            direction = FileTransferDirection.SEND,
            fileName = sanitizeFileName(name),
            mimeType = resolver.getType(uri)?.take(120) ?: "application/octet-stream",
            totalBytes = size,
        )
        outgoingUri = uri
        fileId = UUID.randomUUID().toString()
        update(prepared)
        prepared
    }

    fun onChannelReady() {
        val current = state ?: return
        if (current.direction != FileTransferDirection.SEND || current.status != FileTransferStatus.WAITING_FOR_PEER) return
        val id = fileId ?: return fail("Brak identyfikatora transferu.")
        val metadata = JSONObject()
            .put("kind", PROTOCOL)
            .put("action", "meta")
            .put("id", id)
            .put("name", current.fileName)
            .put("mime", current.mimeType)
            .put("size", current.totalBytes)
            .toString()
        if (!sendText(metadata)) fail("Nie udało się wysłać informacji o pliku.")
    }

    fun onText(raw: String): Boolean {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (json.optString("kind") != PROTOCOL) return false
        when (json.optString("action")) {
            "meta" -> receiveMetadata(json)
            "accept" -> startSending(json.optString("id"))
            "complete" -> completeReceive(json.optString("id"), json.optString("sha256"))
            "verified" -> completeSend(json.optString("id"))
            "cancel" -> cancelLocal("Druga osoba anulowała transfer.", notifyPeer = false)
            "error" -> fail(json.optString("message").take(160).ifBlank { "Błąd po drugiej stronie." }, notifyPeer = false)
        }
        return true
    }

    @Synchronized
    fun onBinary(bytes: ByteArray) {
        val current = state ?: return
        if (current.direction != FileTransferDirection.RECEIVE || current.status != FileTransferStatus.TRANSFERRING) return
        if (receivedBytes + bytes.size > current.totalBytes) {
            fail("Odebrano więcej danych niż zadeklarowano.")
            return
        }
        runCatching {
            receiveStream?.write(bytes) ?: error("Strumień odbioru nie jest otwarty")
            receiveDigest?.update(bytes)
            receivedBytes += bytes.size
            update(current.copy(transferredBytes = receivedBytes))
        }.onFailure { fail("Nie udało się zapisać odbieranego pliku: ${it.javaClass.simpleName}.") }
    }

    fun cancel() = cancelLocal("Transfer anulowany.", notifyPeer = true)

    fun copyReceivedTo(destination: Uri, onComplete: (Result<Unit>) -> Unit) {
        val current = state
        val source = current?.receivedFilePath?.let(::File)
        if (current?.status != FileTransferStatus.COMPLETE || source?.isFile != true) {
            onComplete(Result.failure(IllegalStateException("Brak gotowego pliku do zapisania.")))
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                appContext.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Nie można otworzyć miejsca docelowego")
                Unit
            }
            onComplete(result)
        }
    }

    fun reset(deleteCompleted: Boolean = false) {
        val keepCompleted = !deleteCompleted && state?.status == FileTransferStatus.COMPLETE
        sendJob?.cancel()
        sendJob = null
        closeReceiver()
        if (!keepCompleted) {
            receivedTempFile?.delete()
            receivedTempFile = null
            state = null
        }
        receivedBytes = 0L
        outgoingUri = null
        fileId = null
    }

    private fun receiveMetadata(json: JSONObject) {
        if (state != null) return fail("Otrzymano nieoczekiwany drugi plik.")
        val id = json.optString("id")
        val name = sanitizeFileName(json.optString("name"))
        val mime = json.optString("mime").take(120).ifBlank { "application/octet-stream" }
        val size = json.optLong("size", -1L)
        if (id.length !in 8..128 || size !in 1..MAX_FILE_BYTES) return fail("Nieprawidłowe dane pliku.")
        runCatching {
            val directory = File(appContext.cacheDir, "received-files").apply { mkdirs() }
            val temp = File.createTempFile("wideocall-", ".part", directory)
            receivedTempFile = temp
            receiveStream = BufferedOutputStream(FileOutputStream(temp))
            receiveDigest = MessageDigest.getInstance("SHA-256")
            receivedBytes = 0L
            fileId = id
            update(
                FileTransferState(
                    direction = FileTransferDirection.RECEIVE,
                    fileName = name,
                    mimeType = mime,
                    totalBytes = size,
                    status = FileTransferStatus.TRANSFERRING,
                )
            )
            if (!sendControl("accept")) error("Nie udało się potwierdzić odbioru")
        }.onFailure { fail("Nie można przygotować miejsca na plik: ${it.javaClass.simpleName}.") }
    }

    private fun startSending(id: String) {
        val current = state ?: return
        val uri = outgoingUri ?: return fail("Utracono dostęp do wybranego pliku.")
        if (current.direction != FileTransferDirection.SEND || id != fileId || sendJob?.isActive == true) return
        update(current.copy(status = FileTransferStatus.TRANSFERRING))
        sendJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                var sent = 0L
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        var backpressureWait = 0L
                        while (isActive && bufferedAmount() > MAX_BUFFERED_BYTES) {
                            delay(BACKPRESSURE_DELAY_MS)
                            backpressureWait += BACKPRESSURE_DELAY_MS
                            if (backpressureWait >= MAX_BACKPRESSURE_WAIT_MS) error("Kanał danych nie odbiera kolejnych fragmentów")
                        }
                        val chunk = if (count == buffer.size) buffer.copyOf() else buffer.copyOf(count)
                        if (!sendBinary(chunk)) error("Kanał danych został zamknięty")
                        digest.update(chunk)
                        sent += count
                        update(current.copy(status = FileTransferStatus.TRANSFERRING, transferredBytes = sent))
                    }
                } ?: error("Nie można odczytać wybranego pliku")
                if (sent != current.totalBytes) error("Rozmiar pliku zmienił się w trakcie wysyłania")
                val complete = JSONObject()
                    .put("kind", PROTOCOL)
                    .put("action", "complete")
                    .put("id", fileId)
                    .put("sha256", digest.digest().toHex())
                    .toString()
                if (!sendText(complete)) error("Nie udało się zakończyć transferu")
                update(current.copy(status = FileTransferStatus.VERIFYING, transferredBytes = sent))
            }.onFailure { fail("Wysyłanie nie powiodło się: ${it.message ?: it.javaClass.simpleName}.") }
        }
    }

    @Synchronized
    private fun completeReceive(id: String, expectedHash: String) {
        val current = state ?: return
        if (current.direction != FileTransferDirection.RECEIVE || id != fileId) return
        update(current.copy(status = FileTransferStatus.VERIFYING, transferredBytes = receivedBytes))
        runCatching {
            receiveStream?.flush()
            closeReceiver()
            require(receivedBytes == current.totalBytes) { "Plik jest niekompletny" }
            val actualHash = receiveDigest?.digest()?.toHex() ?: error("Brak sumy kontrolnej")
            require(expectedHash.matches(Regex("[0-9a-f]{64}")) && actualHash == expectedHash) { "Suma kontrolna pliku jest niezgodna" }
            val temp = receivedTempFile ?: error("Brak pliku tymczasowego")
            if (!sendControl("verified")) error("Nie udało się potwierdzić integralności pliku")
            update(
                current.copy(
                    status = FileTransferStatus.COMPLETE,
                    transferredBytes = receivedBytes,
                    receivedFilePath = temp.absolutePath,
                )
            )
        }.onFailure { fail("Weryfikacja pliku nie powiodła się: ${it.message ?: it.javaClass.simpleName}.") }
    }

    private fun completeSend(id: String) {
        val current = state ?: return
        if (current.direction != FileTransferDirection.SEND || current.status != FileTransferStatus.VERIFYING || id != fileId) return
        update(current.copy(status = FileTransferStatus.COMPLETE, transferredBytes = current.totalBytes))
    }

    private fun sendControl(action: String): Boolean = sendText(
        JSONObject().put("kind", PROTOCOL).put("action", action).put("id", fileId).toString()
    )

    private fun cancelLocal(message: String, notifyPeer: Boolean) {
        if (notifyPeer) sendControl("cancel")
        sendJob?.cancel()
        closeReceiver()
        receivedTempFile?.takeIf { state?.status != FileTransferStatus.COMPLETE }?.delete()
        state?.let { update(it.copy(status = FileTransferStatus.CANCELLED, error = message)) }
    }

    private fun fail(message: String, notifyPeer: Boolean = true) {
        if (notifyPeer) {
            sendText(JSONObject().put("kind", PROTOCOL).put("action", "error").put("id", fileId).put("message", message.take(160)).toString())
        }
        sendJob?.cancel()
        closeReceiver()
        receivedTempFile?.takeIf { state?.status != FileTransferStatus.COMPLETE }?.delete()
        val current = state ?: FileTransferState(FileTransferDirection.RECEIVE, "plik", "application/octet-stream", 0L)
        update(current.copy(status = FileTransferStatus.FAILED, error = message))
    }

    private fun closeReceiver() {
        runCatching { receiveStream?.close() }
        receiveStream = null
    }

    private fun update(value: FileTransferState) {
        state = value
        onState(value)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private const val CHUNK_BYTES = 16 * 1024
        private const val MAX_BUFFERED_BYTES = 512L * 1024
        private const val BACKPRESSURE_DELAY_MS = 20L
        private const val MAX_BACKPRESSURE_WAIT_MS = 30_000L
        private const val PROTOCOL = "wideocall-file-v1"

        internal fun isSupportedSize(bytes: Long): Boolean = bytes in 1..MAX_FILE_BYTES

        internal fun sanitizeFileName(raw: String): String = raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.', '_')
            .take(120)
            .ifBlank { "plik" }
    }
}
