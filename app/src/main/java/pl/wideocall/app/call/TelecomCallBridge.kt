package pl.wideocall.app.call

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.data.AppPreferences

/** Keeps one WebRTC video call registered with Android Telecom. Chat sessions are excluded. */
object TelecomCallBridge {
    interface Listener {
        fun onTelecomAnswer(callId: String): Boolean
        fun onTelecomDisconnect(callId: String): Boolean
        fun onTelecomMuteChanged(callId: String, muted: Boolean)
    }

    private sealed interface Action {
        data object Answer : Action
        data object Activate : Action
        data class Disconnect(val cause: DisconnectCause) : Action
    }

    private data class Registration(
        val callId: String,
        val remoteId: String,
        val incoming: Boolean,
        val actions: Channel<Action>,
        var timeoutJob: Job? = null,
        var appInitiatedDisconnect: Boolean = false,
    )

    private const val TAG = "TelecomCallBridge"
    private const val INCOMING_TIMEOUT_MS = 40_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    @Volatile
    private var listener: Listener? = null
    @SuppressLint("StaticFieldLeak") // The stored value is always context.applicationContext.
    private var applicationContext: Context? = null
    @SuppressLint("StaticFieldLeak") // CallsManager is created only with the application context.
    private var callsManager: CallsManager? = null
    private var active: Registration? = null

    @SuppressLint("MissingPermission")
    fun initialize(context: Context) {
        if (callsManager != null) return
        synchronized(lock) {
            if (callsManager != null) return
            applicationContext = context.applicationContext
            callsManager = runCatching {
                CallsManager(context.applicationContext).apply {
                    registerAppWithTelecom(
                        CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING,
                    )
                }
            }.onFailure {
                Log.w(TAG, "Android Telecom registration unavailable")
            }.getOrNull()
        }
    }

    fun attachListener(value: Listener) {
        listener = value
    }

    fun detachListener(value: Listener) {
        if (listener === value) listener = null
    }

    fun registerIncoming(callId: String, remoteId: String): Boolean =
        register(callId, remoteId, incoming = true)

    fun registerOutgoing(callId: String, remoteId: String): Boolean =
        register(callId, remoteId, incoming = false)

    fun answer(callId: String) = send(callId, Action.Answer)

    fun activate(callId: String) = send(callId, Action.Activate)

    fun disconnectLocal(callId: String?) = disconnect(callId, DisconnectCause.LOCAL)

    fun disconnectRejected(callId: String?) = disconnect(callId, DisconnectCause.REJECTED)

    fun disconnectRemote(callId: String?) = disconnect(callId, DisconnectCause.REMOTE)

    fun disconnectMissed(callId: String?) = disconnect(callId, DisconnectCause.MISSED)

    fun disconnectBusy(callId: String?) = disconnect(callId, DisconnectCause.BUSY)

    fun disconnectError(callId: String?) = disconnect(callId, DisconnectCause.ERROR)

    private fun disconnect(callId: String?, code: Int) {
        if (callId != null) send(callId, Action.Disconnect(DisconnectCause(code)))
    }

    @SuppressLint("MissingPermission")
    private fun register(callId: String, remoteId: String, incoming: Boolean): Boolean {
        val manager = callsManager ?: return false
        val registration = synchronized(lock) {
            active?.let { return it.callId == callId }
            Registration(callId, remoteId, incoming, Channel(Channel.BUFFERED)).also { active = it }
        }
        val attributes = CallAttributesCompat(
            displayName = remoteId,
            address = Uri.parse("wideocall:$remoteId"),
            direction = if (incoming) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            },
            callType = CallAttributesCompat.CALL_TYPE_VIDEO_CALL,
            callCapabilities = 0,
        )

        scope.launch {
            try {
                manager.addCall(
                    attributes,
                    onAnswer = {
                        registration.timeoutJob?.cancel()
                        if (active?.callId == callId && listener?.onTelecomAnswer(callId) != true) {
                            dispatchActivityAction(registration, CallNotifications.ACTION_TELECOM_ANSWER)
                        }
                    },
                    onDisconnect = {
                        if (!registration.appInitiatedDisconnect &&
                            active?.callId == callId &&
                            listener?.onTelecomDisconnect(callId) != true
                        ) {
                            dispatchActivityAction(
                                registration,
                                if (incoming) {
                                    CallNotifications.ACTION_TELECOM_DECLINE
                                } else {
                                    CallNotifications.ACTION_TELECOM_HANG_UP
                                },
                            )
                        }
                        registration.actions.close()
                    },
                    onSetActive = { Unit },
                    onSetInactive = { Unit },
                ) {
                    launch {
                        isMuted.collect { muted ->
                            if (active?.callId == callId) {
                                listener?.onTelecomMuteChanged(callId, muted)
                            }
                        }
                    }
                    launch { processActions(registration) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Android Telecom rejected the call")
                applicationContext?.let {
                    AppPreferences(it).saveBackgroundIssue("Android Telecom: ${error.javaClass.simpleName}.")
                }
            } finally {
                clear(registration)
            }
        }
        if (incoming) {
            registration.timeoutJob = scope.launch {
                delay(INCOMING_TIMEOUT_MS)
                disconnectMissed(callId)
            }
        }
        return true
    }

    private suspend fun CallControlScope.processActions(registration: Registration) {
        for (action in registration.actions) {
            val result = when (action) {
                Action.Answer -> answer(CallAttributesCompat.CALL_TYPE_VIDEO_CALL)
                Action.Activate -> setActive()
                is Action.Disconnect -> disconnect(action.cause)
            }
            if (result is CallControlResult.Error) {
                Log.w(TAG, "Android Telecom action failed: ${result.errorCode}")
                applicationContext?.let {
                    AppPreferences(it).saveBackgroundIssue("Android Telecom: operacja nie powiodła się (kod ${result.errorCode}).")
                }
            }
            if (action is Action.Disconnect) registration.actions.close()
        }
    }

    private fun send(callId: String, action: Action) {
        val registration = synchronized(lock) { active?.takeIf { it.callId == callId } } ?: return
        when (action) {
            Action.Answer,
            Action.Activate -> registration.timeoutJob?.cancel()
            is Action.Disconnect -> registration.appInitiatedDisconnect = true
        }
        registration.actions.trySend(action)
    }

    private fun clear(registration: Registration) {
        registration.timeoutJob?.cancel()
        registration.actions.close()
        synchronized(lock) {
            if (active === registration) active = null
        }
    }

    private fun dispatchActivityAction(registration: Registration, action: String) {
        applicationContext?.let {
            CallNotifications.dispatchAction(it, action, registration.remoteId, registration.callId)
        }
    }
}
