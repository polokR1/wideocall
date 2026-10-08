package pl.wideocall.app

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.IceCandidate
import pl.wideocall.app.call.RtcConnectionState
import pl.wideocall.app.call.WebRtcClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Native WebRTC loopback; no signaling server or real contact is involved. */
class WebRtcDataChannelTest {
    @Test fun encryptedDataChannelCarriesUnicodeInBothDirectionsAfterReopening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = Handler(Looper.getMainLooper())
        val failure = AtomicReference<String?>(null)
        val atLeft = LinkedBlockingQueue<String>()
        val atRight = LinkedBlockingQueue<String>()
        val ready = AtomicReference(CountDownLatch(2))
        lateinit var left: WebRtcClient
        lateinit var right: WebRtcClient
        fun listener(toLeft: Boolean) = object : WebRtcClient.Listener {
            override fun onLocalIceCandidate(candidate: IceCandidate) {
                main.post { (if (toLeft) right else left).addRemoteIceCandidate(candidate) }
            }
            override fun onConnectionStateChanged(state: RtcConnectionState) = Unit
            override fun onChatReadyChanged(value: Boolean) { if (value) ready.get().countDown() }
            override fun onChatMessage(message: String) { (if (toLeft) atLeft else atRight).offer(message) }
            override fun onBinaryMessage(bytes: ByteArray) = Unit
            override fun onError(message: String) { failure.set(message) }
        }
        instrumentation.runOnMainSync {
            left = WebRtcClient(instrumentation.targetContext, listener(true))
            right = WebRtcClient(instrumentation.targetContext, listener(false))
        }
        try {
            repeat(2) { round ->
                ready.set(CountDownLatch(2))
                instrumentation.runOnMainSync {
                    left.prepareSession(isCaller = true, withMedia = false, turnConfiguration = null)
                    right.prepareSession(isCaller = false, withMedia = false, turnConfiguration = null)
                    left.createOffer { offer -> main.post {
                        right.setRemoteDescription("offer", offer.description) { main.post {
                            right.createAnswer { answer -> main.post { left.setRemoteDescription("answer", answer.description) } }
                        } }
                    } }
                }
                assertTrue("Kanał nie został otwarty: ${failure.get()}", ready.get().await(20, TimeUnit.SECONDS))
                assertNull(failure.get())
                val outgoing = "Zażółć gęślą jaźń 🔐 $round"
                instrumentation.runOnMainSync { assertTrue(left.sendChatMessage(outgoing)) }
                assertEquals(outgoing, atRight.poll(5, TimeUnit.SECONDS))
                val reply = "Odpowiedź — próba $round"
                instrumentation.runOnMainSync { assertTrue(right.sendChatMessage(reply)) }
                assertEquals(reply, atLeft.poll(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { left.endCall(); right.endCall() }
            }
        } finally { instrumentation.runOnMainSync { left.release(); right.release() } }
    }
}
