package pl.wideocall.app.call

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
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
import pl.wideocall.app.notifications.OngoingCallService
import pl.wideocall.app.signaling.ContactPresence
import pl.wideocall.app.signaling.SignalMessage
import pl.wideocall.app.signaling.SignalingClient
import pl.wideocall.app.security.IdentityKeyManager
import pl.wideocall.app.security.PeerIdentityStatus
import pl.wideocall.app.security.PeerIdentityStore
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
    val safetyNumber: String? = null,
    val peerIdentityVerified: Boolean = false,
)

data class EphemeralChatMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val createdAt: Long,
)

class CallViewModel(application: Application) : AndroidViewModel(application),
    SignalingClient.Listener,
    WebRtcClient.Listener,
    TelecomCallBridge.Listener {

    private val preferences = AppPreferences(application)
    private val signalingClient = SignalingClient(application, this)
    private val webRtcClient = WebRtcClient(application, this)
    private val peerIdentityStore = PeerIdentityStore(application)
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
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
    private var activeIsCaller = false
    private var iceConnected = false
    private var pendingOffer: SignalMessage? = null
    private val pendingIncomingCandidates = mutableListOf<IceCandidate>()
    private var callTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var iceRecoveryJob: Job? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onMain {
            if (hasActiveSession() && activeIsCaller) {
                scheduleIceRecovery(NETWORK_CHANGE_RECOVERY_DELAY_MS)
            }
        }

        override fun onLost(network: Network) = onMain {
            if (hasActiveSession()) {
                _state.update { it.copy(status = "Utracono sieć • oczekiwanie na połączenie…") }
            }
        }
    }

    init {
        TelecomCallBridge.attachListener(this)
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
        connect()
    }

    fun connect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _state.update { it.copy(phase = CallPhase.CONNECTING_SERVER, status = "Łączenie z usługą rozmów…") }
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
        // FCM wakes a closed app; a permanent Firebase listener is no longer used.
        preferences.watchEnabled = false
        _state.update { it.copy(displayName = preferences.displayName, watchEnabled = false) }
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
        activeIsCaller = true
        iceConnected = false
        iceRecoveryJob?.cancel()
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
                safetyNumber = null,
                peerIdentityVerified = false,
            )
        }
        signalingClient.setBusy(true)
        if (!chatOnly) {
            TelecomCallBridge.registerOutgoing(callId, contact.id)
            OngoingCallService.start(getApplication(), contact.name, callId)
        }
        startOutgoingTimeout(callId, contact.id)
        prepareWebRtcSession(callId, isCaller = true, withMedia = !chatOnly) {
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
        }
    }

    fun acceptIncomingCall() {
        val offer = pendingOffer ?: return
        TelecomCallBridge.answer(offer.callId ?: return)
        acceptOffer(offer, withMedia = true)
    }

    fun acceptIncomingCallFromTelecom() {
        val offer = pendingOffer ?: return
        acceptOffer(offer, withMedia = true)
    }

    private fun acceptOffer(offer: SignalMessage, withMedia: Boolean) {
        val remoteId = offer.from ?: return
        val sdp = offer.sdp ?: return
        val callId = offer.callId ?: return
        activeCallId = callId
        activeRemoteId = remoteId
        activeSessionKind = offer.sessionKind
        activeIsCaller = false
        iceConnected = false
        iceRecoveryJob?.cancel()
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
        prepareWebRtcSession(callId, isCaller = false, withMedia = withMedia) {
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
        }
    }

    fun rejectIncomingCall() = rejectIncomingCall(notifyTelecom = true)

    fun rejectIncomingCallFromTelecom() = rejectIncomingCall(notifyTelecom = false)

    private fun rejectIncomingCall(notifyTelecom: Boolean) {
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
        clearCall("Gotowy", TelecomEnd.NONE)
        if (notifyTelecom) TelecomCallBridge.disconnectRejected(offer?.callId)
    }

    fun hangUp() = hangUp(notifyTelecom = true)

    fun hangUpFromTelecom() = hangUp(notifyTelecom = false)

    private fun hangUp(notifyTelecom: Boolean) {
        val callId = activeCallId
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
        clearCall(
            if (activeSessionKind == SignalMessage.SESSION_CHAT) "Czat zakończony" else "Rozmowa zakończona",
            TelecomEnd.NONE,
        )
        if (notifyTelecom) TelecomCallBridge.disconnectLocal(callId)
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
        TurnCredentialProvider.prefetch(_state.value.myId)
        signalingClient.observeContacts(_state.value.contacts.map(Contact::id))
        if (_state.value.phase in listOf(CallPhase.CONNECTING_SERVER, CallPhase.SERVER_ERROR, CallPhase.READY)) {
            _state.update { it.copy(phase = CallPhase.READY, status = "Gotowy do rozmowy") }
        } else if (hasActiveSession() && iceConnected) {
            _state.update { it.copy(status = if (activeSessionKind == SignalMessage.SESSION_CHAT) "Szyfrowany czat połączony" else "Połączono") }
        }
    }

    override fun onDisconnected(reason: String) = onMain {
        if (hasActiveSession()) {
            _state.update { it.copy(status = "Połączenie trwa • odnawiam sygnalizację…") }
            scheduleReconnect(preserveActiveSession = true)
        } else if (_state.value.phase == CallPhase.READY || _state.value.phase == CallPhase.CONNECTING_SERVER) {
            _state.update { it.copy(phase = CallPhase.SERVER_ERROR, status = "$reason • ponawiam połączenie…") }
            scheduleReconnect(preserveActiveSession = false)
        } else {
            failCall("Utracono połączenie z serwerem")
        }
    }

    override fun onMessage(message: SignalMessage) = onMain {
        if (!authenticateMessage(message)) return@onMain
        when (message.type) {
            "offer" -> handleOffer(message)
            "answer" -> if (isActiveMessage(message) && message.sdp != null) {
                val chatOnly = activeSessionKind == SignalMessage.SESSION_CHAT
                _state.update {
                    it.copy(
                        phase = if (chatOnly) CallPhase.CONNECTING_CHAT else CallPhase.CONNECTING_CALL,
                        status = if (chatOnly) "Łączenie szyfrowanego czatu…" else "Łączenie…",
                    )
                }
                webRtcClient.setRemoteDescription(message.sdpType ?: "answer", message.sdp)
            }
            "ice-restart-offer" -> if (isActiveMessage(message)) handleIceRestartOffer(message)
            "ice-restart-answer" -> if (isActiveMessage(message) && message.sdp != null) {
                webRtcClient.setRemoteDescription(message.sdpType ?: "answer", message.sdp)
            }
            "ice" -> if (isActiveMessage(message) && message.candidate != null) {
                val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
                if (_state.value.phase == CallPhase.INCOMING) pendingIncomingCandidates += candidate
                else webRtcClient.addRemoteIceCandidate(candidate)
            }
            "hangup" -> if (isActiveMessage(message)) {
                clearCall(
                    if (activeSessionKind == SignalMessage.SESSION_CHAT) "Rozmówca zakończył czat" else "Rozmówca zakończył rozmowę",
                    TelecomEnd.REMOTE,
                )
            }
            "reject" -> if (isActiveMessage(message)) {
                clearCall(
                    if (activeSessionKind == SignalMessage.SESSION_CHAT) "Czat odrzucony" else "Połączenie odrzucone",
                    TelecomEnd.REJECTED,
                )
            }
            "busy" -> if (isActiveMessage(message)) clearCall("Rozmówca jest zajęty", TelecomEnd.BUSY)
            "unavailable" -> if (isActiveMessage(message)) clearCall("Rozmówca jest niedostępny", TelecomEnd.ERROR)
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

    override fun onConnectionStateChanged(state: RtcConnectionState) = onMain {
        if (state == RtcConnectionState.CONNECTED) {
            iceConnected = true
            iceRecoveryJob?.cancel()
            iceRecoveryJob = null
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
                if (callId != null) TelecomCallBridge.activate(callId)
                if (callId != null && remote != null) OngoingCallService.start(getApplication(), remote, callId)
            }
        } else if (hasActiveSession()) {
            iceConnected = false
            scheduleIceRecovery(
                if (state == RtcConnectionState.FAILED) 0L else ICE_DISCONNECTED_GRACE_MS,
            )
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

    fun markCurrentPeerVerified() {
        val remoteId = activeRemoteId ?: return
        if (_state.value.safetyNumber == null || !peerIdentityStore.markVerified(remoteId)) return
        _state.update { it.copy(peerIdentityVerified = true) }
    }

    private fun authenticateMessage(message: SignalMessage): Boolean {
        if (message.localOnly) return true
        val remoteId = message.from ?: return false
        val identityKey = message.identityKey
        val signature = message.signature
        val signatureValid = identityKey != null && signature != null &&
            IdentityKeyManager.verify(identityKey, message.signingPayload(identityKey), signature)
        if (!signatureValid) {
            blockUntrustedMessage(message, "Odrzucono niepodpisane lub zmienione dane połączenia")
            return false
        }
        val trust = peerIdentityStore.checkAndPin(remoteId, identityKey)
        if (trust.status == PeerIdentityStatus.CHANGED) {
            blockUntrustedMessage(message, "Klucz bezpieczeństwa kontaktu zmienił się — połączenie zablokowane")
            return false
        }
        _state.update {
            it.copy(
                safetyNumber = trust.safetyNumber,
                peerIdentityVerified = trust.manuallyVerified,
            )
        }
        return true
    }

    private fun blockUntrustedMessage(message: SignalMessage, warning: String) {
        if (message.callId != null && message.callId == activeCallId) {
            clearCall(warning, TelecomEnd.ERROR)
        } else {
            _state.update { it.copy(status = warning) }
        }
    }

    override fun onTelecomAnswer(callId: String): Boolean {
        val offer = pendingOffer
        if (offer?.callId != callId || _state.value.phase != CallPhase.INCOMING) return false
        onMain {
            if (pendingOffer?.callId == callId && _state.value.phase == CallPhase.INCOMING) {
                acceptIncomingCallFromTelecom()
            }
        }
        return true
    }

    override fun onTelecomDisconnect(callId: String): Boolean {
        if (activeCallId != callId || !hasActiveSession()) return false
        onMain {
            if (activeCallId != callId) return@onMain
            if (_state.value.phase == CallPhase.INCOMING) rejectIncomingCallFromTelecom()
            else hangUpFromTelecom()
        }
        return true
    }

    override fun onTelecomMuteChanged(callId: String, muted: Boolean) = onMain {
        if (activeCallId != callId) return@onMain
        webRtcClient.setMicrophoneEnabled(!muted)
        _state.update { it.copy(microphoneEnabled = !muted) }
    }

    private fun handleOffer(message: SignalMessage) {
        val from = message.from ?: return
        val callId = message.callId ?: return
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
        activeCallId = callId
        activeRemoteId = from
        activeSessionKind = message.sessionKind
        activeIsCaller = false
        iceConnected = false
        iceRecoveryJob?.cancel()
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
            if (chatOnly) ChatNotifications.cancel(getApplication(), message.callId)
        }
        signalingClient.setBusy(true)
        if (chatOnly) {
            acceptOffer(message, withMedia = false)
        } else {
            TelecomCallBridge.registerIncoming(callId, contact?.name ?: from)
            CallNotifications.showIncoming(getApplication(), contact?.name ?: from, callId)
            startIncomingTimeout(message.callId)
        }
    }

    private fun failCall(message: String) {
        val callId = activeCallId
        callTimeoutJob?.cancel()
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        signalingClient.setBusy(false)
        CallNotifications.cancel(getApplication(), activeCallId)
        ChatNotifications.cancel(getApplication(), activeCallId)
        OngoingCallService.stop(getApplication())
        webRtcClient.endCall()
        TelecomCallBridge.disconnectError(callId)
        activeCallId = null
        activeRemoteId = null
        activeSessionKind = SignalMessage.SESSION_VIDEO
        activeIsCaller = false
        iceConnected = false
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
                safetyNumber = null,
                peerIdentityVerified = false,
            )
        }
    }

    private fun clearCall(status: String, telecomEnd: TelecomEnd = TelecomEnd.LOCAL) {
        val callId = activeCallId
        callTimeoutJob?.cancel()
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        signalingClient.setBusy(false)
        CallNotifications.cancel(getApplication(), activeCallId)
        ChatNotifications.cancel(getApplication(), activeCallId)
        OngoingCallService.stop(getApplication())
        webRtcClient.endCall()
        when (telecomEnd) {
            TelecomEnd.NONE -> Unit
            TelecomEnd.LOCAL -> TelecomCallBridge.disconnectLocal(callId)
            TelecomEnd.REMOTE -> TelecomCallBridge.disconnectRemote(callId)
            TelecomEnd.REJECTED -> TelecomCallBridge.disconnectRejected(callId)
            TelecomEnd.MISSED -> TelecomCallBridge.disconnectMissed(callId)
            TelecomEnd.BUSY -> TelecomCallBridge.disconnectBusy(callId)
            TelecomEnd.ERROR -> TelecomCallBridge.disconnectError(callId)
        }
        activeCallId = null
        activeRemoteId = null
        activeSessionKind = SignalMessage.SESSION_VIDEO
        activeIsCaller = false
        iceConnected = false
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
                safetyNumber = null,
                peerIdentityVerified = false,
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
                clearCall(
                    if (activeSessionKind == SignalMessage.SESSION_CHAT) "Nie udało się połączyć czatu" else "Nie odebrano",
                    TelecomEnd.MISSED,
                )
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
                clearCall("Nie odebrano", TelecomEnd.MISSED)
            }
        }
    }

    private fun scheduleReconnect(preserveActiveSession: Boolean) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectJob = null
            if (preserveActiveSession && hasActiveSession()) {
                signalingClient.connect(_state.value.myId)
            } else if (_state.value.phase == CallPhase.SERVER_ERROR) {
                connect()
            }
        }
    }

    private fun prepareWebRtcSession(
        callId: String,
        isCaller: Boolean,
        withMedia: Boolean,
        onPrepared: () -> Unit,
    ) {
        TurnCredentialProvider.get(_state.value.myId) { turnConfiguration ->
            onMain session@{
                if (activeCallId != callId) return@session
                runCatching {
                    webRtcClient.prepareSession(isCaller, withMedia, turnConfiguration)
                    onPrepared()
                }.onFailure {
                    failCall(
                        it.message ?: if (withMedia) "Nie udało się przygotować rozmowy" else "Nie udało się przygotować czatu",
                    )
                }
            }
        }
    }

    private fun handleIceRestartOffer(message: SignalMessage) {
        val sdp = message.sdp ?: return
        val remoteId = activeRemoteId ?: return
        _state.update { it.copy(status = "Przywracanie połączenia…") }
        webRtcClient.setRemoteDescription(message.sdpType ?: "offer", sdp) {
            webRtcClient.createAnswer { answer ->
                signalingClient.send(
                    SignalMessage(
                        type = "ice-restart-answer",
                        from = _state.value.myId,
                        to = remoteId,
                        callId = activeCallId,
                        sessionKind = activeSessionKind,
                        sdpType = answer.type.canonicalForm(),
                        sdp = answer.description,
                    )
                )
            }
        }
    }

    private fun scheduleIceRecovery(initialDelayMs: Long) {
        val callId = activeCallId ?: return
        if (iceRecoveryJob?.isActive == true) return
        _state.update { it.copy(status = "Połączenie osłabło • przywracanie…") }
        iceRecoveryJob = viewModelScope.launch {
            if (!activeIsCaller) {
                delay(ICE_REMOTE_RECOVERY_TIMEOUT_MS)
            } else {
                delay(initialDelayMs)
                repeat(MAX_ICE_RESTART_ATTEMPTS) {
                    if (activeCallId != callId || iceConnected) return@launch
                    sendIceRestartOffer()
                    delay(ICE_RESTART_RETRY_MS)
                }
            }
            if (activeCallId == callId && !iceConnected) {
                failCall(if (activeSessionKind == SignalMessage.SESSION_CHAT) "Czat został przerwany" else "Połączenie zostało przerwane")
            }
        }
    }

    private fun sendIceRestartOffer() {
        val remoteId = activeRemoteId ?: return
        webRtcClient.createIceRestartOffer { offer ->
            signalingClient.send(
                SignalMessage(
                    type = "ice-restart-offer",
                    from = _state.value.myId,
                    to = remoteId,
                    callId = activeCallId,
                    sessionKind = activeSessionKind,
                    sdpType = offer.type.canonicalForm(),
                    sdp = offer.description,
                )
            )
        }
    }

    private fun isActiveMessage(message: SignalMessage): Boolean =
        message.callId != null && message.callId == activeCallId && message.from == activeRemoteId

    private fun hasActiveSession(): Boolean = activeCallId != null && _state.value.phase in setOf(
        CallPhase.OUTGOING,
        CallPhase.INCOMING,
        CallPhase.CONNECTING_CALL,
        CallPhase.IN_CALL,
        CallPhase.CONNECTING_CHAT,
        CallPhase.IN_CHAT,
    )

    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun onCleared() {
        callTimeoutJob?.cancel()
        reconnectJob?.cancel()
        iceRecoveryJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        OngoingCallService.stop(getApplication())
        TelecomCallBridge.disconnectLocal(activeCallId)
        signalingClient.close()
        webRtcClient.release()
        TelecomCallBridge.detachListener(this)
        super.onCleared()
    }

    private enum class TelecomEnd {
        NONE,
        LOCAL,
        REMOTE,
        REJECTED,
        MISSED,
        BUSY,
        ERROR,
    }

    companion object {
        private const val CALL_TIMEOUT_MS = 35_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val NETWORK_CHANGE_RECOVERY_DELAY_MS = 1_500L
        private const val ICE_DISCONNECTED_GRACE_MS = 4_000L
        private const val ICE_RESTART_RETRY_MS = 8_000L
        private const val ICE_REMOTE_RECOVERY_TIMEOUT_MS = 30_000L
        private const val MAX_ICE_RESTART_ATTEMPTS = 3
        private const val MAX_CHAT_MESSAGES = 100
    }
}
