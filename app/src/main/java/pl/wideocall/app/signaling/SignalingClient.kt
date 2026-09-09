package pl.wideocall.app.signaling

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import pl.wideocall.app.auth.AnonymousFirebaseAuth
import pl.wideocall.app.notifications.PushTokenRegistry
import pl.wideocall.app.notifications.CallWakeGateway
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.BuildConfig

data class ContactPresence(
    val online: Boolean = false,
    val busy: Boolean = false,
    val pushReady: Boolean = false,
    val watchReady: Boolean = false,
    val lastSeen: Long = 0L,
)

class SignalingClient(
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(message: SignalMessage)
        fun onPresenceChanged(userId: String, presence: ContactPresence)
    }

    private var auth: FirebaseAuth? = null
    private var database: FirebaseDatabase? = null
    private var userId: String? = null
    private var userUid: String? = null
    private var presenceRef: DatabaseReference? = null
    private var connectionRef: DatabaseReference? = null
    private var connectionListener: ValueEventListener? = null
    private var signalsRef: DatabaseReference? = null
    private var signalsListener: ChildEventListener? = null
    private val contactListeners = mutableMapOf<String, Pair<DatabaseReference, ValueEventListener>>()
    private var ready = false
    private var intentionallyClosed = false
    private var connectionGeneration = 0

    fun connect(userId: String) {
        disconnect()
        intentionallyClosed = false
        val generation = connectionGeneration
        this.userId = userId

        val app = runCatching { FirebaseApp.getInstance() }.getOrElse {
            reportDisconnected("Brak konfiguracji Firebase w aplikacji")
            return
        }
        val databaseUrl = app.options.databaseUrl
        if (databaseUrl.isNullOrBlank()) {
            reportDisconnected("Realtime Database nie jest jeszcze włączona w projekcie Firebase")
            return
        }

        val firebaseAuth = FirebaseAuth.getInstance(app)
        auth = firebaseAuth
        database = FirebaseDatabase.getInstance(databaseUrl)

        AnonymousFirebaseAuth.ensureSignedIn(
            auth = firebaseAuth,
            onSuccess = success@{ uid ->
                if (generation != connectionGeneration || intentionallyClosed) return@success
                attachToDatabase(userId, uid, generation)
            },
            onFailure = { error ->
                if (generation == connectionGeneration && !intentionallyClosed) {
                    reportDisconnected(
                        "Nie udało się zalogować w Firebase. Włącz logowanie anonimowe w Authentication.",
                        error,
                    )
                }
            },
        )
    }

    fun send(message: SignalMessage): Boolean {
        val currentDatabase = database ?: return false
        val currentUserId = userId ?: return false
        val currentUid = userUid ?: return false
        val recipientId = message.to ?: return false
        if (!ready || message.from != currentUserId) return false

        if (message.type == "offer") {
            currentDatabase.reference.child(USERS_PATH).child(recipientId).get()
                .addOnSuccessListener { snapshot ->
                    val online = snapshot.child("online").getValue(Boolean::class.java) == true
                    val pushReady = snapshot.child("pushReady").getValue(Boolean::class.java) == true
                    val watchReady = snapshot.child("watchReady").getValue(Boolean::class.java) == true
                    val busy = snapshot.child("busy").getValue(Boolean::class.java) == true
                    if (busy) {
                        listener.onMessage(
                            SignalMessage(type = "busy", from = recipientId, to = currentUserId, callId = message.callId)
                        )
                    } else if (online || pushReady || watchReady) {
                        enqueue(message, currentUid)
                    } else {
                        listener.onMessage(
                            SignalMessage(
                                type = "unavailable",
                                from = recipientId,
                                to = currentUserId,
                                callId = message.callId,
                            )
                        )
                    }
                }
                .addOnFailureListener { error -> reportDisconnected("Nie można sprawdzić dostępności rozmówcy", error) }
        } else {
            enqueue(message, currentUid)
        }
        return true
    }

    fun observeContacts(ids: Collection<String>) {
        val currentDatabase = database ?: return
        val wanted = ids.toSet()
        contactListeners.keys.filterNot(wanted::contains).forEach { id ->
            contactListeners.remove(id)?.let { (reference, observer) -> reference.removeEventListener(observer) }
        }
        wanted.filterNot(contactListeners::containsKey).forEach { id ->
            val reference = currentDatabase.reference.child(USERS_PATH).child(id)
            val observer = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    listener.onPresenceChanged(
                        id,
                        ContactPresence(
                            online = snapshot.child("online").getValue(Boolean::class.java) == true,
                            busy = snapshot.child("busy").getValue(Boolean::class.java) == true,
                            pushReady = snapshot.child("pushReady").getValue(Boolean::class.java) == true,
                            watchReady = snapshot.child("watchReady").getValue(Boolean::class.java) == true,
                            lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L,
                        ),
                    )
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            reference.addValueEventListener(observer)
            contactListeners[id] = reference to observer
        }
    }

    fun setBusy(busy: Boolean) {
        presenceRef?.updateChildren(
            mapOf(
                "busy" to busy,
                "lastSeen" to ServerValue.TIMESTAMP,
            )
        )
    }

    fun disconnect() {
        connectionGeneration += 1
        intentionallyClosed = true
        ready = false

        connectionListener?.let { current -> connectionRef?.removeEventListener(current) }
        signalsListener?.let { current -> signalsRef?.removeEventListener(current) }
        contactListeners.values.forEach { (reference, observer) -> reference.removeEventListener(observer) }
        contactListeners.clear()
        presenceRef?.updateChildren(offlinePresence())

        connectionListener = null
        connectionRef = null
        signalsListener = null
        signalsRef = null
        presenceRef = null
        userUid = null
        userId = null
        database = null
        auth = null
    }

    fun close() = disconnect()

    private fun attachToDatabase(userId: String, uid: String, generation: Int) {
        val currentDatabase = database ?: return
        userUid = uid
        val connectedReference = currentDatabase.getReference(".info/connected")
        connectionRef = connectedReference
        connectionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (generation != connectionGeneration || intentionallyClosed) return
                if (snapshot.getValue(Boolean::class.java) == true) {
                    registerPresence(userId, uid, generation)
                } else if (ready) {
                    ready = false
                    listener.onDisconnected("Utracono połączenie z Firebase")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (generation == connectionGeneration && !intentionallyClosed) {
                    reportDisconnected("Firebase odrzucił dostęp do informacji o połączeniu", error.toException())
                }
            }
        }.also(connectedReference::addValueEventListener)
    }

    private fun registerPresence(userId: String, uid: String, generation: Int) {
        val currentDatabase = database ?: return
        val reference = currentDatabase.reference.child(USERS_PATH).child(userId)
        presenceRef = reference
        reference.runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(data: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                val owner = data.child("authUid").getValue(String::class.java)
                if (owner != null && owner != uid) return com.google.firebase.database.Transaction.abort()
                val watchReady = data.child("watchReady").getValue(Boolean::class.java) ?: false
                data.value = onlinePresence(uid, watchReady)
                return com.google.firebase.database.Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (generation != connectionGeneration || intentionallyClosed) return
                if (error != null) {
                    reportDisconnected("Firebase odrzucił rejestrację telefonu", error.toException())
                    return
                }
                if (!committed) {
                    reportDisconnected("Ten identyfikator telefonu jest już przypisany do innej instalacji")
                    return
                }

                reference.onDisconnect().updateChildren(offlinePresence())
                    .addOnSuccessListener {
                        if (generation != connectionGeneration || intentionallyClosed) return@addOnSuccessListener
                        attachSignalListener(userId, generation)
                        ready = true
                        if (BuildConfig.FCM_ENABLED) PushTokenRegistry.refresh(userId, uid, currentDatabase)
                        listener.onConnected()
                    }
                    .addOnFailureListener { failure ->
                        if (generation == connectionGeneration && !intentionallyClosed) {
                            reportDisconnected("Nie udało się zabezpieczyć statusu telefonu", failure)
                        }
                    }
            }
        })
    }

    private fun attachSignalListener(userId: String, generation: Int) {
        if (signalsListener != null) return
        val reference = database?.reference?.child(SIGNALS_PATH)?.child(userId) ?: return
        signalsRef = reference
        signalsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (generation != connectionGeneration || intentionallyClosed) return
                val message = snapshot.toSignalMessage()
                val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                val maxAge = if (message?.type == "offer") OFFER_MAX_AGE_MS else MESSAGE_MAX_AGE_MS
                val fresh = createdAt == 0L || System.currentTimeMillis() - createdAt <= maxAge
                if (message != null && message.to == userId && fresh) listener.onMessage(message)
                snapshot.ref.removeValue()
            }

            override fun onCancelled(error: DatabaseError) {
                if (generation == connectionGeneration && !intentionallyClosed) {
                    reportDisconnected("Firebase odrzucił odbieranie połączeń", error.toException())
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
        }.also(reference::addChildEventListener)
    }

    private fun enqueue(message: SignalMessage, uid: String) {
        val recipientId = message.to ?: return
        val payload = mutableMapOf<String, Any>(
            "type" to message.type,
            "from" to (message.from ?: return),
            "to" to recipientId,
            "senderUid" to uid,
            "createdAt" to ServerValue.TIMESTAMP,
        )
        message.callId?.let { payload["callId"] = it }
        message.sdp?.let { payload["sdp"] = it }
        message.sdpType?.let { payload["sdpType"] = it }
        message.candidate?.let { payload["candidate"] = it }
        message.sdpMid?.let { payload["sdpMid"] = it }
        message.sdpMLineIndex?.let { payload["sdpMLineIndex"] = it }
        message.reason?.let { payload["reason"] = it }

        val signalReference = database?.reference?.child(SIGNALS_PATH)?.child(recipientId)?.push() ?: return
        val signalId = signalReference.key ?: return
        signalReference.setValue(payload)
            .addOnSuccessListener {
                if (message.type == "offer") {
                    val callId = message.callId ?: return@addOnSuccessListener
                    CallWakeGateway.notifyIncomingCall(
                        recipientId = recipientId,
                        callerId = message.from,
                        callId = callId,
                        signalId = signalId,
                    )
                }
            }
            .addOnFailureListener { error -> reportDisconnected("Nie udało się wysłać danych połączenia", error) }
    }

    private fun DataSnapshot.toSignalMessage(): SignalMessage? = runCatching {
        SignalMessage(
            type = child("type").getValue(String::class.java) ?: return null,
            from = child("from").getValue(String::class.java),
            to = child("to").getValue(String::class.java),
            callId = child("callId").getValue(String::class.java),
            sdp = child("sdp").getValue(String::class.java),
            sdpType = child("sdpType").getValue(String::class.java),
            candidate = child("candidate").getValue(String::class.java),
            sdpMid = child("sdpMid").getValue(String::class.java),
            sdpMLineIndex = child("sdpMLineIndex").getValue(Long::class.java)?.toInt(),
            reason = child("reason").getValue(String::class.java),
        )
    }.getOrNull()

    private fun reportDisconnected(message: String, error: Throwable? = null) {
        if (intentionallyClosed) return
        ready = false
        val detail = error?.localizedMessage?.takeIf { it.isNotBlank() }
        listener.onDisconnected(if (detail == null) message else "$message: $detail")
    }

    private fun onlinePresence(uid: String, watchReady: Boolean): Map<String, Any> = mapOf(
        "authUid" to uid,
        "online" to AppVisibility.isForeground,
        "busy" to false,
        "pushReady" to false,
        "watchReady" to watchReady,
        "lastSeen" to ServerValue.TIMESTAMP,
    )

    private fun offlinePresence(): Map<String, Any> = mapOf(
        "online" to false,
        "busy" to false,
        "lastSeen" to ServerValue.TIMESTAMP,
    )

    companion object {
        private const val USERS_PATH = "users"
        private const val SIGNALS_PATH = "signals"
        private const val MESSAGE_MAX_AGE_MS = 2 * 60 * 1000L
        private const val OFFER_MAX_AGE_MS = 45_000L
    }
}
