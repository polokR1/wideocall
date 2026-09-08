package pl.wideocall.app.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import pl.wideocall.app.data.AppPreferences

object PresenceVisibility {
    fun update(context: Context, online: Boolean) {
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return
        val databaseUrl = app.options.databaseUrl ?: return
        val user = FirebaseAuth.getInstance(app).currentUser ?: return
        val userId = AppPreferences(context).myId
        val reference = FirebaseDatabase.getInstance(databaseUrl).reference.child("users").child(userId)
        reference.get().addOnSuccessListener { snapshot ->
            if (snapshot.child("authUid").getValue(String::class.java) == user.uid) {
                reference.updateChildren(
                    mapOf(
                        "online" to online,
                        "lastSeen" to ServerValue.TIMESTAMP,
                    )
                )
            }
        }
    }
}
