package pl.wideocall.app.notifications

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import pl.wideocall.app.BuildConfig
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Sends only call identifiers to the trusted push gateway after Firebase accepted the offer. */
object CallWakeGateway {
    private const val TAG = "CallWakeGateway"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "call-wake-gateway").apply { isDaemon = true }
    }

    fun notifyIncomingCall(recipientId: String, callerId: String, callId: String, signalId: String) {
        if (!BuildConfig.FCM_ENABLED || BuildConfig.CALL_WAKE_URL.isBlank()) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val idToken = result.token ?: return@addOnSuccessListener
                executor.execute {
                    postNotification(idToken, recipientId, callerId, callId, signalId)
                }
            }
            .addOnFailureListener { error -> Log.w(TAG, "Firebase ID token unavailable", error) }
    }

    private fun postNotification(
        idToken: String,
        recipientId: String,
        callerId: String,
        callId: String,
        signalId: String,
    ) {
        val connection = runCatching {
            (URL(BuildConfig.CALL_WAKE_URL).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $idToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                val body = JSONObject()
                    .put("recipientId", recipientId)
                    .put("callerId", callerId)
                    .put("callId", callId)
                    .put("signalId", signalId)
                    .toString()
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }.getOrElse {
            Log.w(TAG, "Unable to contact call wake gateway", it)
            return
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.use { it.readBytes() }
            if (status !in 200..299) Log.w(TAG, "Call wake gateway returned HTTP $status")
        } catch (error: Exception) {
            Log.w(TAG, "Call wake gateway request failed", error)
        } finally {
            connection.disconnect()
        }
    }
}
