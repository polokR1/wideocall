package pl.wideocall.app.signaling

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import pl.wideocall.app.BuildConfig
import pl.wideocall.app.notifications.CloudflarePushRegistry
import pl.wideocall.app.security.CloudflareDeviceAuth
import pl.wideocall.app.security.IdentityKeyManager
import java.util.concurrent.TimeUnit

data class ContactPresence(
    val online: Boolean = false,
    val busy: Boolean = false,
    val pushReady: Boolean = false,
    val watchReady: Boolean = false,
    val lastSeen: Long = 0L,
)

/** Ephemeral Cloudflare WebSocket signaling; Firebase only wakes a closed app. */
class SignalingClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(message: SignalMessage)
        fun onPresenceChanged(userId: String, presence: ContactPresence)
        fun onWarning(message: String)
    }

    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var userId: String? = null
    private var ready = false
    private var intentionallyClosed = false
    private var connectionGeneration = 0

    fun connect(userId: String) {
        disconnect()
        this.userId = userId
        intentionallyClosed = false
        ready = false
        val generation = connectionGeneration
        val baseUrl = BuildConfig.SIGNALING_WEBSOCKET_URL
        if (baseUrl.isBlank()) return reportDisconnected("Brak konfiguracji szybkiej sygnalizacji")
        socket = httpClient.newWebSocket(Request.Builder().url("$baseUrl/$userId").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration || intentionallyClosed) return
                handlePacket(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation == connectionGeneration && !intentionallyClosed) {
                    ready = false
                    val detail = when (reason) {
                        "invalid_signal_signature" -> "serwer odrzucił podpis sygnału"
                        "authentication_failed" -> "nie udało się potwierdzić tożsamości telefonu"
                        "delivery_failed" -> "serwer nie dostarczył sygnału"
                        "invalid_message", "invalid_signal" -> "serwer odrzucił nieprawidłowe dane"
                        else -> "kod $code${reason.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}"
                    }
                    reportDisconnected("Szybka sygnalizacja została rozłączona: $detail")
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (generation == connectionGeneration && !intentionallyClosed) {
                    ready = false
                    reportDisconnected("Nie można połączyć się z usługą rozmów", throwable)
                }
            }
        })
    }

    fun send(message: SignalMessage): Boolean {
        val currentUserId = userId ?: run {
            listener.onWarning("Sygnalizacja: brak identyfikatora aktywnej sesji.")
            return false
        }
        if (!ready) {
            listener.onWarning("Sygnalizacja: WebSocket nie jest jeszcze gotowy.")
            return false
        }
        if (message.from != currentUserId || message.to.isNullOrBlank()) {
            listener.onWarning("Sygnalizacja: odrzucono nieprawidłowego nadawcę lub odbiorcę.")
            return false
        }
        val signed = runCatching {
            val publicKey = IdentityKeyManager.publicKeyBase64()
            message.copy(identityKey = publicKey, signature = IdentityKeyManager.sign(message.signingPayload(publicKey)))
        }.getOrElse {
            reportDisconnected("Nie udało się podpisać danych połączenia", it)
            return false
        }
        val sent = socket?.send(JSONObject().put("type", "signal").put("signal", JSONObject(signed.toJson())).toString()) == true
        if (!sent) listener.onWarning("Sygnalizacja: nie udało się zapisać danych do WebSocketu.")
        return sent
    }

    /** Availability is learned from call outcome, without a database round-trip. */
    fun observeContacts(ids: Collection<String>) = Unit
    fun setBusy(busy: Boolean) = Unit

    fun disconnect() {
        connectionGeneration += 1
        intentionallyClosed = true
        ready = false
        socket?.close(1000, "client_closed")
        socket = null
        userId = null
    }

    fun close() {
        disconnect()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun handlePacket(webSocket: WebSocket, raw: String) {
        val packet = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            listener.onWarning("Sygnalizacja: serwer zwrócił uszkodzoną wiadomość.")
            webSocket.close(1008, "invalid_packet")
            return
        }
        when (packet.optString("type")) {
            "challenge" -> {
                val id = userId ?: return
                val nonce = packet.optString("nonce")
                if (nonce.isBlank()) return reportDisconnected("Nieprawidłowe uwierzytelnienie usługi rozmów")
                val (identityKey, signature) = CloudflareDeviceAuth.challengeSignature(id, nonce)
                webSocket.send(JSONObject().put("type", "authenticate").put("userId", id).put("identityKey", identityKey).put("signature", signature).toString())
            }
            "ready" -> {
                ready = true
                listener.onConnected()
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        CloudflarePushRegistry.register(context, token) { error ->
                            if (error != null) listener.onWarning(error)
                        }
                    }
                    .addOnFailureListener { listener.onWarning("Powiadomienia: nie udało się pobrać tokenu FCM (${it.javaClass.simpleName}).") }
            }
            "signal" -> packet.optJSONObject("signal")?.let { signal ->
                runCatching { SignalMessage.fromJson(signal.toString()) }
                    .onSuccess(listener::onMessage)
                    .onFailure { listener.onWarning("Sygnalizacja: odebrano nieprawidłowy format sygnału.") }
            }
            "error" -> listener.onWarning("Sygnalizacja Cloudflare: ${packet.optString("code", "nieznany błąd")}.")
        }
    }

    private fun reportDisconnected(message: String, error: Throwable? = null) {
        if (intentionallyClosed) return
        val detail = error?.localizedMessage?.takeIf(String::isNotBlank)
        listener.onDisconnected(if (detail == null) message else "$message: $detail")
    }
}
