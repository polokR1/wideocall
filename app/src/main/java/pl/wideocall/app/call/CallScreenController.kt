package pl.wideocall.app.call

import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One owner for both the window flag and the root view; reapply on resume/focus. */
object CallScreenController {
    fun bind(owner: LifecycleOwner, window: Window, state: StateFlow<AppUiState>): Job = owner.lifecycleScope.launch {
        try {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    state.map { CallScreenAwakePolicy.enabled(it.phase) }.distinctUntilChanged().collect { apply(window, it) }
                } finally { apply(window, false) }
            }
        } finally { apply(window, false) }
    }

    fun apply(window: Window, enabled: Boolean) {
        if (window.decorView.keepScreenOn != enabled) window.decorView.keepScreenOn = enabled
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val currentlyEnabled = window.attributes.flags and flag != 0
        if (enabled && !currentlyEnabled) window.addFlags(flag)
        else if (!enabled && currentlyEnabled) window.clearFlags(flag)
    }
}
