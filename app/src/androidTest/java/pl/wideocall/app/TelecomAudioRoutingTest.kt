package pl.wideocall.app

import androidx.activity.ComponentActivity
import androidx.core.telecom.CallEndpointCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.call.TelecomCallBridge
import pl.wideocall.app.call.AudioRoutePolicy
import pl.wideocall.app.notifications.OngoingCallService
import java.util.UUID

/** A local system call only: no contact, server or WebRTC media is called by this test. */
class TelecomAudioRoutingTest {
    @Volatile private var endpointType: Int? = null
    @Volatile private var available: List<CallEndpointCompat> = emptyList()
    @Volatile private var pending = false
    @Volatile private var error: String? = null

    @Test fun systemConfirmsRouteChangesOrReportsMissingAlternative() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val callId = UUID.randomUUID().toString()
        val listener = object : TelecomCallBridge.Listener {
            override fun onTelecomAnswer(callId: String) = true
            override fun onTelecomDisconnect(callId: String) = true
            override fun onTelecomMuteChanged(callId: String, muted: Boolean) = Unit
            override fun onTelecomAudioRoutePending(callId: String, value: Boolean) { pending = value }
            override fun onTelecomError(callId: String, message: String) { error = message }
            override fun onTelecomAudioEndpointChanged(callId: String, current: CallEndpointCompat?, endpoints: List<CallEndpointCompat>) {
                endpointType = current?.type
                available = endpoints
            }
        }
        ActivityScenario.launch(ComponentActivity::class.java).use {
            try {
                instrumentation.runOnMainSync {
                    TelecomCallBridge.attachListener(listener)
                    OngoingCallService.start(context, "Test lokalny", callId)
                    assertTrue(TelecomCallBridge.registerOutgoing(callId, "TEST-TEST"))
                    assertTrue(TelecomCallBridge.activate(callId))
                }
                await { endpointType == CallEndpointCompat.TYPE_SPEAKER && !pending && available.isNotEmpty() }
                val alternative = AudioRoutePolicy.targetType(false, available.map { endpoint -> endpoint.type })
                instrumentation.runOnMainSync { assertTrue(TelecomCallBridge.toggleSpeaker(callId)) }
                if (alternative == null) {
                    // Some emulator images have only a speaker. Verify explicit failure, not a fake successful toggle.
                    repeat(120) {
                        if (error == null || pending) Thread.sleep(100)
                    }
                    assertNotNull("Brak alternatywy powinien dać jawny błąd", error)
                    assertFalse(pending)
                    assertEquals(CallEndpointCompat.TYPE_SPEAKER, endpointType)
                    return@use
                }
                await { endpointType == alternative && !pending }
                instrumentation.runOnMainSync { assertTrue(TelecomCallBridge.toggleSpeaker(callId)) }
                await { endpointType == CallEndpointCompat.TYPE_SPEAKER && !pending }
                assertNull(error)
            } finally {
                instrumentation.runOnMainSync {
                    TelecomCallBridge.disconnectLocal(callId)
                    TelecomCallBridge.detachListener(listener)
                    OngoingCallService.stop(context)
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(120) {
            assertNull(error)
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Android nie potwierdził oczekiwanego wyjścia (typ=$endpointType, pending=$pending).")
    }
}
