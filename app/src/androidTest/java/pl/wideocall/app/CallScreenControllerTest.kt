package pl.wideocall.app

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.wideocall.app.call.CallScreenController

class CallScreenControllerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun activeWindowAndRootViewStayAwakeUntilCallEnds() {
        compose.runOnUiThread {
            val window = compose.activity.window
            CallScreenController.apply(window, true)
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertTrue(window.decorView.keepScreenOn)
            // A recreated/resumed window can lose its flags; reapplying must restore both protections.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.keepScreenOn = false
            CallScreenController.apply(window, true)
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertTrue(window.decorView.keepScreenOn)
            CallScreenController.apply(window, false)
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertFalse(window.decorView.keepScreenOn)
        }
    }
}
