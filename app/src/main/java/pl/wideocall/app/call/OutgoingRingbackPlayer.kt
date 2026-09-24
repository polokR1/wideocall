package pl.wideocall.app.call

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Local ringback only. It never records or transmits audio. */
class OutgoingRingbackPlayer(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var toneGenerator: ToneGenerator? = null

    fun start(): Boolean {
        if (job?.isActive == true) return true
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME)
        }.getOrNull() ?: return false
        toneGenerator = generator
        job = scope.launch {
            while (isActive) {
                runCatching { generator.startTone(ToneGenerator.TONE_SUP_RINGTONE, TONE_DURATION_MS) }
                delay(RINGBACK_INTERVAL_MS)
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        toneGenerator?.let { generator ->
            runCatching { generator.stopTone() }
            runCatching { generator.release() }
        }
        toneGenerator = null
    }

    private companion object {
        const val RINGBACK_VOLUME = 65
        const val TONE_DURATION_MS = 1_200
        const val RINGBACK_INTERVAL_MS = 2_500L
    }
}
