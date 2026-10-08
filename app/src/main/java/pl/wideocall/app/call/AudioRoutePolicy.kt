package pl.wideocall.app.call

import androidx.core.telecom.CallEndpointCompat

/** Choose only currently available routes; never fall back to AudioManager behind Telecom. */
object AudioRoutePolicy {
    fun targetType(speaker: Boolean, available: Collection<Int>): Int? =
        if (speaker) CallEndpointCompat.TYPE_SPEAKER.takeIf { it in available }
        else listOf(CallEndpointCompat.TYPE_BLUETOOTH, CallEndpointCompat.TYPE_WIRED_HEADSET, CallEndpointCompat.TYPE_EARPIECE)
            .firstOrNull { it in available }

    fun preferSpeakerOnStart(currentType: Int): Boolean = currentType == CallEndpointCompat.TYPE_EARPIECE
}
