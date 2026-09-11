package pl.wideocall.app.call

import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import pl.wideocall.app.BuildConfig
import pl.wideocall.app.auth.FirebaseBackendAuth
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

data class TurnIceServer(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
)

data class TurnConfiguration(
    val iceServers: List<TurnIceServer>,
    val expiresAt: Long,
)

/** Keeps short-lived TURN credentials in memory only. Long-term TURN secrets stay in Cloudflare. */
object TurnCredentialProvider {
    private const val TAG = "TurnCredentials"
    private const val CACHE_MARGIN_MS = 5 * 60 * 1000L
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "turn-credentials").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestLock = Any()
    private val waitingCallbacks = mutableListOf<(TurnConfiguration?) -> Unit>()

    @Volatile
    private var cached: TurnConfiguration? = null
    private var requestInFlight = false

    fun prefetch() {
        get { }
    }

    fun get(onResult: (TurnConfiguration?) -> Unit) {
        cached?.takeIf { it.expiresAt - CACHE_MARGIN_MS > System.currentTimeMillis() }?.let {
            deliver(onResult, it)
            return
        }
        if (BuildConfig.TURN_CREDENTIALS_URL.isBlank()) {
            deliver(onResult, null)
            return
        }
        synchronized(requestLock) {
            cached?.takeIf { it.expiresAt - CACHE_MARGIN_MS > System.currentTimeMillis() }?.let {
                deliver(onResult, it)
                return
            }
            waitingCallbacks += onResult
            if (requestInFlight) return
            requestInFlight = true
        }

        FirebaseBackendAuth.get { tokens ->
            if (tokens == null) {
                finish(null)
                return@get
            }
            executor.execute {
                finish(request(tokens.idToken, tokens.appCheckToken))
            }
        }
    }

    private fun finish(configuration: TurnConfiguration?) {
        val callbacks = synchronized(requestLock) {
            if (configuration != null) cached = configuration
            requestInFlight = false
            waitingCallbacks.toList().also { waitingCallbacks.clear() }
        }
        callbacks.forEach { deliver(it, configuration) }
    }

    private fun deliver(callback: (TurnConfiguration?) -> Unit, configuration: TurnConfiguration?) {
        if (Looper.myLooper() == Looper.getMainLooper()) callback(configuration)
        else mainHandler.post { callback(configuration) }
    }

    private fun request(idToken: String, appCheckToken: String?): TurnConfiguration? {
        val connection = runCatching {
            (URL(BuildConfig.TURN_CREDENTIALS_URL).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 10_000
                setFixedLengthStreamingMode(0)
                doOutput = true
                setRequestProperty("Authorization", "Bearer $idToken")
                appCheckToken?.let { setRequestProperty("X-Firebase-AppCheck", it) }
                setRequestProperty("Accept", "application/json")
                outputStream.use { }
            }
        }.getOrElse {
            Log.w(TAG, "TURN gateway unavailable")
            return null
        }

        return try {
            if (connection.responseCode !in 200..299) {
                connection.errorStream?.use(::readLimited)
                Log.w(TAG, "TURN gateway returned HTTP ${connection.responseCode}")
                null
            } else {
                val bytes = connection.inputStream.use(::readLimited) ?: return null
                parse(String(bytes, Charsets.UTF_8))
            }
        } catch (_: Exception) {
            Log.w(TAG, "TURN credentials request failed")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(raw: String): TurnConfiguration? = runCatching {
        val json = JSONObject(raw)
        val expiresAt = json.getLong("expiresAt")
        if (expiresAt <= System.currentTimeMillis() + CACHE_MARGIN_MS) return null
        val serversJson = json.getJSONArray("iceServers")
        val servers = buildList {
            for (index in 0 until minOf(serversJson.length(), 8)) {
                val server = serversJson.getJSONObject(index)
                val rawUrls = server.getJSONArray("urls")
                val urls = buildList {
                    for (urlIndex in 0 until minOf(rawUrls.length(), 8)) {
                        val url = rawUrls.optString(urlIndex)
                        if (isAllowedCloudflareUrl(url)) add(url)
                    }
                }
                if (urls.isEmpty()) continue
                val usesTurn = urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
                val username = server.optString("username")
                val credential = server.optString("credential")
                if (usesTurn && (username.length !in 20..512 || credential.length !in 20..512)) continue
                add(TurnIceServer(urls, username, credential))
            }
        }
        if (servers.none { it.username.isNotBlank() }) return null
        TurnConfiguration(servers, expiresAt)
    }.getOrNull()

    private fun isAllowedCloudflareUrl(url: String): Boolean =
        url.matches(Regex("^(?:stun:stun\\.cloudflare\\.com(?::\\d+)?|turns?:turn\\.cloudflare\\.com(?::\\d+)?(?:\\?transport=(?:udp|tcp))?)$"))

    private fun readLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
