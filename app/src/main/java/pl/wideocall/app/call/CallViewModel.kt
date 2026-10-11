package pl.wideocall.app.call

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.util.Log
import androidx.core.telecom.CallEndpointCompat
import androidx.core.content.ContextCompat
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
import pl.wideocall.app.data.SentFriendRequest
import pl.wideocall.app.data.VideoQualityMode
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.notifications.ChatNotifications
import pl.wideocall.app.notifications.AppVisibility
import pl.wideocall.app.notifications.OngoingCallService
import pl.wideocall.app.notifications.FriendNotifications
import pl.wideocall.app.notifications.FileTransferNotifications
import pl.wideocall.app.notifications.DirectFileTransferService
import pl.wideocall.app.notifications.ChatConnectionService
import pl.wideocall.app.filetransfer.DirectFileTransferManager
import pl.wideocall.app.filetransfer.FileTransferState
import pl.wideocall.app.filetransfer.FileTransferStatus
import pl.wideocall.app.signaling.ContactPresence
import pl.wideocall.app.signaling.SignalMessage
import pl.wideocall.app.signaling.SignalingClient
import pl.wideocall.app.security.IdentityKeyManager
import pl.wideocall.app.security.PeerIdentityStatus
import pl.wideocall.app.security.PeerIdentityStore
import java.util.UUID
import pl.wideocall.app.WideocallApplication

enum class CallPhase {
    CONNECTING_SERVER,
    READY,
    SERVER_ERROR,
    OUTGOING,
    INCOMING,
    CONNECTING_CALL,
    IN_CALL,
    CONNECTING_CHAT,
    INCOMING_CHAT,
    IN_CHAT,
    CONNECTING_FILE,
    INCOMING_FILE,
    IN_FILE,
}

data class AppUiState(
    val myId: String,
    val displayName: String,
    val contacts: List<Contact>,
    val watchEnabled: Boolean = false,
    val videoQualityMode: VideoQualityMode = VideoQualityMode.AUTO,
    val startWithFrontCamera: Boolean = true,
    val contactPresence: Map<String, ContactPresence> = emptyMap(),
    val phase: CallPhase = CallPhase.CONNECTING_SERVER,
    val status: String = "Łączenie…",
    val remoteId: String? = null,
    val remoteName: String? = null,
    val callId: String? = null,
    val microphoneEnabled: Boolean = true,
    val audioRouteName: String = "Automatycznie",
    val speakerphoneEnabled: Boolean = false,
    val canSwitchAudioRoute: Boolean = false,
    val audioRouteChanging: Boolean = false,
    val frontCamera: Boolean = true,
    val audioOnlyCall: Boolean = false,
    val chatMessages: List<EphemeralChatMessage> = emptyList(),
    val chatReady: Boolean = false,
    val chatDraft: String = "",
    val chatSendError: String? = null,
    val chatPeerId: String? = null,
    val chatPeerName: String? = null,
    val chatSaving: Boolean = false,
    val signalingConnected: Boolean = false,
    val chatVisible: Boolean = false,
    val chatEndConfirmationId: String? = null,
    val safetyNumber: String? = null,
    val peerIdentityVerified: Boolean = false,
    val friendRequests: List<FriendRequest> = emptyList(),
    val sentFriendRequests: List<SentFriendRequest> = emptyList(),
    val callVisible: Boolean = true,
    val unreadChatCount: Int = 0,
    val diagnostic: String? = null,
    val fileTransfer: FileTransferState? = null,
    val fileVisible: Boolean = false,
)

data class EphemeralChatMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val createdAt: Long,
    val openedAt: Long? = null,
    val pending: Boolean = false,
)

