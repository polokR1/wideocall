package pl.wideocall.app.call

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.data.Contact
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
}

data class AppUiState(
    val myId: String,
    val displayName: String,
    val contacts: List<Contact>,
    val phase: CallPhase = CallPhase.CONNECTING_SERVER,
    val status: String = "Łączenie…",
    val remoteId: String? = null,
    val remoteName: String? = null,
    val microphoneEnabled: Boolean = true,
    val frontCamera: Boolean = true,
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
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var activeCallId: String? = null
    private var activeRemoteId: String? = null
    private var pendingOffer: SignalMessage? = null
    private val pendingIncomingCandidates = mutableListOf<IceCandidate>()

    init {
        connect()
    }

    fun connect() {
        _state.update { it.copy(phase = CallPhase.CONNECTING_SERVER, status = "Łączenie z Firebase…") }
        signalingClient.connect(_state.value.myId)
    }

    fun saveSettings(displayName: String) {
        preferences.displayName = displayName
        _state.update { it.copy(displayName = preferences.displayName) }
    }

    fun addContact(name: String, id: String) {
        val normalizedId = AppPreferences.normalizeId(id)
        if (normalizedId.length != 9 || normalizedId == _state.value.myId) return
        preferences.saveContact(Contact(normalizedId, name))
        _state.update { it.copy(contacts = preferences.contacts()) }
    }

    fun removeContact(id: String) {
        preferences.removeContact(id)
        _state.update { it.copy(contacts = preferences.contacts()) }
    }

    fun call(contact: Contact) {
        if (_state.value.phase != CallPhase.READY) return
        val callId = UUID.randomUUID().toString()
        activeCallId = callId
        activeRemoteId = contact.id
        _state.update {
            it.copy(
                phase = CallPhase.OUTGOING,
                status = "Dzwonienie…",
                remoteId = contact.id,
                remoteName = contact.name,
                microphoneEnabled = true,
                frontCamera = true,
            )
        }
        runCatching {
            webRtcClient.prepareCall()
            webRtcClient.createOffer { offer ->
                signalingClient.send(
                    SignalMessage(
                        type = "offer",
                        from = _state.value.myId,
                        to = contact.id,
                        callId = callId,
                        sdpType = offer.type.canonicalForm(),
                        sdp = offer.description,
                    )
                )
            }
        }.onFailure { failCall(it.message ?: "Nie udało się rozpocząć rozmowy") }
    }

    fun acceptIncomingCall() {
        val offer = pendingOffer ?: return
        val remoteId = offer.from ?: return
        val sdp = offer.sdp ?: return
        activeCallId = offer.callId
        activeRemoteId = remoteId
        _state.update { it.copy(phase = CallPhase.CONNECTING_CALL, status = "Łączenie…") }
        runCatching {
            webRtcClient.prepareCall()
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
                            sdpType = answer.type.canonicalForm(),
                            sdp = answer.description,
                        )
                    )
                }
            }
            pendingOffer = null
        }.onFailure { failCall(it.message ?: "Nie udało się odebrać rozmowy") }
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
                )
            )
        }
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
                )
            )
        }
        clearCall("Rozmowa zakończona")
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

    fun bindLocalRenderer(renderer: SurfaceViewRenderer?) = webRtcClient.bindLocalRenderer(renderer)
    fun bindRemoteRenderer(renderer: SurfaceViewRenderer?) = webRtcClient.bindRemoteRenderer(renderer)
    fun eglContext() = webRtcClient.eglContext

    override fun onConnected() = onMain {
        if (_state.value.phase in listOf(CallPhase.CONNECTING_SERVER, CallPhase.SERVER_ERROR, CallPhase.READY)) {
            _state.update { it.copy(phase = CallPhase.READY, status = "Gotowy do rozmowy") }
        }
    }

    override fun onDisconnected(reason: String) = onMain {
        if (_state.value.phase == CallPhase.READY || _state.value.phase == CallPhase.CONNECTING_SERVER) {
            _state.update { it.copy(phase = CallPhase.SERVER_ERROR, status = reason) }
        } else {
            failCall("Utracono połączenie z serwerem")
        }
    }

    override fun onMessage(message: SignalMessage) = onMain {
        when (message.type) {
            "offer" -> handleOffer(message)
            "answer" -> if (message.callId == activeCallId && message.sdp != null) {
                _state.update { it.copy(phase = CallPhase.CONNECTING_CALL, status = "Łączenie…") }
                webRtcClient.setRemoteDescription(message.sdpType ?: "answer", message.sdp)
            }
            "ice" -> if (message.callId == activeCallId && message.candidate != null) {
                val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
                if (_state.value.phase == CallPhase.INCOMING) pendingIncomingCandidates += candidate
                else webRtcClient.addRemoteIceCandidate(candidate)
            }
            "hangup" -> if (message.callId == activeCallId) clearCall("Rozmówca zakończył rozmowę")
            "reject" -> if (message.callId == activeCallId) clearCall("Połączenie odrzucone")
            "busy" -> if (message.callId == activeCallId) clearCall("Rozmówca jest zajęty")
            "unavailable" -> if (message.callId == activeCallId) clearCall("Rozmówca jest niedostępny")
        }
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        val remoteId = activeRemoteId ?: return
        signalingClient.send(
            SignalMessage(
                type = "ice",
                from = _state.value.myId,
                to = remoteId,
                callId = activeCallId,
                candidate = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
            )
        )
    }

    override fun onConnectionStateChanged(connected: Boolean) = onMain {
        if (connected) {
            _state.update { it.copy(phase = CallPhase.IN_CALL, status = "Połączono") }
        } else if (_state.value.phase in listOf(CallPhase.CONNECTING_CALL, CallPhase.IN_CALL)) {
            clearCall("Połączenie zostało przerwane")
        }
    }

    override fun onError(message: String) = onMain { failCall(message) }

    private fun handleOffer(message: SignalMessage) {
        val from = message.from ?: return
        if (_state.value.phase != CallPhase.READY) {
            signalingClient.send(
                SignalMessage(type = "busy", from = _state.value.myId, to = from, callId = message.callId)
            )
            return
        }
        pendingOffer = message
        pendingIncomingCandidates.clear()
        activeCallId = message.callId
        activeRemoteId = from
        val contact = _state.value.contacts.firstOrNull { it.id == from }
        _state.update {
            it.copy(
                phase = CallPhase.INCOMING,
                status = "Połączenie przychodzące",
                remoteId = from,
                remoteName = contact?.name ?: from,
            )
        }
    }

    private fun failCall(message: String) {
        webRtcClient.endCall()
        activeCallId = null
        activeRemoteId = null
        pendingOffer = null
        pendingIncomingCandidates.clear()
        _state.update {
            it.copy(
                phase = CallPhase.SERVER_ERROR,
                status = message,
                remoteId = null,
                remoteName = null,
            )
        }
    }

    private fun clearCall(status: String) {
        webRtcClient.endCall()
        activeCallId = null
        activeRemoteId = null
        pendingOffer = null
        pendingIncomingCandidates.clear()
        _state.update {
            it.copy(
                phase = CallPhase.READY,
                status = status,
                remoteId = null,
                remoteName = null,
                microphoneEnabled = true,
                frontCamera = true,
            )
        }
    }

    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun onCleared() {
        signalingClient.close()
        webRtcClient.release()
        super.onCleared()
    }
}
