package pl.wideocall.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import pl.wideocall.app.MainActivity
import pl.wideocall.app.R
import pl.wideocall.app.data.AppPreferences

class CallWatcherService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var signalsRef: DatabaseReference? = null
    private var signalListener: ChildEventListener? = null
    private var presenceRef: DatabaseReference? = null
    private var intentionallyStopped = false
    private val reconnect = Runnable {
        if (!intentionallyStopped && signalListener == null) connect()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        promoteToForeground("Uruchamianie czuwania…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppPreferences(this).watchEnabled = false
            intentionallyStopped = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (signalListener == null) connect()
        return START_STICKY
    }

    private fun connect() {
        handler.removeCallbacks(reconnect)
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return showError()
        val databaseUrl = app.options.databaseUrl ?: return showError()
        val database = FirebaseDatabase.getInstance(databaseUrl)
        val auth = FirebaseAuth.getInstance(app)
        val userId = AppPreferences(this).myId
        val current = auth.currentUser
        if (current != null) {
            registerWatcher(database, userId, current.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result -> result.user?.uid?.let { registerWatcher(database, userId, it) } ?: showError() }
                .addOnFailureListener { showError() }
        }
    }

    private fun registerWatcher(database: FirebaseDatabase, userId: String, uid: String) {
        val reference = database.reference.child("users").child(userId)
        presenceRef = reference
        reference.runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(data: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                val owner = data.child("authUid").getValue(String::class.java)
                if (owner != null && owner != uid) return com.google.firebase.database.Transaction.abort()
                data.value = mapOf(
                    "authUid" to uid,
                    "online" to AppVisibility.isForeground,
                    "busy" to (data.child("busy").getValue(Boolean::class.java) ?: false),
                    "pushReady" to (data.child("pushReady").getValue(Boolean::class.java) ?: false),
                    "watchReady" to true,
                    "lastSeen" to ServerValue.TIMESTAMP,
                )
                return com.google.firebase.database.Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null || !committed || intentionallyStopped) return showError()
                reference.onDisconnect().updateChildren(
                    mapOf(
                        "online" to false,
                        "busy" to false,
                        "watchReady" to false,
                        "lastSeen" to ServerValue.TIMESTAMP,
                    )
                )
                attachSignalListener(database, userId)
                handler.removeCallbacks(reconnect)
                promoteToForeground("Czuwanie aktywne • $userId")
            }
        })
    }

    private fun attachSignalListener(database: FirebaseDatabase, userId: String) {
        if (signalListener != null) return
        val reference = database.reference.child("signals").child(userId)
        signalsRef = reference
        signalListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val type = snapshot.child("type").getValue(String::class.java) ?: return
                val callId = snapshot.child("callId").getValue(String::class.java)
                val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                val age = (System.currentTimeMillis() - createdAt).coerceAtLeast(0L)
                if (createdAt > 0L && age > SIGNAL_MAX_AGE_MS) {
                    snapshot.ref.removeValue()
                    return
                }
                if (AppVisibility.isForeground) return
                when (type) {
                    "offer" -> {
                        val callerId = snapshot.child("from").getValue(String::class.java) ?: return
                        if (callId == null || age > OFFER_MAX_AGE_MS) {
                            snapshot.ref.removeValue()
                            return
                        }
                        CallNotifications.showIncoming(this@CallWatcherService, callerId, callId)
                        handler.postDelayed({ expireOffer(snapshot.ref, callId) }, OFFER_MAX_AGE_MS - age)
                    }
                    "hangup", "reject" -> if (callId != null) {
                        CallNotifications.cancel(this@CallWatcherService, callId)
                        cleanCallSignals(callId)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) = showError()
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
        }.also(reference::addChildEventListener)
    }

    private fun expireOffer(reference: DatabaseReference, callId: String) {
        reference.get().addOnSuccessListener { snapshot ->
            if (snapshot.child("callId").getValue(String::class.java) == callId) reference.removeValue()
        }
        CallNotifications.cancel(this, callId)
    }

    private fun cleanCallSignals(callId: String) {
        signalsRef?.get()?.addOnSuccessListener { snapshot ->
            snapshot.children
                .filter { it.child("callId").getValue(String::class.java) == callId }
                .forEach { it.ref.removeValue() }
        }
    }

    private fun promoteToForeground(text: String) {
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            7140,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            7141,
            Intent(this, CallWatcherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wideocall czuwa")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Wyłącz", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Czuwanie Wideocall", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Stały nasłuch połączeń przychodzących"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun showError() {
        promoteToForeground("Czuwanie czeka na połączenie z Firebase")
        handler.removeCallbacks(reconnect)
        if (!intentionallyStopped) handler.postDelayed(reconnect, RETRY_DELAY_MS)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        signalListener?.let { listener -> signalsRef?.removeEventListener(listener) }
        signalListener = null
        signalsRef = null
        presenceRef?.updateChildren(
            mapOf(
                "watchReady" to false,
                "lastSeen" to ServerValue.TIMESTAMP,
            )
        )
        presenceRef = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        private const val CHANNEL_ID = "call_watcher"
        private const val NOTIFICATION_ID = 7142
        private const val ACTION_STOP = "pl.wideocall.app.STOP_WATCHER"
        private const val RETRY_DELAY_MS = 15_000L
        private const val OFFER_MAX_AGE_MS = 45_000L
        private const val SIGNAL_MAX_AGE_MS = 2 * 60_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallWatcherService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallWatcherService::class.java))
        }
    }
}
