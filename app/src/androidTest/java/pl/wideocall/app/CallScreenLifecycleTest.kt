package pl.wideocall.app

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.call.*

class CallScreenLifecycleTest {
    @Test fun visibleCallSurvivesBackgroundReturnAndRecreatedWindowThenReleasesScreen() {
        val state = MutableStateFlow(AppUiState("TEST-TEST", "Test", emptyList(), phase = CallPhase.IN_CALL))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { CallScreenController.bind(it, it.window, state) }
            awaitWindow(scenario, true)
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitWindow(scenario, false)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitWindow(scenario, true)
            scenario.recreate()
            scenario.onActivity { CallScreenController.bind(it, it.window, state) }
            awaitWindow(scenario, true)
            state.value = state.value.copy(phase = CallPhase.READY)
            awaitWindow(scenario, false)
        }
    }

    private fun awaitWindow(scenario: ActivityScenario<ComponentActivity>, expected: Boolean) {
        repeat(30) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var actual = false
            scenario.onActivity { activity ->
                actual = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) == expected &&
                    activity.window.decorView.keepScreenOn == expected
            }
            if (actual) return
            Thread.sleep(100)
        }
        fail("Okno nie osiągnęło oczekiwanego keep-screen-on=$expected")
    }
}
