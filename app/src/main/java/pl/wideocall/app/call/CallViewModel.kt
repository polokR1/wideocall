package pl.wideocall.app.call

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer
import org.json.JSONObject
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.data.Contact
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.notifications.ChatNotifications
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.notifications.CallWatcherService
import pl.wideocall.app.notifications.OngoingCallService
import pl.wideocall.app.signaling.ContactPresence
import pl.wideocall.app.signaling.SignalMessage
import pl.wideocall.app.signaling.SignalingClient
import java.util.UUID

enum class CallPhase {
    CONNECTING_SERVER,
    READY,
    SERVER_ERROR,
    OUTGOING,
    INCOMING,
    CONNECTING_CALL,
    IN_CALL,
    CONNECTING_CHAT,
    IN_CHAT,
}

data class AppUiState(
    val myId: String,
    val displayName: String,
    val contacts: List<Contact>,
    val watchEnabled: Boolean = false,
    val contactPresence: Map<String, ContactPresence> = emptyMap(),
    val phase: CallPhase = CallPhase.CONNECTING_SERVER,
    val status: String = "Łączenie…",
    val remoteId: String? = null,
    val remoteName: String? = null,
    val callId: String? = null,
    val microphoneEnabled: Boolean = true,
    val frontCamera: Boolean = true,
    val chatMessages: List<EphemeralChatMessage> = emptyList(),
    val chatReady: Boolean = false,
    val chatVisible: Boolean = false,
)

data class EphemeralChatMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val createdAt: Long,
)