class CallViewModel(application: Application) : AndroidViewModel(application),
    SignalingClient.Listener,
    WebRtcClient.Listener,
    TelecomCallBridge.Listener {

    private val preferences = AppPreferences(application)
    private val signalingClient = SignalingClient(application, this)
    private val webRtcClient = WebRtcClient(application, this)
    private val outgoingRingback = OutgoingRingbackPlayer(viewModelScope)
    private val peerIdentityStore = PeerIdentityStore(application)
    private val localChatStore = (application as WideocallApplication).localChatStore
    private var chatFlushJob: Job? = null
    private var persistentChatReady = false
    private val messageExpiryJobs = mutableMapOf<String, Job>()
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(
        AppUiState(
            myId = preferences.myId,
            displayName = preferences.displayName,
            contacts = preferences.contacts(),
            friendRequests = preferences.friendRequests(),
            sentFriendRequests = preferences.sentFriendRequests(),
            watchEnabled = preferences.watchEnabled,
            videoQualityMode = preferences.videoQualityMode,
            startWithFrontCamera = preferences.startWithFrontCamera,
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val fileTransferManager = DirectFileTransferManager(
        context = application,
        scope = viewModelScope,
        sendText = webRtcClient::sendChatMessage,
        sendBinary = webRtcClient::sendBinaryMessage,
        bufferedAmount = webRtcClient::dataChannelBufferedAmount,
        onState = { transfer ->
            _state.update {
                it.copy(
                    fileTransfer = transfer,
                    status = when (transfer.status) {
                        FileTransferStatus.WAITING_FOR_PEER -> "Oczekiwanie na drugi telefon…"
                        FileTransferStatus.WAITING_FOR_ACCEPTANCE -> "Czy chcesz odebrać ten plik?"
                        FileTransferStatus.TRANSFERRING -> if (transfer.direction.name == "SEND") "Wysyłanie pliku…" else "Odbieranie pliku…"
                        FileTransferStatus.VERIFYING -> "Sprawdzanie integralności pliku…"
                        FileTransferStatus.COMPLETE -> "Transfer pliku zakończony"
                        FileTransferStatus.CANCELLED -> "Transfer anulowany"
                        FileTransferStatus.FAILED -> "Transfer pliku nie powiódł się"
                        FileTransferStatus.IDLE -> it.status
                    },
                    diagnostic = transfer.error,
                )
            }
        },
    )

    private var activeCallId: String? = null
    private var activeRemoteId: String? = null
    private var activeSessionKind: String = SignalMessage.SESSION_VIDEO
    private var activeIsCaller = false
    private var iceConnected = false
    private var pendingOffer: SignalMessage? = null
    private val pendingIncomingCandidates = mutableListOf<IceCandidate>()
    private var callTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var signalingConnected = false
    private var defaultNetwork: Network? = null
    private var iceRecoveryJob: Job? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onMain {
            val changed = defaultNetwork != null && defaultNetwork != network
            defaultNetwork = network
            if (!signalingConnected || changed) {
                reconnectJob?.cancel()
                reconnectJob = null
                if (changed) {
                    signalingConnected = false
                    _state.update { SessionStatePolicy.refreshing(it, hasActiveSession()) }
                }
                signalingClient.connect(_state.value.myId, force = changed)
            }
            if (hasActiveSession() && activeIsCaller) {
                scheduleIceRecovery(NETWORK_CHANGE_RECOVERY_DELAY_MS)
            }
        }

        override fun onLost(network: Network) = onMain {
            if (defaultNetwork != network) return@onMain
            defaultNetwork = null
            signalingClient.disconnect()
            onDisconnected("Utracono połączenie z internetem")
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
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        signalingConnected = false
        _state.update { SessionStatePolicy.refreshing(it, hasActiveSession()) }
        signalingClient.connect(_state.value.myId, force = true)
    }

    fun refreshPreferences() {
        val backgroundIssue = preferences.consumeBackgroundIssue()
        _state.update {
            it.copy(
                displayName = preferences.displayName,
                watchEnabled = preferences.watchEnabled,
                videoQualityMode = preferences.videoQualityMode,
                startWithFrontCamera = preferences.startWithFrontCamera,
                friendRequests = preferences.friendRequests(),
                sentFriendRequests = preferences.sentFriendRequests(),
                diagnostic = backgroundIssue ?: it.diagnostic,
            )
        }
    }

    fun onAppVisible() {
        val current = _state.value
        if (current.chatVisible) current.chatPeerId?.let { refreshLocalChat(it, markOpened = true) }
        if (!signalingConnected) {
            // An FCM/full-screen notification may bring back an existing Activity whose
            // socket failed while it was asleep. Do not wait for a paused retry coroutine.
            reconnectJob?.cancel()
            reconnectJob = null
            _state.update { SessionStatePolicy.refreshing(it, hasActiveSession()) }
            signalingClient.connect(_state.value.myId)
            Log.i("WideocallSignal", "foreground_reconnect_requested activeSession=${hasActiveSession()}")
        }
    }

    fun saveSettings(
        displayName: String,
        videoQualityMode: VideoQualityMode,
        startWithFrontCamera: Boolean,
    ) {
        preferences.displayName = displayName
        // FCM wakes a closed app; a permanent Firebase listener is no longer used.
        preferences.watchEnabled = false
        preferences.videoQualityMode = videoQualityMode
        preferences.startWithFrontCamera = startWithFrontCamera
        _state.update {
            it.copy(
                displayName = preferences.displayName,
                watchEnabled = false,
                videoQualityMode = preferences.videoQualityMode,
                startWithFrontCamera = preferences.startWithFrontCamera,
                status = "Ustawienia zapisane",
                diagnostic = null,
            )
        }
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
        if (_state.value.contacts.any { it.id == normalizedId }) {
            _state.update { it.copy(status = "Ten telefon jest już w kontaktach", diagnostic = null) }
            return
        }
        if (_state.value.sentFriendRequests.any { it.toId == normalizedId }) {
            _state.update { it.copy(status = "Zaproszenie do $normalizedId już oczekuje", diagnostic = null) }
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
        if (sent) preferences.saveSentFriendRequest(SentFriendRequest(requestId, normalizedId, System.currentTimeMillis()))
        _state.update {
            it.copy(
                sentFriendRequests = preferences.sentFriendRequests(),
                status = if (sent) "Zaproszenie wysłane do $normalizedId" else "Nie udało się wysłać zaproszenia",
                diagnostic = if (sent) null else "Zaproszenie: brak aktywnego połączenia z Cloudflare.",
            )
        }
    }

    fun cancelFriendRequest(request: SentFriendRequest) {
        val current = _state.value.sentFriendRequests.firstOrNull {
            it.requestId == request.requestId && it.toId == request.toId
        } ?: return
        val sent = signalingClient.send(
            SignalMessage(
                type = "contact-cancel",
                from = _state.value.myId,
                to = current.toId,
                callId = current.requestId,
                sessionKind = SignalMessage.SESSION_CONTACT,
                reason = _state.value.displayName.take(80),
            )
        )
        if (!sent) {
            _state.update {
                it.copy(
                    status = "Nie udało się cofnąć zaproszenia",
                    diagnostic = "Zaproszenie: brak aktywnego połączenia z Cloudflare. Spróbuj ponownie.",
                )
            }
            return
        }
        preferences.removeSentFriendRequest(current.requestId)
        _state.update {
            it.copy(
                sentFriendRequests = preferences.sentFriendRequests(),
                status = "Zaproszenie do ${current.toId} cofnięte",
                diagnostic = null,
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

    fun renameContact(id: String, name: String): Result<Unit> = preferences.renameContact(id, name).map { renamed ->
        _state.update { current -> current.copy(
            contacts = preferences.contacts(),
            chatPeerName = if (current.chatPeerId == renamed.id) renamed.name else current.chatPeerName,
            remoteName = if (current.remoteId == renamed.id) renamed.name else current.remoteName,
        ) }
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
        releaseChatTransport()
        startOutgoingSession(contact, SignalMessage.SESSION_VIDEO)
    }

    fun audioCall(contact: Contact) {
        releaseChatTransport()
        startOutgoingSession(contact, SignalMessage.SESSION_AUDIO)
    }

    fun openContactChat(contact: Contact) {
        _state.update { it.copy(chatPeerId = contact.id, chatPeerName = contact.name, chatVisible = true, chatSendError = null,
            chatMessages = if (it.chatPeerId == contact.id) it.chatMessages else emptyList(),
            chatDraft = if (it.chatPeerId == contact.id) it.chatDraft else "") }
        refreshLocalChat(contact.id, markOpened = true)
        ChatNotifications.cancel(getApplication(), "local:${contact.id}")
        refreshChatDelivery()
        Log.i("WideocallChat", "window_open activeTransport=${activeCallId != null}")
    }

    fun refreshChatDelivery() {
        val peer = _state.value.chatPeerId ?: return
        refreshLocalChat(peer, markOpened = true)
        if (activeRemoteId == peer && activeSessionKind != SignalMessage.SESSION_FILE && _state.value.chatReady) startChatFlush()
    }

    fun connectChat(contact: Contact) {
        if (activeRemoteId == contact.id && activeSessionKind != SignalMessage.SESSION_FILE && activeCallId != null) {
            refreshChatDelivery()
            return
        }
        if (activeCallId != null) {
            _state.update { it.copy(chatSendError = "Inna sesja nadal działa w tle. Jej zakończenie wymaga potwierdzenia; wiadomość pozostaje w kolejce.") }
            return
        }
        if (activeCallId == null && signalingConnected && _state.value.phase == CallPhase.READY) {
            startOutgoingSession(contact, SignalMessage.SESSION_CHAT)
        } else {
            _state.update { it.copy(chatSendError = "Brak gotowego połączenia z usługą. Wiadomości pozostają lokalnie; spróbuj po przywróceniu sieci.") }
        }
    }

    fun requestEndChat() {
        val id = activeCallId ?: return
        if (activeSessionKind == SignalMessage.SESSION_CHAT && _state.value.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT)) {
            _state.update { it.copy(chatEndConfirmationId = id) }
        }
    }

    fun cancelEndChat() = _state.update { it.copy(chatEndConfirmationId = null) }

    fun confirmEndChat(expectedId: String) {
        if (_state.value.chatEndConfirmationId == expectedId && activeCallId == expectedId && activeSessionKind == SignalMessage.SESSION_CHAT) {
            Log.i("WideocallChat", "session_end_confirmed")
            hangUp()
        } else cancelEndChat()
    }

    fun startFileTransfer(contact: Contact, uri: Uri) {
        releaseChatTransport()
        if (_state.value.phase != CallPhase.READY) {
            _state.update { it.copy(diagnostic = "Transfer: aplikacja nie jest teraz gotowa do nowej sesji.") }
            return
        }
        val prepared = fileTransferManager.prepareOutgoing(uri)
        if (prepared.isFailure) {
            _state.update {
                it.copy(
                    status = "Nie można wysłać pliku",
                    diagnostic = prepared.exceptionOrNull()?.message ?: "Nieznany błąd wyboru pliku.",
                )
            }
            return
        }
        startOutgoingSession(contact, SignalMessage.SESSION_FILE)
    }

    private fun releaseChatTransport() {
        // Only a data-only session is replaceable. Local messages and the peer's window are retained.
        if (activeSessionKind == SignalMessage.SESSION_CHAT && activeCallId != null &&
            _state.value.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT)) hangUp()
    }

    private fun startOutgoingSession(contact: Contact, sessionKind: String) {
        if (_state.value.phase != CallPhase.READY || !signalingConnected || activeCallId != null) {
            _state.update { it.copy(diagnostic = "Sesja: brak gotowej sygnalizacji lub inna sesja nadal trwa.") }
            return
        }
        val callId = UUID.randomUUID().toString()
        activeCallId = callId
        activeRemoteId = contact.id
        activeSessionKind = sessionKind
        activeIsCaller = true
        iceConnected = false
        iceRecoveryJob?.cancel()
        val chatOnly = sessionKind == SignalMessage.SESSION_CHAT
        val fileOnly = sessionKind == SignalMessage.SESSION_FILE
        val dataOnly = chatOnly || fileOnly
        _state.update {
            val window = ChatWindowPolicy.outgoing(it, contact, chatOnly)
            it.copy(
                phase = when {
                    chatOnly -> CallPhase.CONNECTING_CHAT
                    fileOnly -> CallPhase.CONNECTING_FILE
                    else -> CallPhase.OUTGOING
                },
                status = when {
                    chatOnly -> "Oczekiwanie na otwarcie czatu…"
                    fileOnly -> "Oczekiwanie na odbiorcę pliku…"
                    else -> "Dzwonienie…"
                },
                remoteId = contact.id,
                remoteName = contact.name,
                callId = callId,
                microphoneEnabled = true,
                frontCamera = preferences.startWithFrontCamera,
                chatMessages = window.chatMessages,
                chatDraft = window.chatDraft,
                chatPeerId = window.chatPeerId,
                chatPeerName = window.chatPeerName,
                chatReady = false,
                // Starting a queued delivery must not reopen a window minimized while saving.
                chatVisible = window.chatVisible,
                fileVisible = fileOnly,
                callVisible = true,
                audioOnlyCall = sessionKind == SignalMessage.SESSION_AUDIO,
                unreadChatCount = 0,
                safetyNumber = null,
                peerIdentityVerified = false,
                diagnostic = null,
            )
        }
        signalingClient.setBusy(true)
        if (!fileOnly) refreshLocalChat(contact.id, markOpened = chatOnly)
        if (chatOnly) {
            runCatching { ChatConnectionService.start(getApplication()) }
                .onFailure { error -> _state.update { it.copy(diagnostic = "Czat w tle: ${error.javaClass.simpleName}.") } }
        }
        if (!dataOnly) {
            if (!TelecomCallBridge.registerOutgoing(callId, contact.id, video = sessionKind == SignalMessage.SESSION_VIDEO)) {
                _state.update { it.copy(diagnostic = "Android Telecom: system nie zarejestrował połączenia wychodzącego.") }
            }
            runCatching { OngoingCallService.start(getApplication(), contact.name, callId) }
                .onFailure { error -> _state.update { it.copy(diagnostic = "Powiadomienie rozmowy: ${error.javaClass.simpleName}.") } }
            if (!outgoingRingback.start()) {
                _state.update { it.copy(diagnostic = "Dźwięk dzwonienia: telefon nie udostępnił strumienia rozmowy.") }
            }
        }
        if (fileOnly) {
            runCatching { DirectFileTransferService.start(getApplication(), contact.name, callId) }
                .onFailure { error -> _state.update { it.copy(diagnostic = "Transfer w tle: ${error.javaClass.simpleName}.") } }
        }
        startSetupTimeout(callId, contact.id)
        val preparingAt = SystemClock.elapsedRealtime()
        Log.i("WideocallCall", "outgoing_prepare kind=$sessionKind")
        prepareWebRtcSession(callId, isCaller = true, withMedia = !dataOnly, withVideo = sessionKind == SignalMessage.SESSION_VIDEO) {
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
                Log.i("WideocallCall", "offer_queued kind=$sessionKind elapsedMs=${SystemClock.elapsedRealtime() - preparingAt} success=$sent")
                if (!sent) failCall("Nie udało się rozpocząć połączenia", "Sygnalizacja: oferta WebRTC nie została wysłana.")
            }
        }
    }

    fun acceptIncomingCall() {
        val offer = pendingOffer?.takeIf {
            _state.value.phase == CallPhase.INCOMING && it.sessionKind in setOf(SignalMessage.SESSION_VIDEO, SignalMessage.SESSION_AUDIO)
        } ?: return
        notifyCallAccepted(offer)
        TelecomCallBridge.answer(offer.callId ?: return)
        acceptOffer(offer, withMedia = true)
    }

    fun acceptIncomingCallFromTelecom() {
        val offer = pendingOffer?.takeIf {
            _state.value.phase == CallPhase.INCOMING && it.sessionKind in setOf(SignalMessage.SESSION_VIDEO, SignalMessage.SESSION_AUDIO)
        } ?: return
        notifyCallAccepted(offer)
        acceptOffer(offer, withMedia = true)
    }

    fun acceptIncomingChat(sessionId: String) {
        val offer = pendingOffer?.takeIf {
            it.callId == sessionId && it.sessionKind == SignalMessage.SESSION_CHAT
        } ?: return
        notifyCallAccepted(offer)
        _state.update { it.copy(chatVisible = true) }
        acceptOffer(offer, withMedia = false)
    }

    fun acceptIncomingFile(sessionId: String) {
        val offer = pendingOffer?.takeIf {
            it.callId == sessionId && it.sessionKind == SignalMessage.SESSION_FILE
        } ?: return
        notifyCallAccepted(offer)
        runCatching { DirectFileTransferService.start(getApplication(), _state.value.remoteName ?: offer.from.orEmpty(), sessionId) }
            .onFailure { error -> _state.update { it.copy(diagnostic = "Transfer w tle: ${error.javaClass.simpleName}.") } }
        _state.update { it.copy(fileVisible = true) }
        acceptOffer(offer, withMedia = false)
    }

    fun acceptReceivedFile() {
        if (activeSessionKind == SignalMessage.SESSION_FILE && activeCallId != null) fileTransferManager.acceptReceivedFile()
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
        val chatOnly = offer.sessionKind == SignalMessage.SESSION_CHAT
        val fileOnly = offer.sessionKind == SignalMessage.SESSION_FILE
        if (chatOnly) {
            runCatching { ChatConnectionService.start(getApplication()) }
                .onFailure { error -> _state.update { it.copy(diagnostic = "Czat w tle: ${error.javaClass.simpleName}.") } }
        }
        _state.update {
            it.copy(
                phase = when {
                    withMedia -> CallPhase.CONNECTING_CALL
                    fileOnly -> CallPhase.CONNECTING_FILE
                    else -> CallPhase.CONNECTING_CHAT
                },
                status = when {
                    withMedia -> "Łączenie…"
                    fileOnly -> "Łączenie prywatnego transferu…"
                    else -> "Łączenie szyfrowanego czatu…"
                },
                chatVisible = chatOnly,
                fileVisible = fileOnly,
            )
        }
        startSetupTimeout(callId, remoteId)
        if (!fileOnly) {
            _state.update { it.copy(chatPeerId = remoteId, chatPeerName = it.remoteName ?: remoteId,
                chatMessages = if (it.chatPeerId == remoteId) it.chatMessages else emptyList(),
                chatDraft = if (it.chatPeerId == remoteId) it.chatDraft else "") }
            refreshLocalChat(remoteId, markOpened = chatOnly)
        }
        CallNotifications.cancel(getApplication(), offer.callId)
        ChatNotifications.cancel(getApplication(), offer.callId)
        FileTransferNotifications.cancel(getApplication(), offer.callId)
        prepareWebRtcSession(callId, isCaller = false, withMedia = withMedia, withVideo = offer.sessionKind == SignalMessage.SESSION_VIDEO) {
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

    fun rejectIncomingDataSession() = rejectIncomingCall(notifyTelecom = false)

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
        FileTransferNotifications.cancel(getApplication(), offer?.callId)
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
        if (activeSessionKind == SignalMessage.SESSION_FILE && _state.value.fileTransfer?.status != FileTransferStatus.COMPLETE) {
            fileTransferManager.cancel()
        }
        clearCall(
            when (activeSessionKind) {
                SignalMessage.SESSION_CHAT -> "Czat zakończony"
                SignalMessage.SESSION_FILE -> "Transfer zakończony"
                else -> "Rozmowa zakończona"
            },
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

    fun toggleSpeaker() {
        if (_state.value.audioRouteChanging) return
        if (!TelecomCallBridge.toggleSpeaker(activeCallId)) {
            _state.update { it.copy(diagnostic = "Dźwięk: brak innego dostępnego wyjścia audio.") }
        }
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
        Log.i("WideocallChat", "window_minimized activeTransport=${activeCallId != null}")
        // Minimizing is silent; only an invitation or an actual unread message warrants a notification.
        if (activeSessionKind == SignalMessage.SESSION_CHAT) ChatNotifications.cancel(getApplication(), activeCallId)
    }

    fun hideFileTransfer() {
        if (_state.value.phase in setOf(CallPhase.INCOMING_FILE, CallPhase.CONNECTING_FILE, CallPhase.IN_FILE) ||
            (_state.value.phase == CallPhase.READY && _state.value.fileTransfer != null)
        ) {
            _state.update { it.copy(fileVisible = false) }
            // The foreground transfer service keeps the encrypted stream alive while this screen is hidden.
        }
    }

    fun openFileTransfer() {
        if (_state.value.phase in setOf(CallPhase.INCOMING_FILE, CallPhase.CONNECTING_FILE, CallPhase.IN_FILE) ||
            (_state.value.phase == CallPhase.READY && _state.value.fileTransfer != null)
        ) {
            _state.update { it.copy(fileVisible = true) }
            FileTransferNotifications.cancel(getApplication(), activeCallId)
        }
    }

    fun saveReceivedFile(destination: Uri) {
        fileTransferManager.copyReceivedTo(destination) { result ->
            onMain {
                _state.update {
                    it.copy(
                        status = if (result.isSuccess) "Plik zapisany na telefonie" else "Nie udało się zapisać pliku",
                        diagnostic = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    fun hideCallScreen() {
        if (_state.value.phase in setOf(CallPhase.OUTGOING, CallPhase.INCOMING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL)) {
            _state.update { it.copy(callVisible = false) }
        }
    }

    fun openCallScreen() {
        if (_state.value.phase in setOf(CallPhase.OUTGOING, CallPhase.INCOMING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL)) {
            _state.update { it.copy(callVisible = true) }
        }
    }

    fun openChat() {
        val peer = activeRemoteId ?: _state.value.chatPeerId ?: return
        openLocalChat(peer)
        ChatNotifications.cancel(getApplication(), activeCallId)
        ChatNotifications.cancel(getApplication(), "local:$peer")
        FileTransferNotifications.cancel(getApplication(), activeCallId)
    }

    fun openLocalChat(peer: String) {
        if (!peer.matches(Regex("[A-Z0-9]{4}-[A-Z0-9]{4}"))) return
        _state.update { it.copy(chatVisible = true, chatPeerId = peer,
            chatPeerName = it.contacts.firstOrNull { contact -> contact.id == peer }?.name ?: peer,
            chatMessages = if (it.chatPeerId == peer) it.chatMessages else emptyList(),
            chatDraft = if (it.chatPeerId == peer) it.chatDraft else "") }
        refreshLocalChat(peer, markOpened = true)
        ChatNotifications.cancel(getApplication(), "local:$peer")
        refreshChatDelivery()
    }

    fun setChatDraft(raw: String) {
        _state.update { it.copy(chatDraft = raw.take(1_000), chatSendError = null) }
    }

    fun sendChatMessage(raw: String): Boolean {
        val current = _state.value
        val peer = current.chatPeerId ?: activeRemoteId ?: return false
        val text = EphemeralChatPolicy.sanitize(raw)
        if (text.isBlank() || current.chatSaving) return false
        _state.update { it.copy(chatSaving = true, chatSendError = null) }
        viewModelScope.launch {
            try {
                localChatStore.add(peer, EphemeralChatMessage(UUID.randomUUID().toString(), text, true,
                    System.currentTimeMillis(), pending = true))
                Log.i("WideocallChat", "queue_saved hidden=${!_state.value.chatVisible}")
                _state.update { it.copy(chatDraft = if (it.chatPeerId == peer && it.chatDraft == raw) "" else it.chatDraft, chatSaving = false) }
                refreshLocalChat(peer)
                if (activeCallId == null && signalingConnected && _state.value.phase == CallPhase.READY) {
                    connectChat(Contact(peer, current.chatPeerName ?: peer))
                } else if (activeRemoteId == peer && _state.value.chatReady) {
                    startChatFlush()
                }
            } catch (error: Exception) {
                _state.update { it.copy(chatSaving = false, chatSendError = "Nie zapisano wiadomości: ${error.javaClass.simpleName}. Tekst zachowano.") }
            }
        }
        return true
    }

    private fun refreshLocalChat(peer: String, markOpened: Boolean = false) {
        viewModelScope.launch {
            try {
                if (markOpened && _state.value.chatPeerId == peer && _state.value.chatVisible && AppVisibility.isForeground)
                    localChatStore.opened(peer, System.currentTimeMillis())
                val messages = localChatStore.read(peer)
                if (_state.value.chatPeerId == peer) _state.update { it.copy(chatMessages = messages,
                    unreadChatCount = messages.count { message -> !message.mine && message.openedAt == null }) }
                messages.forEach { message -> message.openedAt?.let { if (!message.pending) scheduleMessageExpiry(peer, message.id, it) } }
            } catch (error: Exception) {
                _state.update { it.copy(chatSendError = "Nie można otworzyć lokalnej skrzynki: ${error.javaClass.simpleName}. Nie usunięto danych.") }
            }
        }
    }

    fun bindLocalRenderer(renderer: SurfaceViewRenderer?) = webRtcClient.bindLocalRenderer(renderer)
    fun bindRemoteRenderer(renderer: SurfaceViewRenderer?) = webRtcClient.bindRemoteRenderer(renderer)
    fun eglContext() = webRtcClient.eglContext

    override fun onConnected() = onMain {
        signalingConnected = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        TurnCredentialProvider.prefetch(_state.value.myId)
        signalingClient.observeContacts(_state.value.contacts.map(Contact::id))
        _state.update { SessionStatePolicy.signalingReady(it, hasActiveSession(), iceConnected, activeSessionKind) }
    }

    override fun onDisconnected(reason: String) = onMain {
        signalingConnected = false
        _state.update { SessionStatePolicy.signalingLost(it, hasActiveSession(), reason) }
        scheduleReconnect()
    }

    override fun onMessage(message: SignalMessage) = onMain {
        if (!authenticateMessage(message)) return@onMain
        when (message.type) {
            "contact-request" -> handleFriendRequest(message)
            "contact-accept" -> handleFriendAccepted(message)
            "contact-reject" -> handleFriendRejected(message)
            "contact-cancel" -> handleFriendCancelled(message)
            "offer" -> handleOffer(message)
            "accepted" -> if (isActiveMessage(message) && _state.value.phase == CallPhase.OUTGOING) {
                outgoingRingback.stop()
                _state.update { it.copy(status = "Odebrano • zestawianie połączenia…", diagnostic = null) }
            }
            "answer" -> if (isActiveMessage(message) && message.sdp != null) {
                outgoingRingback.stop()
                val chatOnly = activeSessionKind == SignalMessage.SESSION_CHAT
                val fileOnly = activeSessionKind == SignalMessage.SESSION_FILE
                _state.update {
                    it.copy(
                        phase = when {
                            chatOnly -> CallPhase.CONNECTING_CHAT
                            fileOnly -> CallPhase.CONNECTING_FILE
                            else -> CallPhase.CONNECTING_CALL
                        },
                        status = when {
                            chatOnly -> "Łączenie szyfrowanego czatu…"
                            fileOnly -> "Łączenie prywatnego transferu…"
                            else -> "Łączenie…"
                        },
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
                if (_state.value.phase in setOf(CallPhase.INCOMING, CallPhase.INCOMING_CHAT, CallPhase.INCOMING_FILE)) pendingIncomingCandidates += candidate
                else webRtcClient.addRemoteIceCandidate(candidate)
            }
            "hangup" -> if (isActiveMessage(message)) {
                clearCall(
                    when (activeSessionKind) {
                        SignalMessage.SESSION_CHAT -> "Rozmówca zakończył czat"
                        SignalMessage.SESSION_FILE -> "Rozmówca zakończył transfer"
                        else -> "Rozmówca zakończył rozmowę"
                    },
                    TelecomEnd.REMOTE,
                )
            }
            "reject" -> if (isActiveMessage(message)) {
                clearCall(
                    when (activeSessionKind) {
                        SignalMessage.SESSION_CHAT -> "Czat odrzucony"
                        SignalMessage.SESSION_FILE -> "Transfer odrzucony"
                        else -> "Połączenie odrzucone"
                    },
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
        if (!hasActiveSession()) return@onMain
        if (state == RtcConnectionState.CONNECTED) {
            outgoingRingback.stop()
            iceConnected = true
            iceRecoveryJob?.cancel()
            iceRecoveryJob = null
            val chatOnly = activeSessionKind == SignalMessage.SESSION_CHAT
            val fileOnly = activeSessionKind == SignalMessage.SESSION_FILE
            _state.update { SessionStatePolicy.transportConnected(it, activeSessionKind) }
            callTimeoutJob?.cancel()
            if (!chatOnly && !fileOnly) {
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
        if (!hasActiveSession()) return@onMain
        _state.update { it.copy(chatReady = ready) }
        if (activeSessionKind == SignalMessage.SESSION_FILE) {
            if (ready) fileTransferManager.onChannelReady()
        } else if (ready) startChatFlush() else {
            persistentChatReady = false
            chatFlushJob?.cancel()
            chatFlushJob = null
        }
    }

    override fun onChatMessage(message: String) = onMain {
        if (!hasActiveSession()) return@onMain
        if (activeSessionKind == SignalMessage.SESSION_FILE && fileTransferManager.onText(message)) return@onMain
        if (activeSessionKind == SignalMessage.SESSION_FILE || message.length > 8_192) return@onMain
        val peer = activeRemoteId ?: return@onMain
        val session = activeCallId ?: return@onMain
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return@onMain
        when (json.optString("type")) {
            "chat-hello", "chat-hello-ack" -> {
                if (json.optInt("version") != 2) {
                    _state.update { it.copy(chatSendError = "Drugi telefon używa niezgodnej wersji czatu. Zaktualizuj Wideocall na obu telefonach.") }
                    Log.w("WideocallChat", "handshake_incompatible api=${Build.VERSION.SDK_INT}")
                    return@onMain
                }
                Log.i("WideocallChat", "handshake_ready api=${Build.VERSION.SDK_INT}")
                persistentChatReady = true
                if (json.optString("type") == "chat-hello") webRtcClient.sendChatMessage(
                    JSONObject().put("type", "chat-hello-ack").put("version", 2).toString())
                _state.update { it.copy(chatSendError = null) }
                startChatFlush()
            }
            "chat-ack" -> {
                val id = json.optString("id")
                if (!validChatMessageId(id)) return@onMain
                viewModelScope.launch {
                    try {
                        localChatStore.acknowledged(peer, id, System.currentTimeMillis().takeIf {
                            _state.value.chatVisible && _state.value.chatPeerId == peer && AppVisibility.isForeground
                        })
                        Log.i("WideocallChat", "delivery_ack hidden=${!_state.value.chatVisible}")
                        refreshLocalChat(peer)
                    } catch (error: Exception) { chatStorageError(error) }
                }
            }
            "chat-message", "" -> {
                val modern = json.optString("type") == "chat-message"
                val id = if (modern) json.optString("id") else UUID.randomUUID().toString()
                val text = EphemeralChatPolicy.sanitize(json.optString("text"))
                if (!validChatMessageId(id) || text.isBlank()) return@onMain
                viewModelScope.launch {
                    try {
                        val now = System.currentTimeMillis()
                        val visible = _state.value.chatVisible && _state.value.chatPeerId == peer && AppVisibility.isForeground
                        val added = localChatStore.add(peer, EphemeralChatMessage(id, text, false, now, now.takeIf { visible }))
                        Log.i("WideocallChat", "message_received hidden=${!visible} duplicate=${!added}")
                        // Acknowledge only after the atomic encrypted write succeeds, including duplicate deliveries.
                        if (modern && activeCallId == session && activeRemoteId == peer) webRtcClient.sendChatMessage(
                            JSONObject().put("type", "chat-ack").put("id", id).toString())
                        refreshLocalChat(peer)
                        if (added && !visible) runCatching {
                            ChatNotifications.showMessage(getApplication(), preferences.contacts().firstOrNull { it.id == peer }?.name ?: peer, peer)
                        }.onFailure { chatStorageError(it) }
                    } catch (error: Exception) { chatStorageError(error) }
                }
            }
        }
    }

    private fun validChatMessageId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun chatStorageError(error: Throwable) {
        Log.w("WideocallChat", "storage_failed api=${Build.VERSION.SDK_INT} error=${error.javaClass.simpleName}")
        _state.update { it.copy(chatSendError = "Błąd lokalnego czatu: ${error.javaClass.simpleName}. Wiadomości nie potwierdzono; nadawca może ponowić wysyłkę.") }
    }

    private fun startChatFlush() {
        if (chatFlushJob?.isActive == true || !_state.value.chatReady) return
        val peer = activeRemoteId ?: return
        val session = activeCallId ?: return
        chatFlushJob = viewModelScope.launch {
            var handshakeAttempts = 0
            while (activeCallId == session && activeRemoteId == peer && _state.value.chatReady) {
                try {
                    if (!persistentChatReady) {
                        webRtcClient.sendChatMessage(JSONObject().put("type", "chat-hello").put("version", 2).toString())
                        if (++handshakeAttempts == 3) {
                            Log.w("WideocallChat", "handshake_timeout api=${Build.VERSION.SDK_INT}")
                            _state.update { it.copy(chatSendError = "Brak potwierdzenia obsługi kolejki. Zaktualizuj aplikację na obu telefonach. Wiadomości nadal czekają lokalnie.") }
                        }
                    } else {
                        val pending = localChatStore.read(peer).filter { it.pending }.take(100)
                        if (pending.isNotEmpty()) Log.i("WideocallChat", "queue_retry count=${pending.size} hidden=${!_state.value.chatVisible}")
                        for (entry in pending) {
                            if (activeCallId != session || !_state.value.chatReady) break
                            if (!webRtcClient.sendChatMessage(JSONObject().put("type", "chat-message").put("id", entry.id).put("text", entry.text).toString())) break
                        }
                    }
                } catch (error: Exception) { chatStorageError(error) }
                delay(3_000)
            }
        }
    }

    fun discardPendingChat() {
        val peer = _state.value.chatPeerId ?: return
        viewModelScope.launch {
            try { localChatStore.discardPending(peer); refreshLocalChat(peer) }
            catch (error: Exception) { chatStorageError(error) }
        }
    }

    override fun onBinaryMessage(bytes: ByteArray) {
        if (activeSessionKind == SignalMessage.SESSION_FILE) fileTransferManager.onBinary(bytes)
    }

    override fun onError(message: String) = onMain { failCall(message, "WebRTC: $message") }

    override fun onWarning(message: String) = onMain {
        _state.update { it.copy(diagnostic = message) }
    }

    fun markCurrentPeerVerified(expectedCallId: String?, expectedNumber: String): Boolean {
        val remoteId = activeRemoteId ?: return false
        if (activeCallId != expectedCallId || _state.value.safetyNumber != expectedNumber) return false
        val verified = runCatching { peerIdentityStore.markVerified(remoteId) }.getOrDefault(false)
        if (!verified) {
            _state.update { it.copy(diagnostic = "Nie udało się zapisać potwierdzenia tożsamości.") }
            return false
        }
        _state.update { it.copy(peerIdentityVerified = true) }
        return true
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
        // A second caller or a delayed signal must not replace the active peer's verification number.
        if (!message.type.startsWith("contact-") &&
            (isActiveMessage(message) || (message.type == "offer" && activeCallId == null))
        ) {
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
        val application = getApplication<Application>()
        val hasMediaPermissions = ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        // Returning false lets Telecom open MainActivity, where Android can display the
        // runtime permission dialogs and continue the pending answer after approval.
        if (!hasMediaPermissions) return false
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

    override fun onTelecomAudioEndpointChanged(
        callId: String,
        current: CallEndpointCompat?,
        available: List<CallEndpointCompat>,
    ) = onMain {
        if (activeCallId != callId) return@onMain
        _state.update {
            it.copy(
                audioRouteName = current?.name?.toString().orEmpty().ifBlank { audioEndpointLabel(current?.type) },
                speakerphoneEnabled = current?.type == CallEndpointCompat.TYPE_SPEAKER,
                canSwitchAudioRoute = available.map { endpoint -> endpoint.type }.distinct().size > 1,
            )
        }
    }

    override fun onTelecomAudioRoutePending(callId: String, pending: Boolean) = onMain {
        if (activeCallId == callId) _state.update { it.copy(audioRouteChanging = pending) }
    }

    override fun onTelecomError(callId: String, message: String) = onMain {
        if (activeCallId == callId) _state.update { it.copy(diagnostic = message, audioRouteChanging = false) }
    }

    private fun handleOffer(message: SignalMessage) {
        val from = message.from ?: return
        val callId = message.callId ?: return
        if (message.sessionKind in setOf(SignalMessage.SESSION_VIDEO, SignalMessage.SESSION_AUDIO)) releaseChatTransport()
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
        val fileOnly = message.sessionKind == SignalMessage.SESSION_FILE
        _state.update {
            it.copy(
                phase = when {
                    chatOnly -> CallPhase.INCOMING_CHAT
                    fileOnly -> CallPhase.INCOMING_FILE
                    else -> CallPhase.INCOMING
                },
                status = when {
                    chatOnly -> "Nowe zaproszenie do szyfrowanego czatu"
                    fileOnly -> "Nowa prośba o bezpośredni transfer pliku"
                    else -> "Połączenie przychodzące"
                },
                remoteId = from,
                remoteName = contact?.name ?: from,
                callId = message.callId,
                chatMessages = if (it.chatPeerId == from) it.chatMessages else emptyList(),
                chatReady = false,
                chatVisible = false,
                fileVisible = false,
                callVisible = true,
                audioOnlyCall = message.sessionKind == SignalMessage.SESSION_AUDIO,
            )
        }
        signalingClient.setBusy(true)
        if (chatOnly) {
            ChatNotifications.showInvitation(getApplication(), contact?.name ?: from, callId)
            startIncomingTimeout(message.callId)
        } else if (fileOnly) {
            FileTransferNotifications.showInvitation(getApplication(), contact?.name ?: from, callId)
            startIncomingTimeout(message.callId)
        } else {
            if (!TelecomCallBridge.registerIncoming(callId, contact?.name ?: from, video = message.sessionKind == SignalMessage.SESSION_VIDEO)) {
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
        val requestId = message.callId ?: return
        val displayName = message.reason?.trim()?.take(80).orEmpty().ifBlank { from }
        preferences.removeSentFriendRequest(requestId)
        addContact(displayName, from)
        _state.update {
            it.copy(
                sentFriendRequests = preferences.sentFriendRequests(),
                status = "$displayName zaakceptował zaproszenie",
                diagnostic = null,
            )
        }
    }

    private fun handleFriendRejected(message: SignalMessage) {
        val from = message.from ?: return
        val requestId = message.callId ?: return
        preferences.removeSentFriendRequest(requestId)
        _state.update {
            it.copy(
                sentFriendRequests = preferences.sentFriendRequests(),
                status = "$from odrzucił zaproszenie",
                diagnostic = null,
            )
        }
    }

    private fun handleFriendCancelled(message: SignalMessage) {
        val from = message.from ?: return
        val requestId = message.callId ?: return
        val request = _state.value.friendRequests.firstOrNull {
            it.requestId == requestId && it.fromId == from
        } ?: return
        preferences.removeFriendRequest(request.requestId)
        FriendNotifications.cancel(getApplication(), request.requestId)
        _state.update {
            it.copy(
                friendRequests = preferences.friendRequests(),
                status = "${request.displayName} cofnął zaproszenie",
                diagnostic = null,
            )
        }
    }

    private fun failCall(message: String, diagnostic: String = "Połączenie: $message") {
        chatFlushJob?.cancel()
        chatFlushJob = null
        persistentChatReady = false
        outgoingRingback.stop()
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
        FileTransferNotifications.cancel(getApplication(), activeCallId)
        OngoingCallService.stop(getApplication())
        DirectFileTransferService.stop(getApplication())
        ChatConnectionService.stop(getApplication())
        webRtcClient.endCall()
        fileTransferManager.reset(deleteCompleted = true)
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
                phase = SessionStatePolicy.idlePhase(signalingConnected),
                status = message,
                remoteId = null,
                remoteName = null,
                callId = null,
                chatReady = false,
                callVisible = true,
                chatEndConfirmationId = null,
                safetyNumber = null,
                peerIdentityVerified = false,
                diagnostic = diagnostic,
                fileTransfer = null,
                fileVisible = false,
            )
        }
        if (!signalingConnected) scheduleReconnect()
    }

    private fun clearCall(status: String, telecomEnd: TelecomEnd = TelecomEnd.LOCAL) {
        chatFlushJob?.cancel()
        chatFlushJob = null
        persistentChatReady = false
        outgoingRingback.stop()
        val callId = activeCallId
        val completedTransfer = _state.value.fileTransfer?.takeIf { it.status == FileTransferStatus.COMPLETE }
        callTimeoutJob?.cancel()
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        signalingClient.setBusy(false)
        CallNotifications.cancel(getApplication(), activeCallId)
        ChatNotifications.cancel(getApplication(), activeCallId)
        FileTransferNotifications.cancel(getApplication(), activeCallId)
        OngoingCallService.stop(getApplication())
        DirectFileTransferService.stop(getApplication())
        ChatConnectionService.stop(getApplication())
        webRtcClient.endCall()
        fileTransferManager.reset(deleteCompleted = completedTransfer == null)
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
                phase = SessionStatePolicy.idlePhase(signalingConnected),
                status = if (signalingConnected) status else "$status • odnawiam sygnalizację…",
                remoteId = null,
                remoteName = null,
                callId = null,
                microphoneEnabled = true,
                audioRouteName = "Automatycznie",
                speakerphoneEnabled = false,
                canSwitchAudioRoute = false,
                audioRouteChanging = false,
                frontCamera = true,
                audioOnlyCall = false,
                chatReady = false,
                callVisible = true,
                chatEndConfirmationId = null,
                safetyNumber = null,
                peerIdentityVerified = false,
                fileTransfer = completedTransfer,
                fileVisible = false,
            )
        }
        if (!signalingConnected) scheduleReconnect()
    }

    private fun scheduleMessageExpiry(peer: String, messageId: String, openedAt: Long) {
        val key = "$peer/$messageId"
        if (messageExpiryJobs[key]?.isActive == true) return
        messageExpiryJobs[key] = viewModelScope.launch {
            val remaining = (openedAt + EphemeralChatPolicy.LIFETIME_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(remaining)
            try { localChatStore.prune(); refreshLocalChat(peer) }
            catch (error: Exception) { chatStorageError(error) }
            finally { messageExpiryJobs.remove(key) }
        }
    }

    private fun startSetupTimeout(callId: String, remoteId: String) {
        callTimeoutJob?.cancel()
        callTimeoutJob = viewModelScope.launch {
            delay(CALL_TIMEOUT_MS)
            if (SessionStatePolicy.shouldExpireSetup(callId, activeCallId, _state.value.phase)) {
                val wasRinging = _state.value.phase == CallPhase.OUTGOING
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
                    when (activeSessionKind) {
                        SignalMessage.SESSION_CHAT -> "Nie udało się połączyć czatu"
                        SignalMessage.SESSION_FILE -> "Nie udało się rozpocząć transferu"
                        else -> if (wasRinging) "Nie odebrano" else "Nie udało się zestawić rozmowy"
                    },
                    if (activeSessionKind in setOf(SignalMessage.SESSION_VIDEO, SignalMessage.SESSION_AUDIO)) TelecomEnd.MISSED else TelecomEnd.NONE,
                )
                _state.update { it.copy(diagnostic = "WebRTC: przekroczono limit ${CALL_TIMEOUT_MS / 1_000} sekund na zestawienie sesji. Sprawdź sieć obu telefonów.") }
            }
        }
    }

    private fun startIncomingTimeout(callId: String?) {
        if (callId == null) return
        callTimeoutJob?.cancel()
        callTimeoutJob = viewModelScope.launch {
            delay(CALL_TIMEOUT_MS)
            if (activeCallId == callId && _state.value.phase in setOf(CallPhase.INCOMING, CallPhase.INCOMING_CHAT, CallPhase.INCOMING_FILE)) {
                CallNotifications.cancel(getApplication(), callId)
                ChatNotifications.cancel(getApplication(), callId)
                FileTransferNotifications.cancel(getApplication(), callId)
                clearCall(
                    when (activeSessionKind) {
                        SignalMessage.SESSION_CHAT -> "Zaproszenie do czatu wygasło"
                        SignalMessage.SESSION_FILE -> "Prośba o transfer wygasła"
                        else -> "Nie odebrano"
                    },
                    if (activeSessionKind in setOf(SignalMessage.SESSION_VIDEO, SignalMessage.SESSION_AUDIO)) TelecomEnd.MISSED else TelecomEnd.NONE,
                )
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            val delayMs = RECONNECT_DELAYS_MS[reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
            delay(delayMs)
            reconnectJob = null
            if (!signalingConnected) {
                val network = connectivityManager.activeNetwork
                if (network == null || connectivityManager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                    _state.update { it.copy(diagnostic = "Sieć: brak połączenia z internetem. Czekam na powrót sieci.") }
                    return@launch
                }
                // Re-evaluate actual network readiness after the delay, even if the session ended.
                _state.update { SessionStatePolicy.refreshing(it, hasActiveSession()) }
                signalingClient.connect(_state.value.myId)
            }
        }
    }

    private fun prepareWebRtcSession(
        callId: String,
        isCaller: Boolean,
        withMedia: Boolean,
        withVideo: Boolean,
        onPrepared: () -> Unit,
    ) {
        val requestedAt = SystemClock.elapsedRealtime()
        TurnCredentialProvider.get(_state.value.myId) { turnConfiguration ->
            onMain session@{
                if (activeCallId != callId) return@session
                Log.i("WideocallCall", "turn_ready elapsedMs=${SystemClock.elapsedRealtime() - requestedAt} available=${turnConfiguration != null}")
                if (turnConfiguration == null) {
                    _state.update {
                        it.copy(diagnostic = TurnCredentialProvider.diagnostic() ?: "TURN: brak przekaźnika; próbuję połączenia bezpośredniego przez STUN.")
                    }
                }
                runCatching {
                    webRtcClient.prepareSession(isCaller, withMedia, withVideo, turnConfiguration)
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

    private fun hasActiveSession(): Boolean = activeCallId != null

    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun audioEndpointLabel(type: Int?): String = when (type) {
        CallEndpointCompat.TYPE_SPEAKER -> "Głośnik"
        CallEndpointCompat.TYPE_EARPIECE -> "Telefon"
        CallEndpointCompat.TYPE_BLUETOOTH -> "Bluetooth"
        CallEndpointCompat.TYPE_WIRED_HEADSET -> "Słuchawki"
        CallEndpointCompat.TYPE_STREAMING -> "Urządzenie zewnętrzne"
        else -> "Automatycznie"
    }

    override fun onCleared() {
        outgoingRingback.stop()
        callTimeoutJob?.cancel()
        reconnectJob?.cancel()
        iceRecoveryJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        OngoingCallService.stop(getApplication())
        DirectFileTransferService.stop(getApplication())
        ChatConnectionService.stop(getApplication())
        TelecomCallBridge.disconnectLocal(activeCallId)
        signalingClient.close()
        fileTransferManager.reset(deleteCompleted = true)
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
        private val RECONNECT_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 10_000L)
        private const val NETWORK_CHANGE_RECOVERY_DELAY_MS = 1_500L
        private const val ICE_DISCONNECTED_GRACE_MS = 4_000L
        private const val ICE_RESTART_RETRY_MS = 8_000L
        private const val ICE_REMOTE_RECOVERY_TIMEOUT_MS = 30_000L
        private const val MAX_ICE_RESTART_ATTEMPTS = 3
        private const val MAX_CHAT_MESSAGES = 100
    }
}
