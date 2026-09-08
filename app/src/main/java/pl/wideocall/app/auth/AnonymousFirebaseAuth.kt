package pl.wideocall.app.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth

object AnonymousFirebaseAuth {
    private val lock = Any()
    private var pendingSignIn: Task<AuthResult>? = null

    fun ensureSignedIn(
        auth: FirebaseAuth,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        auth.currentUser?.uid?.let {
            onSuccess(it)
            return
        }

        val task = synchronized(lock) {
            auth.currentUser?.uid?.let {
                onSuccess(it)
                return
            }
            pendingSignIn ?: auth.signInAnonymously().also { created ->
                pendingSignIn = created
                created.addOnCompleteListener {
                    synchronized(lock) {
                        if (pendingSignIn === created) pendingSignIn = null
                    }
                }
            }
        }

        task.addOnSuccessListener { result ->
            val uid = result.user?.uid ?: auth.currentUser?.uid
            if (uid == null) {
                onFailure(IllegalStateException("Firebase nie zwrócił identyfikatora użytkownika"))
            } else {
                onSuccess(uid)
            }
        }.addOnFailureListener(onFailure)
    }
}
