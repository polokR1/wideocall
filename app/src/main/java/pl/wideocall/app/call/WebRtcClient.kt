package pl.wideocall.app.call

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.data.VideoQualityMode
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
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum class RtcConnectionState {
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

class WebRtcClient(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChanged(state: RtcConnectionState)
        fun onChatReadyChanged(ready: Boolean)
        fun onChatMessage(message: String)
        fun onBinaryMessage(bytes: ByteArray)
        fun onError(message: String)
    }

    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    private val appContext = context.applicationContext
    private val eglBase = EglBase.create()
    private val audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setUseHardwareAcousticEchoCanceler(true)
        .setUseHardwareNoiseSuppressor(true)
        // Opting into FAST playback failed on the physical moto g stylus / Android 11:
        // zero-frame playback buffer, repeated underruns, then AudioTrack.write failed (-1).
        // Use WebRTC's normal playback path; keep Telecom routing and real audio errors intact.
        .setUseLowLatency(false)
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                listener.onError("Mikrofon nie został uruchomiony: $errorMessage")
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                errorMessage: String,
            ) {
                listener.onError("Mikrofon rozmowy nie został uruchomiony ($errorCode): $errorMessage")
            }

            override fun onWebRtcAudioRecordError(errorMessage: String) {
                listener.onError("Mikrofon przestał działać: $errorMessage")
            }
        })
        .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                listener.onError("Głośnik nie został uruchomiony: $errorMessage")
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                errorMessage: String,
            ) {
                listener.onError("Odtwarzanie dźwięku nie wystartowało ($errorCode): $errorMessage")
            }

            override fun onWebRtcAudioTrackError(errorMessage: String) {
                listener.onError("Odtwarzanie dźwięku zostało przerwane: $errorMessage")
            }
        })
        .createAudioDeviceModule()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    @Volatile private var sessionGeneration = 0L
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private val queuedRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var remoteDescriptionSet = false
    private var frontCamera = true
    private var mediaEnabled = false
    private var videoProfile = VideoProfile.wifi()

    init {
        Log.i("WideocallAudio", "audio_config lowLatency=false api=${Build.VERSION.SDK_INT}")
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

    fun prepareSession(
        isCaller: Boolean,
        withMedia: Boolean,
        withVideo: Boolean,
        turnConfiguration: TurnConfiguration?,
    ) {
        closePeerConnection()
        stopLocalMedia()
        remoteDescriptionSet = false
        mediaEnabled = withMedia
        frontCamera = AppPreferences(appContext).startWithFrontCamera
        videoProfile = preferredVideoProfile()
        if (withMedia) startLocalMedia(videoProfile, withVideo)

        val iceServers = buildIceServers(turnConfiguration)
        val generation = sessionGeneration
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceCandidatePoolSize = 2
            audioJitterBufferMaxPackets = 100
            audioJitterBufferFastAccelerate = true
            iceConnectionReceivingTimeout = 8_000
            iceBackupCandidatePairPingInterval = 2_500
            enableCpuOveruseDetection = true
        }
        peerConnection = peerConnectionFactory.createPeerConnection(
            configuration,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    if (generation != sessionGeneration) return
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED ->
                            listener.onConnectionStateChanged(RtcConnectionState.CONNECTED)
                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            listener.onConnectionStateChanged(RtcConnectionState.DISCONNECTED)
                        PeerConnection.IceConnectionState.FAILED ->
                            listener.onConnectionStateChanged(RtcConnectionState.FAILED)
                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (generation == sessionGeneration) listener.onLocalIceCandidate(candidate)
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) {
                    if (generation == sessionGeneration) bindDataChannel(channel)
                    else { channel.close(); channel.dispose() }
                }
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                    if (generation == sessionGeneration) attachRemoteTrack(receiver.track())
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    if (generation == sessionGeneration) attachRemoteTrack(transceiver.receiver.track())
                }
            }
        ) ?: error("Nie udało się utworzyć połączenia WebRTC")

        if (isCaller) {
            val init = DataChannel.Init().apply { ordered = true }
            peerConnection?.createDataChannel(CHAT_CHANNEL, init)?.let(::bindDataChannel)
        }

        localVideoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf(STREAM_ID))?.let { configureVideoSender(it, videoProfile) }
        }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf(STREAM_ID)) }
        if (withMedia) {
            peerConnection?.setBitrate(videoProfile.minBitrateBps, videoProfile.startBitrateBps, videoProfile.maxBitrateBps)
        }
    }

    fun createOffer(onReady: (SessionDescription) -> Unit) {
        createOffer(iceRestart = false, onReady = onReady)
    }

    fun createIceRestartOffer(onReady: (SessionDescription) -> Unit) {
        peerConnection?.restartIce()
        createOffer(iceRestart = true, onReady = onReady)
    }

    private fun createOffer(iceRestart: Boolean, onReady: (SessionDescription) -> Unit) {
        val generation = sessionGeneration
        val constraints = offerAnswerConstraints().apply {
            if (iceRestart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (generation == sessionGeneration) setLocalDescription(description, onReady)
            }

            override fun onCreateFailure(error: String) { if (generation == sessionGeneration) listener.onError(error) }
        }, constraints)
    }

    fun createAnswer(onReady: (SessionDescription) -> Unit) {
        val generation = sessionGeneration
        val constraints = offerAnswerConstraints()
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (generation == sessionGeneration) setLocalDescription(description, onReady)
            }

            override fun onCreateFailure(error: String) { if (generation == sessionGeneration) listener.onError(error) }
        }, constraints)
    }

    fun setRemoteDescription(type: String, sdp: String, onComplete: () -> Unit = {}) {
        val generation = sessionGeneration
        val descriptionType = when (type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> return listener.onError("Nieznany typ opisu połączenia")
        }
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (generation != sessionGeneration) return
                remoteDescriptionSet = true
                queuedRemoteCandidates.forEach { peerConnection?.addIceCandidate(it) }
                queuedRemoteCandidates.clear()
                onComplete()
            }

            override fun onSetFailure(error: String) { if (generation == sessionGeneration) listener.onError(error) }
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

    fun sendChatMessage(message: String): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun sendBinaryMessage(bytes: ByteArray): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN || bytes.isEmpty()) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    fun dataChannelBufferedAmount(): Long = dataChannel?.bufferedAmount() ?: Long.MAX_VALUE

    fun endCall() {
        bindRemoteRenderer(null)
        bindLocalRenderer(null)
        closePeerConnection()
        stopLocalMedia()
        mediaEnabled = false
    }

    fun release() {
        endCall()
        peerConnectionFactory.dispose()
        audioDeviceModule.release()
        eglBase.release()
    }

    private fun startLocalMedia(profile: VideoProfile, withVideo: Boolean) {
        if (withVideo && localVideoTrack == null) {
            val capturer = createCameraCapturer()
            val helper = SurfaceTextureHelper.create("WideocallCapture", eglContext)
            val source = peerConnectionFactory.createVideoSource(false)
            capturer.initialize(helper, appContext, source.capturerObserver)
            runCatching { capturer.startCapture(profile.width, profile.height, profile.fps) }
                .onFailure { listener.onError("Nie udało się uruchomić kamery: ${it.message}") }

            videoCapturer = capturer
            surfaceTextureHelper = helper
            videoSource = source
            localVideoTrack = peerConnectionFactory.createVideoTrack("video0", source).apply {
                setEnabled(true)
                localRenderer?.let(::addSink)
            }
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
        sessionGeneration += 1
        dataChannel?.unregisterObserver()
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        listener.onChatReadyChanged(false)
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteDescriptionSet = false
        queuedRemoteCandidates.clear()
        remoteVideoTrack = null
    }

    private fun bindDataChannel(channel: DataChannel) {
        val generation = sessionGeneration
        if (dataChannel === channel) return
        dataChannel?.unregisterObserver()
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (generation != sessionGeneration || dataChannel !== channel) return
                listener.onChatReadyChanged(channel.state() == DataChannel.State.OPEN)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (generation != sessionGeneration || dataChannel !== channel) return
                val source = buffer.data.slice()
                val maxBytes = if (buffer.binary) MAX_BINARY_PAYLOAD_BYTES else MAX_CHAT_PAYLOAD_BYTES
                if (source.remaining() !in 1..maxBytes) return
                val bytes = ByteArray(source.remaining())
                source.get(bytes)
                if (buffer.binary) listener.onBinaryMessage(bytes)
                else listener.onChatMessage(String(bytes, StandardCharsets.UTF_8))
            }
        })
        listener.onChatReadyChanged(channel.state() == DataChannel.State.OPEN)
    }

    private fun configureVideoSender(sender: RtpSender, profile: VideoProfile) {
        val parameters = sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        parameters.encodings.forEach { encoding ->
            encoding.minBitrateBps = profile.minBitrateBps
            encoding.maxBitrateBps = profile.maxBitrateBps
            encoding.maxFramerate = profile.fps
        }
        sender.setParameters(parameters)
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
        val generation = sessionGeneration
        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { if (generation == sessionGeneration) onReady(description) }
            override fun onSetFailure(error: String) { if (generation == sessionGeneration) listener.onError(error) }
        }, description)
    }

    private fun offerAnswerConstraints() = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", mediaEnabled.toString())
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", mediaEnabled.toString())
    }

    private fun buildIceServers(turnConfiguration: TurnConfiguration?): List<PeerConnection.IceServer> = buildList {
        add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        turnConfiguration?.iceServers?.forEach { server ->
            server.urls.forEach { url ->
                val builder = PeerConnection.IceServer.builder(url)
                if (server.username.isNotBlank()) builder.setUsername(server.username)
                if (server.credential.isNotBlank()) builder.setPassword(server.credential)
                add(builder.createIceServer())
            }
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator: CameraEnumerator = Camera2Enumerator(appContext)
        val preferred = if (frontCamera) {
            enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
        } else {
            enumerator.deviceNames.firstOrNull(enumerator::isBackFacing)
        }
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("Nie znaleziono kamery")
        return enumerator.createCapturer(preferred, null)
            ?: error("Nie udało się otworzyć kamery")
    }

    private fun preferredVideoProfile(): VideoProfile {
        when (AppPreferences(appContext).videoQualityMode) {
            VideoQualityMode.DATA_SAVER -> return VideoProfile.cellular()
            VideoQualityMode.HIGH -> return VideoProfile.wifi()
            VideoQualityMode.AUTO -> Unit
        }
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        return if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            VideoProfile.cellular()
        } else {
            VideoProfile.wifi()
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private const val STREAM_ID = "wideocall"
        private const val CHAT_CHANNEL = "ephemeral-chat"
        private const val MAX_CHAT_PAYLOAD_BYTES = 4_096
        private const val MAX_BINARY_PAYLOAD_BYTES = 64 * 1024
    }

    private data class VideoProfile(
        val width: Int,
        val height: Int,
        val fps: Int,
        val minBitrateBps: Int,
        val startBitrateBps: Int,
        val maxBitrateBps: Int,
    ) {
        companion object {
            // 24 fps looks materially smoother than the previous 15 fps while WebRTC can still
            // lower quality dynamically on a weak mobile connection.
            fun cellular() = VideoProfile(640, 360, 24, 80_000, 400_000, 750_000)
            fun wifi() = VideoProfile(1280, 720, 30, 150_000, 800_000, 1_500_000)
        }
    }
}
