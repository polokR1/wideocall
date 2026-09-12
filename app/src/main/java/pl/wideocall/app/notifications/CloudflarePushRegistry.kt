package pl.wideocall.app.notifications

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.wideocall.app.BuildConfig
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.security.CloudflareDeviceAuth
import java.util.concurrent.TimeUnit

/** Registers only an opaque FCM delivery token with the Cloudflare signal hub. */
object CloudflarePushRegistry {
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun register(context: Context, token: String) {
        if (token.length < 20 || BuildConfig.SIGNALING_HTTP_URL.isBlank()) return
        val body = CloudflareDeviceAuth.request(
            userId = AppPreferences(context).myId,
            operation = "device",
            token = token,
        ).toString().toRequestBody(json)
        client.newCall(
            Request.Builder()
                .url("${BuildConfig.SIGNALING_HTTP_URL}/device")
                .post(body)
                .build(),
        ).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
        })
    }
}