class CallViewModel(application: Application) : AndroidViewModel(application),
    SignalingClient.Listener,
    WebRtcClient.Listener {

    private val preferences = AppPreferences(application)
    private val signalingClient = SignalingClient(this)
    private val webRtcClient = WebRtcClient(application, this)
    private val _state = MutableStateFlow(
        AppUiState(
            myId = preferences.myId,
            displayName = preferences.displayName,
            contacts = preferences.contacts(),
            watchEnabled = preferences.watchEnabled,
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var activeCallId: String? = null
    private var activeRemoteId: String? = null
    private var activeSessionKind: String = SignalMessage.SESSION_VIDEO
    private var pendingOffer: SignalMessage? = null
    private val pendingIncomingCandidates = mutableListOf<IceCandidate>()
    private var callTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        connect()
    }

    fun connect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _state.update { it.copy(phase = CallPhase.CONNECTING_SERVER, status = "Łączenie z Firebase…") }
        signalingClient.connect(_state.value.myId)
    }

    fun refreshPreferences() {
        _state.update {
            it.copy(
                displayName = preferences.displayName,
                watchEnabled = preferences.watchEnabled,
            )
        }
    }

    fun saveSettings(displayName: String, watchEnabled: Boolean) {
        preferences.displayName = displayName
        preferences.watchEnabled = watchEnabled
        if (watchEnabled) {
            CallWatcherService.start(getApplication())
        } else {
            CallWatcherService.stop(getApplication())
        }
        _state.update { it.copy(displayName = preferences.displayName, watchEnabled = watchEnabled) }
    }

    fun addContact(name: String, id: String) {
        val normalizedId = AppPreferences.normalizeId(id)
        if (normalizedId.length != 9 || normalizedId == _state.value.myId) return
        preferences.saveContact(Contact(normalizedId, name))
        _state.update { it.copy(contacts = preferences.contacts()) }
        signalingClient.observeContacts(_state.value.contacts.map(Contact::id))
    }

    fun removeContact(id: String) {
        preferences.removeContact(id)
        _state.update { current ->
            current.copy(
                contacts = preferences.contacts(),
                contactPresence = current.contactPresence - AppPreferences.normalizeId(id),
            )
        }
        signalingClient.observeContacts(_state.value.contacts.map(Contact::id))
    }

    fun call(contact: Contact) {
        startOutgoingSession(contact, SignalMessage.SESSION_VIDEO)
    }

    fun startChat(contact: Contact) {
        startOutgoingSession(contact, SignalMessage.SESSION_CHAT)
    }

    private fun startOutgoingSession(contact: Contact, sessionKind: String) {
        if (_state.value.phase != CallPhase.READY) return
        val callId = UUID.randomUUID().toString()
        activeCallId = callId
        activeRemoteId = contact.id
        activeSessionKind = sessionKind
        val chatOnly = sessionKind == SignalMessage.SESSION_CHAT
        _state.update {
            it.copy(
                phase = if (chatOnly) CallPhase.CONNECTING_CHAT else CallPhase.OUTGOING,
                status = if (chatOnly) "Łączenie szyfrowanego czatu…" else "Dzwonienie…",
                remoteId = contact.id,
                remoteName = contact.name,
                callId = callId,
                microphoneEnabled = true,
                frontCamera = true,
                chatMessages = emptyList(),
                chatReady = false,
                chatVisible = chatOnly,
            )
        }
        signalingClient.setBusy(true)
        startOutgoingTimeout(callId, contact.id)
        runCatching {
            webRtcClient.prepareSession(isCaller = true, withMedia = !chatOnly)
            webRtcClient.createOffer { offer ->
                signalingClient.send(
                    SignalMessage(
                        type = "offer",
                        from = _state.value.myId,
                        to = contact.id,
                        callId = callId,
                        sessionKind = sessionKind,
                        sdpType = offer.type.canonicalForm(),
                        sdp = offer.description,
                    )
                )
            }
        }.onFailure {
            failCall(it.message ?: if (chatOnly) "Nie udało się rozpocząć czatu" else "Nie udało się rozpocząć rozmowy")
        }
    }

    fun acceptIncomingCall() {
        val offer = pendingOffer ?: return
        acceptOffer(offer, withMedia = true)
    }

    private fun acceptOffer(offer: SignalMessage, withMedia: Boolean) {
        val remoteId = offer.from ?: return
        val sdp = offer.sdp ?: return
        activeCallId = offer.callId
        activeRemoteId = remoteId
        activeSessionKind = offer.sessionKind
        _state.update {
            it.copy(
                phase = if (withMedia) CallPhase.CONNECTING_CALL else CallPhase.CONNECTING_CHAT,
                status = if (withMedia) "Łączenie…" else "Łączenie szyfrowanego czatu…",
                chatVisible = !withMedia,
            )
        }
        callTimeoutJob?.cancel()
        CallNotifications.cancel(getApplication(), offer.callId)
        ChatNotifications.cancel(getApplication(), offer.callId)
        runCatching {
            webRtcClient.prepareSession(isCaller = false, withMedia = withMedia)
            webRtcClient.setRemoteDescription(offer.sdpType ?: "offer", sdp) {
                pendingIncomingCandidates.forEach(webRtcClient::addRemoteIceCandidate)
                pendingIncomingCandidates.clear()
                webRtcClient.createAnswer { answer ->
                    signalingClient.send(
                        SignalMessage(
                            type = "answer",
                            from = _state.value.myId,
                            to = remoteId,
                            callId = activeCallId,
                            sessionKind = offer.sessionKind,
                            sdpType = answer.type.canonicalForm(),
                            sdp = answer.description,
                        )
                    )
                }
            }
            pendingOffer = null
        }.onFailure {
            failCall(it.message ?: if (withMedia) "Nie udało się odebrać rozmowy" else "Nie udało się otworzyć czatu")
        }
    }

    fun rejectIncomingCall() {
        val offer = pendingOffer
        if (offer != null) {
            signalingClient.send(
                SignalMessage(
                    type = "reject",
                    from = _state.value.myId,
                    to = offer.from,
                    callId = offer.callId,
                    sessionKind = offer.sessionKind,
                )
            )
        }
        CallNotifications.cancel(getApplication(), offer?.callId)
        ChatNotifications.cancel(getApplication(), offer?.callId)
        pendingOffer = null
        clearCall("Gotowy")
    }

    fun hangUp() {
        val remoteId = activeRemoteId
        if (remoteId != null) {
            signalingClient.send(
                SignalMessage(
                    type = "hangup",
                    from = _state.value.myId,
                    to = remoteId,
                    callId = activeCallId,
                    sessionKind = activeSessionKind,
                )
            )
        }
        clearCall(if (activeSessionKind == SignalMessage.SESSION_CHAT) "Czat zakończony" else "Rozmowa zakończona")
    }

    fun toggleMicrophone() {
        val enabled = !_state.value.microphoneEnabled
        webRtcClient.setMicrophoneEnabled(enabled)
        _state.update { it.copy(microphoneEnabled = enabled) }
    }

    fun switchCamera() {
        webRtcClient.switchCamera()
        _state.update { it.copy(frontCamera = !it.frontCamera) }
    }

    fun toggleChat() {
        _state.update { it.copy(chatVisible = !it.chatVisible) }
    }

    fun sendChatMessage(raw: String) {
        if (_state.value.phase !in setOf(CallPhase.IN_CALL, CallPhase.IN_CHAT) || !_state.value.chatReady) return
        val text = EphemeralChatPolicy.sanitize(raw)
        if (text.isBlank()) return
        val message = EphemeralChatMessage(UUID.randomUUID().toString(), text, true, System.currentTimeMillis())
        val payload = JSONObject().put("text", message.text).toString()
        if (webRtcClient.sendChatMessage(payload)) addChatMessage(message)
    }

    fun bindLocalRenderer(renderer: SurfaceViewRenderer?) = webRtcClient.bindLocalRenderer(renderer)
    fun bindRemoteRenderer(renderer: SurfaceViewRenderer?) = webRtcClient.bindRemoteRenderer(renderer)
    fun eglContext() = webRtcClient.eglContext

    override fun onConnected() = onMain {
        reconnectJob?.cancel()
        reconnectJob = null
        if (_state.value.phase in listOf(CallPhase.CONNECTING_SERVER, CallPhase.SERVER_ERROR, CallPhase.READY)) {
            _state.update { it.copy(phase = CallPhase.READY, status = "Gotowy do rozmowy") }
            signalingClient.observeContacts(_state.value.contacts.map(Contact::id))
        }
    }

    override fun onDisconnected(reason: String) = onMain {
        if (_state.value.phase == CallPhase.READY || _state.value.phase == CallPhase.CONNECTING_SERVER) {
            _state.update { it.copy(phase = CallPhase.SERVER_ERROR, status = "$reason • ponawiam połączenie…") }
            scheduleReconnect()
        } else {
            failCall("Utracono połączenie z serwerem")
        }
    }

    override fun onMessage(message: SignalMessage) = onMain {
        when (message.type) {
            "offer" -> handleOffer(message)
            "answer" -> if (message.callId == activeCallId && message.sdp != null) {
                val chatOnly = activeSessionKind == SignalMessage.SESSION_CHAT
                _state.update {
                    it.copy(
                        phase = if (chatOnly) CallPhase.CONNECTING_CHAT else CallPhase.CONNECTING_CALL,
                        status = if (chatOnly) "Łączenie szyfrowanego czatu…" else "Łączenie…",
                    )
                }
                webRtcClient.setRemoteDescription(message.sdpType ?: "answer", message.sdp)
            }
            "ice" -> if (message.callId == activeCallId && message.candidate != null) {
                val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
                if (_state.value.phase == CallPhase.INCOMING) pendingIncomingCandidates += candidate
                else webRtcClient.addRemoteIceCandidate(candidate)
            }
            "hangup" -> if (message.callId == activeCallId) {
                clearCall(if (activeSessionKind == SignalMessage.SESSION_CHAT) "Rozmówca zakończył czat" else "Rozmówca zakończył rozmowę")
            }
            "reject" -> if (message.callId == activeCallId) {
                clearCall(if (activeSessionKind == SignalMessage.SESSION_CHAT) "Czat odrzucony" else "Połączenie odrzucone")
            }
            "busy" -> if (message.callId == activeCallId) clearCall("Rozmówca jest zajęty")
            "unavailable" -> if (message.callId == activeCallId) clearCall("Rozmówca jest niedostępny")
        }
    }

    override fun onPresenceChanged(userId: String, presence: ContactPresence) = onMain {
        _state.update { it.copy(contactPresence = it.contactPresence + (userId to presence)) }
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        val remoteId = activeRemoteId ?: return
        signalingClient.send(
            SignalMessage(
                type = "ice",
                from = _state.value.myId,
                to = remoteId,
                callId = activeCallId,
                sessionKind = activeSessionKind,
                candidate = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
            )
        )
    }

    override fun onConnectionStateChanged(connected: Boolean) = onMain {
        if (connected) {
            val chatOnly = activeSessionKind == SignalMessage.SESSION_CHAT
            _state.update {
                it.copy(
                    phase = if (chatOnly) CallPhase.IN_CHAT else CallPhase.IN_CALL,
                    status = if (chatOnly) "Szyfrowany czat połączony" else "Połączono",
                    chatVisible = chatOnly || it.chatVisible,
                )
            }
            callTimeoutJob?.cancel()
            if (!chatOnly) {
                val callId = activeCallId
                val remote = _state.value.remoteName ?: activeRemoteId
                if (callId != null && remote != null) OngoingCallService.start(getApplication(), remote, callId)
            }
        } else if (_state.value.phase in listOf(CallPhase.CONNECTING_CALL, CallPhase.IN_CALL, CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT)) {
            clearCall(if (activeSessionKind == SignalMessage.SESSION_CHAT) "Czat został przerwany" else "Połączenie zostało przerwane")
        }
    }

    override fun onChatReadyChanged(ready: Boolean) = onMain {
        _state.update { it.copy(chatReady = ready) }
    }

    override fun onChatMessage(message: String) = onMain {
        val parsed = runCatching {
            val json = JSONObject(message)
            val text = EphemeralChatPolicy.sanitize(json.getString("text"))
            if (text.isBlank()) null else EphemeralChatMessage(UUID.randomUUID().toString(), text, false, System.currentTimeMillis())
        }.getOrNull() ?: return@onMain
        addChatMessage(parsed)
        if (!AppVisibility.isForeground && activeSessionKind == SignalMessage.SESSION_CHAT) {
            ChatNotifications.showMessage(
                getApplication(),
                _state.value.remoteName ?: activeRemoteId.orEmpty(),
                activeCallId,
            )
        }
    }

    override fun onError(message: String) = onMain { failCall(message) }

    private fun handleOffer(message: SignalMessage) {
        val from = message.from ?: return
        if (_state.value.phase != CallPhase.READY) {
            signalingClient.send(
                SignalMessage(
                    type = "busy",
                    from = _state.value.myId,
                    to = from,
                    callId = message.callId,
                    sessionKind = message.sessionKind,
                )
            )
            return
        }
        pendingOffer = message
        pendingIncomingCandidates.clear()
        activeCallId = message.callId
        activeRemoteId = from
        activeSessionKind = message.sessionKind
        val contact = _state.value.contacts.firstOrNull { it.id == from }
        val chatOnly = message.sessionKind == SignalMessage.SESSION_CHAT
        _state.update {
            it.copy(
                phase = if (chatOnly) CallPhase.CONNECTING_CHAT else CallPhase.INCOMING,
                status = if (chatOnly) "Otwieranie szyfrowanego czatu…" else "Połączenie przychodzące",
                remoteId = from,
                remoteName = contact?.name ?: from,
                callId = message.callId,
                chatMessages = emptyList(),
                chatReady = false,
                chatVisible = chatOnly,
            )
        }
        if (AppVisibility.isForeground) {
            CallNotifications.cancel(getApplication(), message.callId)
            ChatNotifications.cancel(getApplication(), message.callId)
        }
        signalingClient.setBusy(true)
        if (chatOnly) {
            acceptOffer(message, withMedia = false)
        } else {
            startIncomingTimeout(message.callId)
        }
    }

    private fun failCall(message: String) {
        callTimeoutJob?.cancel()
        signalingClient.setBusy(false)
        CallNotifications.cancel(getApplication(), activeCallId)
        ChatNotifications.cancel(getApplication(), activeCallId)
        OngoingCallService.stop(getApplication())
        webRtcClient.endCall()
        activeCallId = null
        activeRemoteId = null
        activeSessionKind = SignalMessage.SESSION_VIDEO
        pendingOffer = null
        pendingIncomingCandidates.clear()
        _state.update {
            it.copy(
                phase = CallPhase.SERVER_ERROR,
                status = message,
                remoteId = null,
                remoteName = null,
                callId = null,
                chatMessages = emptyList(),
                chatReady = false,
                chatVisible = false,
            )
        }
    }

    private fun clearCall(status: String) {
        callTimeoutJob?.cancel()
        signalingClient.setBusy(false)
        CallNotifications.cancel(getApplication(), activeCallId)
        ChatNotifications.cancel(getApplication(), activeCallId)
        OngoingCallService.stop(getApplication())
        webRtcClient.endCall()
        activeCallId = null
        activeRemoteId = null
        activeSessionKind = SignalMessage.SESSION_VIDEO
        pendingOffer = null
        pendingIncomingCandidates.clear()
        _state.update {
            it.copy(
                phase = CallPhase.READY,
                status = status,
                remoteId = null,
                remoteName = null,
                callId = null,
                microphoneEnabled = true,
                frontCamera = true,
                chatMessages = emptyList(),
                chatReady = false,
                chatVisible = false,
            )
        }
    }

    private fun addChatMessage(message: EphemeralChatMessage) {
        _state.update { it.copy(chatMessages = (it.chatMessages + message).takeLast(MAX_CHAT_MESSAGES)) }
        viewModelScope.launch {
            delay(EphemeralChatPolicy.LIFETIME_MS)
            _state.update { current -> current.copy(chatMessages = current.chatMessages.filterNot { it.id == message.id }) }
        }
    }

    private fun startOutgoingTimeout(callId: String, remoteId: String) {
        callTimeoutJob?.cancel()
        callTimeoutJob = viewModelScope.launch {
            delay(CALL_TIMEOUT_MS)
            if (activeCallId == callId && _state.value.phase in setOf(
                    CallPhase.OUTGOING,
                    CallPhase.CONNECTING_CALL,
                    CallPhase.CONNECTING_CHAT,
                )
            ) {
                signalingClient.send(
                    SignalMessage(
                        type = "hangup",
                        from = _state.value.myId,
                        to = remoteId,
                        callId = callId,
                        sessionKind = activeSessionKind,
                    )
                )
                clearCall(if (activeSessionKind == SignalMessage.SESSION_CHAT) "Nie udało się połączyć czatu" else "Nie odebrano")
            }
        }
    }

    private fun startIncomingTimeout(callId: String?) {
        if (callId == null) return
        callTimeoutJob?.cancel()
        callTimeoutJob = viewModelScope.launch {
            delay(CALL_TIMEOUT_MS)
            if (activeCallId == callId && _state.value.phase == CallPhase.INCOMING) {
                CallNotifications.cancel(getApplication(), callId)
                clearCall("Nie odebrano")
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectJob = null
            if (_state.value.phase == CallPhase.SERVER_ERROR) connect()
        }
    }

    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun onCleared() {
        callTimeoutJob?.cancel()
        reconnectJob?.cancel()
        OngoingCallService.stop(getApplication())
        signalingClient.close()
        webRtcClient.release()
        super.onCleared()
    }

    companion object {
        private const val CALL_TIMEOUT_MS = 35_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_CHAT_MESSAGES = 100
    }
}
