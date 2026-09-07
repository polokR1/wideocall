package pl.wideocall.app.notifications

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.messaging.FirebaseMessaging
import pl.wideocall.app.data.AppPreferences

object PushTokenRegistry {
    fun refresh(userId: String, uid: String, database: FirebaseDatabase) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> save(userId, uid, token, database) }
            .addOnFailureListener {
                database.reference.child("users").child(userId).child("pushReady").setValue(false)
            }
    }

    fun refreshAfterTokenChange(context: android.content.Context, token: String) {
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return
        val databaseUrl = app.options.databaseUrl ?: return
        val auth = FirebaseAuth.getInstance(app)
        val userId = AppPreferences(context).myId
        val current = auth.currentUser
        if (current != null) {
            save(userId, current.uid, token, FirebaseDatabase.getInstance(databaseUrl))
        } else {
            auth.signInAnonymously().addOnSuccessListener { result ->
                result.user?.uid?.let { uid ->
                    save(userId, uid, token, FirebaseDatabase.getInstance(databaseUrl))
                }
            }
        }
    }

    private fun save(userId: String, uid: String, token: String, database: FirebaseDatabase) {
        if (token.length < 20) return
        val device = mapOf(
            "authUid" to uid,
            "token" to token,
            "updatedAt" to ServerValue.TIMESTAMP,
        )
        database.reference.child("devices").child(userId).setValue(device)
            .addOnSuccessListener {
                database.reference.child("users").child(userId).child("pushReady").setValue(true)
            }
            .addOnFailureListener {
                database.reference.child("users").child(userId).child("pushReady").setValue(false)
            }
    }
}
