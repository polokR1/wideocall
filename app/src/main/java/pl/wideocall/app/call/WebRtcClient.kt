package pl.wideocall.app.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CopyOnWriteArrayList

class WebRtcClient(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChanged(connected: Boolean)
        fun onError(message: String)
    }

    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    private val appContext = context.applicationContext
    private val eglBase = EglBase.create()
    private val audioDeviceModule = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private val queuedRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var remoteDescriptionSet = false
    private var frontCamera = true

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
    }

    fun prepareCall() {
        closePeerConnection()
        remoteDescriptionSet = false
        startLocalMedia()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        peerConnection = peerConnectionFactory.createPeerConnection(
            configuration,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> listener.onConnectionStateChanged(true)
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> listener.onConnectionStateChanged(false)
                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = listener.onLocalIceCandidate(candidate)
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                    attachRemoteTrack(receiver.track())
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    attachRemoteTrack(transceiver.receiver.track())
                }
            }
        ) ?: error("Nie udało się utworzyć połączenia WebRTC")

        localVideoTrack?.let { peerConnection?.addTrack(it, listOf(STREAM_ID)) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf(STREAM_ID)) }
        configureAudio()
    }

    fun createOffer(onReady: (SessionDescription) -> Unit) {
        val constraints = offerAnswerConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                setLocalDescription(description, onReady)
            }

            override fun onCreateFailure(error: String) = listener.onError(error)
        }, constraints)
    }

    fun createAnswer(onReady: (SessionDescription) -> Unit) {
        val constraints = offerAnswerConstraints()
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                setLocalDescription(description, onReady)
            }

            override fun onCreateFailure(error: String) = listener.onError(error)
        }, constraints)
    }

    fun setRemoteDescription(type: String, sdp: String, onComplete: () -> Unit = {}) {
        val descriptionType = when (type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> return listener.onError("Nieznany typ opisu połączenia")
        }
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                queuedRemoteCandidates.forEach { peerConnection?.addIceCandidate(it) }
                queuedRemoteCandidates.clear()
                onComplete()
            }

            override fun onSetFailure(error: String) = listener.onError(error)
        }, SessionDescription(descriptionType, sdp))
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (remoteDescriptionSet) peerConnection?.addIceCandidate(candidate)
        else queuedRemoteCandidates += candidate
    }

    fun bindLocalRenderer(renderer: SurfaceViewRenderer?) {
        localRenderer?.let { localVideoTrack?.removeSink(it) }
        localRenderer = renderer
        renderer?.let { localVideoTrack?.addSink(it) }
    }

    fun bindRemoteRenderer(renderer: SurfaceViewRenderer?) {
        remoteRenderer?.let { remoteVideoTrack?.removeSink(it) }
        remoteRenderer = renderer
        renderer?.let { remoteVideoTrack?.addSink(it) }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
        frontCamera = !frontCamera
    }

    fun endCall() {
        bindRemoteRenderer(null)
        bindLocalRenderer(null)
        closePeerConnection()
        stopLocalMedia()
        restoreAudio()
    }

    fun release() {
        endCall()
        peerConnectionFactory.dispose()
        audioDeviceModule.release()
        eglBase.release()
    }

    private fun startLocalMedia() {
        if (localVideoTrack != null) return
        val capturer = createCameraCapturer()
        val helper = SurfaceTextureHelper.create("WideocallCapture", eglContext)
        val source = peerConnectionFactory.createVideoSource(false)
        capturer.initialize(helper, appContext, source.capturerObserver)
        runCatching { capturer.startCapture(1280, 720, 30) }
            .onFailure { listener.onError("Nie udało się uruchomić kamery: ${it.message}") }

        videoCapturer = capturer
        surfaceTextureHelper = helper
        videoSource = source
        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", source).apply {
            setEnabled(true)
            localRenderer?.let(::addSink)
        }
        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource).apply {
            setEnabled(true)
        }
    }

    private fun stopLocalMedia() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        videoCapturer = null
        surfaceTextureHelper = null
        localVideoTrack = null
        localAudioTrack = null
        videoSource = null
        audioSource = null
        remoteVideoTrack = null
    }

    private fun closePeerConnection() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteDescriptionSet = false
        queuedRemoteCandidates.clear()
        remoteVideoTrack = null
    }

    private fun attachRemoteTrack(track: org.webrtc.MediaStreamTrack?) {
        val videoTrack = track as? VideoTrack ?: return
        remoteVideoTrack?.let { old -> remoteRenderer?.let(old::removeSink) }
        remoteVideoTrack = videoTrack
        remoteRenderer?.let(videoTrack::addSink)
    }

    private fun setLocalDescription(
        description: SessionDescription,
        onReady: (SessionDescription) -> Unit,
    ) {
        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() = onReady(description)
            override fun onSetFailure(error: String) = listener.onError(error)
        }, description)
    }

    private fun offerAnswerConstraints() = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
    }

    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator: CameraEnumerator = Camera2Enumerator(appContext)
        val preferred = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("Nie znaleziono kamery")
        return enumerator.createCapturer(preferred, null)
            ?: error("Nie udało się otworzyć kamery")
    }

    private fun configureAudio() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let(audioManager::setCommunicationDevice)
        } else {
            setLegacySpeakerphone(audioManager, true)
        }
    }

    private fun restoreAudio() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            setLegacySpeakerphone(audioManager, false)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphone(audioManager: AudioManager, enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private const val STREAM_ID = "wideocall"
    }
}
