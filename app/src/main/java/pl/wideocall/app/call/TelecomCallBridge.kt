package pl.wideocall.app.call

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.data.AppPreferences

/** Keeps one WebRTC video call registered with Android Telecom. Chat sessions are excluded. */
object TelecomCallBridge {
    interface Listener {
        fun onTelecomAnswer(callId: String): Boolean
        fun onTelecomDisconnect(callId: String): Boolean
        fun onTelecomMuteChanged(callId: String, muted: Boolean)
        fun onTelecomAudioRoutePending(callId: String, pending: Boolean) = Unit
        fun onTelecomError(callId: String, message: String) = Unit
        fun onTelecomAudioEndpointChanged(
            callId: String,
            current: CallEndpointCompat?,
            available: List<CallEndpointCompat>,
        )
    }

    private sealed interface Action {
        data object Answer : Action
        data object Activate : Action
        data class ChangeEndpoint(val speaker: Boolean, val revision: Long) : Action
        data class Disconnect(val cause: DisconnectCause) : Action
    }

    private data class Registration(
        val callId: String,
        val remoteId: String,
        val incoming: Boolean,
        val video: Boolean,
        val actions: Channel<Action>,
        var timeoutJob: Job? = null,
        var appInitiatedDisconnect: Boolean = false,
        var currentEndpoint: CallEndpointCompat? = null,
        var availableEndpoints: List<CallEndpointCompat> = emptyList(),
        val endpointFlow: MutableStateFlow<CallEndpointCompat?> = MutableStateFlow(null),
        val availableFlow: MutableStateFlow<List<CallEndpointCompat>> = MutableStateFlow(emptyList()),
        var routeJob: Job? = null,
        var routeRevision: Long = 0L,
        var requestedSpeaker: Boolean? = null,
        var userSelectedRoute: Boolean = false,
        var mediaActive: Boolean = false,
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

    fun registerIncoming(callId: String, remoteId: String, video: Boolean = true): Boolean =
        register(callId, remoteId, incoming = true, video = video)

    fun registerOutgoing(callId: String, remoteId: String, video: Boolean = true): Boolean =
        register(callId, remoteId, incoming = false, video = video)

    fun answer(callId: String) = send(callId, Action.Answer)

    fun activate(callId: String) = send(callId, Action.Activate)

    fun toggleSpeaker(callId: String?): Boolean {
        if (callId == null) return false
        val registration = synchronized(lock) { active?.takeIf { it.callId == callId } } ?: return false
        val speaker = !(registration.requestedSpeaker ?: (registration.currentEndpoint?.type == CallEndpointCompat.TYPE_SPEAKER))
        registration.userSelectedRoute = true
        registration.requestedSpeaker = speaker
        val revision = ++registration.routeRevision
        listener?.onTelecomAudioRoutePending(callId, true)
        val queued = send(callId, Action.ChangeEndpoint(speaker, revision))
        if (!queued) {
            registration.requestedSpeaker = null
            listener?.onTelecomAudioRoutePending(callId, false)
        }
        return queued
    }

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
    private fun register(callId: String, remoteId: String, incoming: Boolean, video: Boolean): Boolean {
        val manager = callsManager ?: return false
        val registration = synchronized(lock) {
            active?.let { return it.callId == callId }
            Registration(callId, remoteId, incoming, video, Channel(Channel.BUFFERED)).also { active = it }
        }
        val attributes = CallAttributesCompat(
            displayName = remoteId,
            address = Uri.parse("wideocall:$remoteId"),
            direction = if (incoming) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            },
            callType = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = 0,
        )

        scope.launch {
            try {
                manager.addCall(
                    attributes,
                    onAnswer = {
                        registration.mediaActive = true
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
                    onSetActive = { registration.mediaActive = true },
                    onSetInactive = { registration.mediaActive = false },
                ) {
                    launch {
                        isMuted.collect { muted ->
                            if (active?.callId == callId) {
                                listener?.onTelecomMuteChanged(callId, muted)
                            }
                        }
                    }
                    launch {
                        currentCallEndpoint.collect { endpoint ->
                            registration.currentEndpoint = endpoint
                            registration.endpointFlow.value = endpoint
                            notifyAudioEndpoints(registration)
                        }
                    }
                    launch {
                        availableEndpoints.collect { endpoints ->
                            registration.availableEndpoints = endpoints
                            registration.availableFlow.value = endpoints
                            notifyAudioEndpoints(registration)
                        }
                    }
                    launch { processActions(registration) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Android Telecom rejected the call")
                listener?.onTelecomError(callId, "Android Telecom: nie udało się utrzymać systemowej sesji rozmowy (${error.javaClass.simpleName}).")
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
                Action.Answer -> answer(if (registration.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL).also {
                    if (it !is CallControlResult.Error) registration.mediaActive = true
                }
                Action.Activate -> {
                    // Answering already makes an incoming Telecom call active.
                    val activeResult = if (registration.mediaActive) null else setActive()
                    if (activeResult !is CallControlResult.Error && !registration.userSelectedRoute) {
                        registration.mediaActive = true
                        startAudioRouteChange(registration, speaker = true, revision = ++registration.routeRevision, automatic = true)
                    }
                    activeResult
                }
                is Action.ChangeEndpoint -> {
                    startAudioRouteChange(registration, action.speaker, action.revision, automatic = false)
                    null
                }
                is Action.Disconnect -> {
                    registration.routeJob?.cancel()
                    disconnect(action.cause)
                }
            }
            if (result is CallControlResult.Error) {
                Log.w(TAG, "Android Telecom action failed: ${result.errorCode}")
                listener?.onTelecomError(registration.callId, "Android Telecom: operacja nie powiodła się (kod ${result.errorCode}).")
                applicationContext?.let {
                    AppPreferences(it).saveBackgroundIssue("Android Telecom: operacja nie powiodła się (kod ${result.errorCode}).")
                }
            }
            if (action is Action.Disconnect) registration.actions.close()
        }
    }

    private fun CallControlScope.startAudioRouteChange(registration: Registration, speaker: Boolean, revision: Long, automatic: Boolean) {
        registration.routeJob?.cancel()
        registration.routeJob = scope.launch {
            try {
                withTimeout(8_000L) {
                    if (automatic) {
                        val current = registration.endpointFlow.filterNotNull().first()
                        // Never take sound away from a connected headset, nor override a user's choice.
                        if (registration.userSelectedRoute || !AudioRoutePolicy.preferSpeakerOnStart(current.type)) {
                            return@withTimeout
                        }
                        registration.requestedSpeaker = true
                        listener?.onTelecomAudioRoutePending(registration.callId, true)
                    }
                    val target = registration.availableFlow.map { endpoints ->
                        val type = AudioRoutePolicy.targetType(speaker, endpoints.map { it.type })
                        endpoints.firstOrNull { it.type == type }
                    }.filterNotNull().first()
                    if (registration.endpointFlow.value?.type == target.type) {
                        return@withTimeout
                    }
                    val result = requestEndpointChange(target)
                    if (result is CallControlResult.Error) {
                        error("kod ${result.errorCode}")
                    }
                    // API success alone does not prove the actual output changed.
                    withTimeout(3_000L) { registration.endpointFlow.filterNotNull().first { it.type == target.type } }
                    Log.i(TAG, "audio_route_confirmed type=${target.type}")
                }
            } catch (error: TimeoutCancellationException) {
                reportAudioRouteError(registration, "Android nie potwierdził zmiany wyjścia dźwięku. Spróbuj ponownie.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportAudioRouteError(registration, "Nie udało się zmienić wyjścia dźwięku (${error.message?.takeIf { it.startsWith("kod ") } ?: error.javaClass.simpleName}).")
            } finally {
                if (active === registration && registration.routeRevision == revision) {
                    registration.requestedSpeaker = null
                    listener?.onTelecomAudioRoutePending(registration.callId, false)
                }
            }
        }
    }

    private fun reportAudioRouteError(registration: Registration, message: String) {
        if (active !== registration || registration.appInitiatedDisconnect) return
        Log.w(TAG, "audio_route_failed")
        listener?.onTelecomError(registration.callId, "Dźwięk: $message")
        applicationContext?.let { AppPreferences(it).saveBackgroundIssue("Dźwięk: $message") }
    }

    private fun send(callId: String, action: Action): Boolean {
        val registration = synchronized(lock) { active?.takeIf { it.callId == callId } } ?: return false
        when (action) {
            Action.Answer,
            Action.Activate,
            is Action.ChangeEndpoint -> registration.timeoutJob?.cancel()
            is Action.Disconnect -> registration.appInitiatedDisconnect = true
        }
        return registration.actions.trySend(action).isSuccess
    }

    private fun clear(registration: Registration) {
        registration.timeoutJob?.cancel()
        registration.routeJob?.cancel()
        registration.actions.close()
        synchronized(lock) {
            if (active === registration) active = null
        }
        listener?.onTelecomAudioRoutePending(registration.callId, false)
    }

    private fun dispatchActivityAction(registration: Registration, action: String) {
        applicationContext?.let {
            CallNotifications.dispatchAction(it, action, registration.remoteId, registration.callId)
        }
    }

    private fun notifyAudioEndpoints(registration: Registration) {
        if (active?.callId != registration.callId) return
        listener?.onTelecomAudioEndpointChanged(
            registration.callId,
            registration.currentEndpoint,
            registration.availableEndpoints,
        )
    }
}
