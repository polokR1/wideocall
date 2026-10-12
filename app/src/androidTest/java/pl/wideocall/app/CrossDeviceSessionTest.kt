package pl.wideocall.app

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import pl.wideocall.app.call.*
import pl.wideocall.app.data.Contact
import pl.wideocall.app.filetransfer.FileTransferStatus
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in test on two real phones. It preserves device identities and saved contacts.
 * Run simultaneously with -e role caller|receiver -e peer XXXX-XXXX -e kind audio|video|chat|file.
 * Packet/frame counters prove transport, not perceived loudness or image quality.
 */
class CrossDeviceSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private lateinit var model: CallViewModel

    @Test fun actualSessionBetweenTwoDevices() {
        val role = arguments.getString("role") ?: ""
        assumeTrue("Only run explicitly on two devices", role in setOf("caller", "receiver"))
        val caller = role == "caller"
        val peer = requireNotNull(arguments.getString("peer"))
        val kind = arguments.getString("kind") ?: "audio"
        require(kind in setOf("audio", "video", "chat", "file"))
        val run = arguments.getString("run") ?: "cross-device"
        val contact = Contact(peer, "Test zgodności")
        var outgoingFile: File? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { model = ViewModelProvider(it)[CallViewModel::class.java] }
            try {
                await("sygnalizacja", 30_000) {
                    if (caller) model.state.value.phase == CallPhase.READY else model.state.value.signalingConnected
                }
                if (caller) main {
                    when (kind) {
                        "audio" -> model.audioCall(contact)
                        "video" -> model.call(contact)
                        "chat" -> model.connectChat(contact)
                        "file" -> {
                            outgoingFile = File.createTempFile("wideocall-e2e-", ".bin", instrumentation.targetContext.cacheDir)
                                .apply { writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() }) }
                            model.startFileTransfer(contact, Uri.fromFile(outgoingFile!!))
                        }
                    }
                } else {
                    val incoming = when (kind) {
                        "chat" -> CallPhase.INCOMING_CHAT
                        "file" -> CallPhase.INCOMING_FILE
                        else -> CallPhase.INCOMING
                    }
                    await("oferta $kind", 40_000) { model.state.value.phase == incoming }
                    main {
                        when (kind) {
                            "chat" -> model.acceptIncomingChat(requireNotNull(model.state.value.callId))
                            "file" -> model.acceptIncomingFile(requireNotNull(model.state.value.callId))
                            else -> model.acceptIncomingCall()
                        }
                    }
                }
                when (arguments.getString("outcome")) {
                    "decline" -> {
                        await("odrzucenie", 35_000) { model.state.value.callId == null }
                        assertTrue("Zakończenie bez odrzucenia: phase=${model.state.value.phase}, status=${model.state.value.status}, diagnostic=${model.state.value.diagnostic}",
                            model.state.value.status.contains("odrzuco", ignoreCase = true))
                        report("decline", "remoteDeclineConfirmed=true")
                    }
                    "cancel" -> {
                        Thread.sleep(8_000)
                        main { model.hangUp() }
                        Thread.sleep(2_000)
                        report("cancel", "callerCancellationSent=true")
                    }
                    else -> when (kind) {
                        "audio", "video" -> checkMedia(caller, kind)
                        "chat" -> checkChat(caller, peer, run)
                        "file" -> checkFile(caller)
                    }
                }
                if (caller) main { model.hangUp() }
                await("koniec sesji", 35_000) { model.state.value.callId == null }
                assertTrue("Sesja nie zwolniła stanu: ${model.state.value.phase}",
                    model.state.value.phase in setOf(CallPhase.READY, CallPhase.CONNECTING_SERVER, CallPhase.SERVER_ERROR))
            } finally {
                main { if (model.state.value.callId != null) model.hangUp() }
                outgoingFile?.delete()
            }
        }
    }

    private fun checkMedia(caller: Boolean, kind: String) {
        await("WebRTC media", 35_000) { model.state.value.phase == CallPhase.IN_CALL }
        assertEquals(kind == "audio", model.state.value.audioOnlyCall)
        Thread.sleep(3_000)
        if (model.state.value.canSwitchAudioRoute) {
            val originalSpeaker = model.state.value.speakerphoneEnabled
            main { model.toggleSpeaker() }
            await("przełączenie głośnika", 12_000) { !model.state.value.audioRouteChanging && model.state.value.speakerphoneEnabled != originalSpeaker }
            main { model.toggleSpeaker() }
            await("powrót wyjścia audio", 12_000) { !model.state.value.audioRouteChanging && model.state.value.speakerphoneEnabled == originalSpeaker }
        }
        val before = stats()
        repeat(6) {
            Thread.sleep(10_000)
            assertEquals("Rozmowa przerwana: ${model.state.value.diagnostic}", CallPhase.IN_CALL, model.state.value.phase)
        }
        val after = stats()
        for (direction in listOf("inbound-rtp", "outbound-rtp")) {
            val field = if (direction == "inbound-rtp") "packetsReceived" else "packetsSent"
            assertTrue("Brak ruchu audio $direction", metric(after, direction, "audio", field) > metric(before, direction, "audio", field))
        }
        if (kind == "video") {
            assertTrue("Nie dekodowano zdalnych klatek", metric(after, "inbound-rtp", "video", "framesDecoded") > metric(before, "inbound-rtp", "video", "framesDecoded"))
        }
        report("media", "kind=$kind inboundAudio=${metric(after, "inbound-rtp", "audio", "packetsReceived")} outboundAudio=${metric(after, "outbound-rtp", "audio", "packetsSent")} decodedFrames=${metric(after, "inbound-rtp", "video", "framesDecoded")}")
        if (caller) Thread.sleep(8_000)
    }

    private fun checkChat(caller: Boolean, peer: String, run: String) {
        await("kanał czatu", 35_000) { model.state.value.phase == CallPhase.IN_CHAT && model.state.value.chatReady }
        val store = (instrumentation.targetContext.applicationContext as WideocallApplication).localChatStore
        val text = "TEST $run — zażółć gęślą jaźń 🔐"
        if (caller) {
            Thread.sleep(2_000)
            main { model.openLocalChat(peer); assertTrue(model.sendChatMessage(text)) }
            await("potwierdzenie trwałego dostarczenia", 20_000) {
                runBlocking { store.read(peer) }.any { it.text == text && it.mine && !it.pending }
            }
            main { model.closeChatLocally() }
            home()
            await("odpowiedź do zminimalizowanego czatu", 110_000) {
                runBlocking { store.read(peer) }.any { it.text == "$text / odpowiedź" && !it.mine }
            }
            val reply = runBlocking { store.read(peer) }.single { it.text == "$text / odpowiedź" && !it.mine }
            assertNull("Odpowiedź była odliczana bez otwarcia", reply.openedAt)
            resumeApp()
            main { model.openLocalChat(peer) }
            Thread.sleep(67_000)
        } else {
            main { model.closeChatLocally() }
            home()
            await("wiadomość w zminimalizowanym czacie", 20_000) {
                runBlocking { store.read(peer) }.any { it.text == text && !it.mine }
            }
            val message = runBlocking { store.read(peer) }.single { it.text == text && !it.mine }
            assertNull(message.openedAt)
            Thread.sleep(65_000)
            val unopened = runBlocking { store.read(peer) }.single { it.id == message.id }
            assertNull("Powiadomienie rozpoczęło minutę", unopened.openedAt)
            resumeApp()
            main { model.openLocalChat(peer); assertTrue(model.sendChatMessage("$text / odpowiedź")) }
            await("lokalne otwarcie", 5_000) { runBlocking { store.read(peer) }.any { it.id == message.id && it.openedAt != null } }
            Thread.sleep(63_000)
            assertTrue("Wiadomość nie zniknęła po otwarciu", runBlocking { store.read(peer) }.none { it.id == message.id })
        }
        assertEquals("Powrót do okna przerwał sesję", CallPhase.IN_CHAT, model.state.value.phase)
        report("chat", "deliveryAcknowledged=true minimizedDelivery=true unopenedRetained65Seconds=true expiresAfterOpening=true")
    }

    private fun checkFile(caller: Boolean) {
        if (!caller) {
            await("metadane pliku", 35_000) { model.state.value.fileTransfer?.status == FileTransferStatus.WAITING_FOR_ACCEPTANCE }
            assertEquals(0L, model.state.value.fileTransfer?.transferredBytes)
            assertNull(model.state.value.fileTransfer?.receivedFilePath)
            Thread.sleep(2_000)
            assertEquals(0L, model.state.value.fileTransfer?.transferredBytes)
            main { model.acceptReceivedFile() }
        }
        await("transfer i SHA-256", 40_000) { model.state.value.fileTransfer?.status == FileTransferStatus.COMPLETE }
        assertEquals(256L * 1024, model.state.value.fileTransfer?.transferredBytes)
        if (!caller) {
            val received = File(requireNotNull(model.state.value.fileTransfer?.receivedFilePath))
            val expected = MessageDigest.getInstance("SHA-256").digest(ByteArray(256 * 1024) { (it % 251).toByte() })
            assertArrayEquals(expected, MessageDigest.getInstance("SHA-256").digest(received.readBytes()))
            received.delete()
        }
        report("file", "consentRequired=true bytes=262144 sha256Verified=true")
        if (caller) Thread.sleep(3_000)
    }

    private fun stats(): RTCStatsReport {
        val result = AtomicReference<RTCStatsReport>()
        val latch = CountDownLatch(1)
        main {
            val rtc = CallViewModel::class.java.getDeclaredField("webRtcClient").apply { isAccessible = true }.get(model)
            val connection = WebRtcClient::class.java.getDeclaredField("peerConnection").apply { isAccessible = true }.get(rtc) as PeerConnection
            connection.getStats { result.set(it); latch.countDown() }
        }
        assertTrue("Brak statystyk WebRTC", latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun metric(report: RTCStatsReport, type: String, kind: String, member: String): Long =
        report.statsMap.values.filter { it.type == type && (it.members["kind"] ?: it.members["mediaType"]) == kind }
            .sumOf { (it.members[member] as? Number)?.toLong() ?: 0L }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun home() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        Thread.sleep(1_000)
    }
    private fun resumeApp() {
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        })
        Thread.sleep(1_000)
    }
    private fun await(label: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("$label: phase=${model.state.value.phase}, status=${model.state.value.status}, diagnostic=${model.state.value.diagnostic}")
    }
    private fun report(key: String, value: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
}
