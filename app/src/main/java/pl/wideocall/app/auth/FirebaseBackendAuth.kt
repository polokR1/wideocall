package pl.wideocall.app.auth

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth

data class FirebaseBackendTokens(
    val idToken: String,
    val appCheckToken: String?,
)

/** Acquires the tokens used by the non-Firebase Cloudflare gateway without persisting them. */
object FirebaseBackendAuth {
    private const val TAG = "FirebaseBackendAuth"

    fun get(onResult: (FirebaseBackendTokens?) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onResult(null)
            return
        }
        val idTokenTask = user.getIdToken(false)
        val appCheckTask = FirebaseAppCheck.getInstance().getAppCheckToken(false)
        Tasks.whenAllComplete(idTokenTask, appCheckTask).addOnCompleteListener {
            val idToken = idTokenTask.takeIf { task -> task.isSuccessful }?.result?.token
            if (idToken.isNullOrBlank()) {
                Log.w(TAG, "Firebase ID token unavailable")
                onResult(null)
                return@addOnCompleteListener
            }
            val appCheckToken = appCheckTask
                .takeIf { task -> task.isSuccessful }
                ?.result
                ?.token
                ?.takeIf(String::isNotBlank)
            if (appCheckToken == null) {
                Log.w(TAG, "App Check token unavailable; backend request is compatible until enforcement")
            }
            onResult(FirebaseBackendTokens(idToken, appCheckToken))
        }
    }
}
