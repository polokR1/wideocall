package pl.wideocall.app.call

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.*
import org.junit.Test

class AudioRoutePolicyTest {
    private val speaker = CallEndpointCompat.TYPE_SPEAKER
    private val earpiece = CallEndpointCompat.TYPE_EARPIECE
    private val bluetooth = CallEndpointCompat.TYPE_BLUETOOTH
    private val wired = CallEndpointCompat.TYPE_WIRED_HEADSET

    @Test fun speakerSelectionUsesOnlyAvailableSpeaker() {
        assertEquals(speaker, AudioRoutePolicy.targetType(true, listOf(earpiece, speaker)))
        assertNull(AudioRoutePolicy.targetType(true, listOf(earpiece)))
    }
    @Test fun speakerOffPrefersConnectedHeadsetsThenEarpiece() {
        assertEquals(bluetooth, AudioRoutePolicy.targetType(false, listOf(speaker, earpiece, wired, bluetooth)))
        assertEquals(wired, AudioRoutePolicy.targetType(false, listOf(speaker, earpiece, wired)))
        assertEquals(earpiece, AudioRoutePolicy.targetType(false, listOf(speaker, earpiece)))
    }
    @Test fun missingAlternativeDoesNotPretendToSwitch() {
        assertNull(AudioRoutePolicy.targetType(false, listOf(speaker)))
        assertNull(AudioRoutePolicy.targetType(true, emptyList()))
    }
    @Test fun videoDefaultDoesNotStealAudioFromHeadsets() {
        assertTrue(AudioRoutePolicy.preferSpeakerOnStart(earpiece))
        assertFalse(AudioRoutePolicy.preferSpeakerOnStart(bluetooth))
        assertFalse(AudioRoutePolicy.preferSpeakerOnStart(wired))
        assertFalse(AudioRoutePolicy.preferSpeakerOnStart(speaker))
    }
}
