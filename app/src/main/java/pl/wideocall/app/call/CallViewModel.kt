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
import pl.wideocall.app.data.FriendRequest
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
    val friendRequests: List<FriendRequest> = emptyList(),
    val unreadChatCount: Int = 0,
    val diagnostic: String? = null,
)

data class EphemeralChatMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val createdAt: Long,
    val openedAt: Long? = null,
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
            friendRequests = preferences.friendRequests(),
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
                _state.update {
                    it.copy(
                        status = "Utracono sieć • oczekiwanie na połączenie…",
                        diagnostic = "Sieć: Android zgłosił utratę aktywnego połączenia internetowego.",
                    )
                }
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
        _state.update { it.copy(phase = CallPhase.CONNECTING_SERVER, status = "Łączenie z usługą rozmów…", diagnostic = null) }
        signalingClient.connect(_state.value.myId)
    }

    fun refreshPreferences() {
        val backgroundIssue = preferences.consumeBackgroundIssue()
        _state.update {
            it.copy(
                displayName = preferences.displayName,
                watchEnabled = preferences.watchEnabled,
                friendRequests = preferences.friendRequests(),
                diagnostic = backgroundIssue ?: it.diagnostic,
            )
        }
    }

    fun onAppVisible() {
        if (_state.value.chatVisible && _state.value.chatMessages.any { it.openedAt == null }) openChat()
    }

    fun saveSettings(displayName: String, watchEnabled: Boolean) {
        preferences.displayName = displayName
        // FCM wakes a closed app; a permanent Firebase listener is no longer used.
        preferences.watchEnabled = false
        _state.update { it.copy(displayName = preferences.displayName, watchEnabled = false) }
    }

    private fun addContact(name: String, id: String) {
        val normalizedId = AppPreferences.normalizeId(id)
        if (normalizedId.length != 9 || normalizedId == _state.value.myId) return
        preferences.saveContact(Contact(normalizedId, name))
        _state.update { it.copy(contacts = preferences.contacts()) }
        signalingClient.observeContacts(_state.value.contacts.map(Contact::id))
    }

    fun sendFriendRequest(id: String) {
        val normalizedId = AppPreferences.normalizeId(id)
        if (normalizedId.length != 9) {
            _state.update { it.copy(status = "Nieprawidłowy identyfikator", diagnostic = "Zaproszenie: identyfikator musi mieć format XXXX-XXXX.") }
            return
        }
        if (normalizedId == _state.value.myId) {
            _state.update { it.copy(status = "Nie można zaprosić własnego telefonu", diagnostic = "Zaproszenie: podano identyfikator tego urządzenia.") }
            return
        }
        if (_state.value.phase != CallPhase.READY) {
            _state.update { it.copy(diagnostic = "Zaproszenie: usługa sygnalizacji nie jest teraz gotowa.") }
            return
        }
        val requestId = UUID.randomUUID().toString()
        val sent = signalingClient.send(
            SignalMessage(
                type = "contact-request",
                from = _state.value.myId,
                to = normalizedId,
                callId = requestId,
                sessionKind = SignalMessage.SESSION_CONTACT,
                reason = _state.value.displayName.take(80),
            )
        )
        _state.update {
            it.copy(
                status = if (sent) "Zaproszenie wysłane do $normalizedId" else "Nie udało się wysłać zaproszenia",
                diagnostic = if (sent) null else "Zaproszenie: brak aktywnego połączenia z Cloudflare.",
            )
        }
    }

    fun acceptFriendRequest(request: FriendRequest) {
        val sent = signalingClient.send(
            SignalMessage(
                type = "contact-accept",
                from = _state.value.myId,
                to = request.fromId,
                callId = request.requestId,
                sessionKind = SignalMessage.SESSION_CONTACT,
                reason = _state.value.displayName.take(80),
            )
        )
        if (!sent) {
            _state.update { it.copy(status = "Nie udało się zaakceptować zaproszenia", diagnostic = "Zaproszenie: brak aktywnego połączenia z Cloudflare.") }
            return
        }
        addContact(request.displayName, request.fromId)
        preferences.removeFriendRequest(request.requestId)
        _state.update { it.copy(friendRequests = preferences.friendRequests(), status = "Dodano ${request.displayName}", diagnostic = null) }
    }

    fun rejectFriendRequest(request: FriendRequest) {
        val sent = signalingClient.send(
            SignalMessage(
                type = "contact-reject",
                from = _state.value.myId,
                to = request.fromId,
                callId = request.requestId,
                sessionKind = SignalMessage.SESSION_CONTACT,
                reason = _state.value.displayName.take(80),
            )
        )
        if (!sent) {
            _state.update { it.copy(status = "Nie udało się odrzucić zaproszenia", diagnostic = "Zaproszenie: brak aktywnego połączenia z Cloudflare.") }
            return
        }
        preferences.removeFriendRequest(request.requestId)
        _state.update { it.copy(friendRequests = preferences.friendRequests(), status = "Zaproszenie odrzucone", diagnostic = null) }
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
                unreadChatCount = 0,
                safetyNumber = null,
                peerIdentityVerified = false,
                diagnostic = null,
            )
        }
        signalingClient.setBusy(true)
        if (!chatOnly) {
            if (!TelecomCallBridge.registerOutgoing(callId, contact.id)) {
                _state.update { it.copy(diagnostic = "Android Telecom: system nie zarejestrował połączenia wychodzącego.") }
            }
            runCatching { OngoingCallService.start(getApplication(), contact.name, callId) }
                .onFailure { error -> _state.update { it.copy(diagnostic = "Powiadomienie rozmowy: ${error.javaClass.simpleName}.") } }
        }
        startOutgoingTimeout(callId, contact.id)
        prepareWebRtcSession(callId, isCaller = true, withMedia = !chatOnly) {
            webRtcClient.createOffer { offer ->
                val sent = signalingClient.send(
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
                if (!sent) failCall("Nie udało się rozpocząć połączenia", "Sygnalizacja: oferta WebRTC nie została wysłana.")
            }
        }
    }

    fun acceptIncomingCall() {
        val offer = pendingOffer ?: return
        notifyCallAccepted(offer)
        TelecomCallBridge.answer(offer.callId ?: return)
        acceptOffer(offer, withMedia = true)
    }

    fun acceptIncomingCallFromTelecom() {
        val offer = pendingOffer ?: return
        notifyCallAccepted(offer)
        acceptOffer(offer, withMedia = true)
    }

    private fun notifyCallAccepted(offer: SignalMessage) {
        CallNotifications.cancel(getApplication(), offer.callId)
        val sent = signalingClient.send(
            SignalMessage(
                type = "accepted",
                from = _state.value.myId,
                to = offer.from,
                callId = offer.callId,
                sessionKind = offer.sessionKind,
            )
        )
        if (!sent) {
            _state.update { it.copy(diagnostic = "Odbieranie: nie udało się potwierdzić odebrania przez sygnalizację.") }
        }
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
                    val sent = signalingClient.send(
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
                    if (!sent) failCall("Nie udało się odebrać połączenia", "Sygnalizacja: odpowiedź WebRTC nie została wysłana.")
                }
            }
            pendingOffer = null
        }
    }

    fun rejectIncomingCall() = rejectIncomingCall(notifyTelecom = true)

    fun rejectIncomingCallFromTelecom() = rejectIncomingCall(notifyTelecom = false)

    private fun rejectIncomingCall(notifyTelecom: Boolean) {
        val offer = pendingOffer
        val rejectionSent = if (offer != null) {
            signalingClient.send(
                SignalMessage(
                    type = "reject",
                    from = _state.value.myId,
                    to = offer.from,
                    callId = offer.callId,
                    sessionKind = offer.sessionKind,
                )
            )
        } else true
        CallNotifications.cancel(getApplication(), offer?.callId)
        ChatNotifications.cancel(getApplication(), offer?.callId)
        pendingOffer = null
        clearCall("Gotowy", TelecomEnd.NONE)
        if (!rejectionSent) _state.update { it.copy(diagnostic = "Odrzucanie: druga strona mogła nie otrzymać potwierdzenia.") }
        if (notifyTelecom) TelecomCallBridge.disconnectRejected(offer?.callId)
    }

    fun hangUp() = hangUp(notifyTelecom = true)

    fun hangUpFromTelecom() = hangUp(notifyTelecom = false)

    private fun hangUp(notifyTelecom: Boolean) {
        val callId = activeCallId
        val remoteId = activeRemoteId
        val hangupSent = if (remoteId != null) {
            signalingClient.send(
                SignalMessage(
                    type = "hangup",
                    from = _state.value.myId,
                    to = remoteId,
                    callId = activeCallId,
                    sessionKind = activeSessionKind,
                )
            )
        } else true
        clearCall(
            if (activeSessionKind == SignalMessage.SESSION_CHAT) "Czat zakończony" else "Rozmowa zakończona",
            TelecomEnd.NONE,
        )
        if (!hangupSent) _state.update { it.copy(diagnostic = "Kończenie: sygnał rozłączenia nie został dostarczony; druga strona zakończy po czasie.") }
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
        if (_state.value.chatVisible) closeChatLocally() else openChat()
    }

    fun closeChatLocally() {
        _state.update { it.copy(chatVisible = false) }
    }

    fun openChat() {
        val now = System.currentTimeMillis()
        val newlyOpened = _state.value.chatMessages.filter { it.openedAt == null }.map { it.id }
        _state.update { current ->
            current.copy(
                chatVisible = true,
                unreadChatCount = 0,
                chatMessages = current.chatMessages.map { message ->
                    if (message.openedAt == null) message.copy(openedAt = now) else message
                },
            )
        }
        newlyOpened.forEach { scheduleMessageExpiry(it, now) }
    }

    fun sendChatMessage(raw: String) {
        if (_state.value.phase !in setOf(CallPhase.IN_CALL, CallPhase.IN_CHAT) || !_state.value.chatReady) return
        val text = EphemeralChatPolicy.sanitize(raw)
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val message = EphemeralChatMessage(UUID.randomUUID().toString(), text, true, now, now)
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
            _state.update { it.copy(phase = CallPhase.READY, status = "Gotowy do rozmowy", diagnostic = null) }
        } else if (hasActiveSession() && iceConnected) {
            _state.update { it.copy(status = if (activeSessionKind == SignalMessage.SESSION_CHAT) "Szyfrowany czat połączony" else "Połączono") }
        }
    }

    override fun onDisconnected(reason: String) = onMain {
        if (hasActiveSession()) {
            _state.update { it.copy(status = "Połączenie trwa • odnawiam sygnalizację…") }
            scheduleReconnect(preserveActiveSession = true)
        } else if (_state.value.phase == CallPhase.READY || _state.value.phase == CallPhase.CONNECTING_SERVER) {
            _state.update {
                it.copy(
                    phase = CallPhase.SERVER_ERROR,
                    status = "$reason • ponawiam połączenie…",
                    diagnostic = "Sygnalizacja Cloudflare: $reason",
                )
            }
            scheduleReconnect(preserveActiveSession = false)
        } else {
            failCall("Utracono połączenie z serwerem")
        }
    }

    override fun onMessage(message: SignalMessage) = onMain {
        if (!authenticateMessage(message)) return@onMain
        when (message.type) {
            "contact-request" -> handleFriendRequest(message)
            "contact-accept" -> handleFriendAccepted(message)
            "contact-reject" -> handleFriendRejected(message)
            "offer" -> handleOffer(message)
            "accepted" -> if (isActiveMessage(message) && _state.value.phase == CallPhase.OUTGOING) {
                _state.update { it.copy(status = "Odebrano • zestawianie połączenia…", diagnostic = null) }
            }
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
                    diagnostic = null,
                )
            }
            callTimeoutJob?.cancel()
            if (!chatOnly) {
                val callId = activeCallId
                val remote = _state.value.remoteName ?: activeRemoteId
                if (callId != null) TelecomCallBridge.activate(callId)
                if (callId != null && remote != null) {
                    runCatching { OngoingCallService.start(getApplication(), remote, callId) }
                        .onFailure { error -> _state.update { it.copy(diagnostic = "Powiadomienie rozmowy: ${error.javaClass.simpleName}.") } }
                }
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
            val now = System.currentTimeMillis()
            if (text.isBlank()) null else EphemeralChatMessage(
                id = UUID.randomUUID().toString(),
                text = text,
                mine = false,
                createdAt = now,
                openedAt = now.takeIf { _state.value.chatVisible && AppVisibility.isForeground },
            )
        }.getOrNull() ?: return@onMain
        addChatMessage(parsed)
        if (!_state.value.chatVisible || !AppVisibility.isForeground) {
            _state.update { it.copy(unreadChatCount = it.unreadChatCount + 1) }
        }
        if (!AppVisibility.isForeground && activeSessionKind == SignalMessage.SESSION_CHAT) {
            ChatNotifications.showMessage(
                getApplication(),
                _state.value.remoteName ?: activeRemoteId.orEmpty(),
                activeCallId,
            )
        }
    }

    override fun onError(message: String) = onMain { failCall(message, "WebRTC: $message") }

    override fun onWarning(message: String) = onMain {
        _state.update { it.copy(diagnostic = message) }
    }

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
        if (!message.type.startsWith("contact-")) {
            _state.update {
                it.copy(
                    safetyNumber = trust.safetyNumber,
                    peerIdentityVerified = trust.manuallyVerified,
                )
            }
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
            if (!TelecomCallBridge.registerIncoming(callId, contact?.name ?: from)) {
                _state.update { it.copy(diagnostic = "Android Telecom: system nie zarejestrował połączenia przychodzącego.") }
            }
            runCatching { CallNotifications.showIncoming(getApplication(), contact?.name ?: from, callId) }
                .onFailure { error -> _state.update { it.copy(diagnostic = "Powiadomienie przychodzące: ${error.javaClass.simpleName}.") } }
            startIncomingTimeout(message.callId)
        }
    }

    private fun handleFriendRequest(message: SignalMessage) {
        val from = message.from ?: return
        val requestId = message.callId ?: return
        val displayName = message.reason?.trim()?.take(80).orEmpty().ifBlank { from }
        if (_state.value.contacts.any { it.id == from }) {
            signalingClient.send(
                SignalMessage(
                    type = "contact-accept",
                    from = _state.value.myId,
                    to = from,
                    callId = requestId,
                    sessionKind = SignalMessage.SESSION_CONTACT,
                    reason = _state.value.displayName.take(80),
                )
            )
            return
        }
        preferences.saveFriendRequest(FriendRequest(requestId, from, displayName))
        _state.update {
            it.copy(
                friendRequests = preferences.friendRequests(),
                status = "Nowe zaproszenie od $displayName",
                diagnostic = null,
            )
        }
    }

    private fun handleFriendAccepted(message: SignalMessage) {
        val from = message.from ?: return
        val displayName = message.reason?.trim()?.take(80).orEmpty().ifBlank { from }
        addContact(displayName, from)
        _state.update { it.copy(status = "$displayName zaakceptował zaproszenie", diagnostic = null) }
    }

    private fun handleFriendRejected(message: SignalMessage) {
        val from = message.from ?: return
        _state.update { it.copy(status = "$from odrzucił zaproszenie", diagnostic = null) }
    }

    private fun failCall(message: String, diagnostic: String = "Połączenie: $message") {
        val callId = activeCallId
        val remoteId = activeRemoteId
        if (callId != null && remoteId != null) {
            signalingClient.send(
                SignalMessage(
                    type = "hangup",
                    from = _state.value.myId,
                    to = remoteId,
                    callId = callId,
                    sessionKind = activeSessionKind,
                    reason = diagnostic.take(80),
                )
            )
        }
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
                unreadChatCount = 0,
                safetyNumber = null,
                peerIdentityVerified = false,
                diagnostic = diagnostic,
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
                unreadChatCount = 0,
                safetyNumber = null,
                peerIdentityVerified = false,
            )
        }
    }

    private fun addChatMessage(message: EphemeralChatMessage) {
        _state.update { it.copy(chatMessages = (it.chatMessages + message).takeLast(MAX_CHAT_MESSAGES)) }
        message.openedAt?.let { scheduleMessageExpiry(message.id, it) }
    }

    private fun scheduleMessageExpiry(messageId: String, openedAt: Long) {
        viewModelScope.launch {
            val remaining = (openedAt + EphemeralChatPolicy.LIFETIME_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(remaining)
            _state.update { current ->
                current.copy(chatMessages = current.chatMessages.filterNot { it.id == messageId })
            }
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
                if (turnConfiguration == null) {
                    _state.update {
                        it.copy(diagnostic = TurnCredentialProvider.diagnostic() ?: "TURN: brak przekaźnika; próbuję połączenia bezpośredniego przez STUN.")
                    }
                }
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
