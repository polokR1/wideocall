package pl.wideocall.app

import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.wideocall.app.notifications.CallNotifications

class IncomingScreenWakeTest {
    @Test fun fullScreenCallIntentRestoresWakeAndLockScreenFlagsOnSingleTaskActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val incoming = Intent(context, MainActivity::class.java).apply {
            action = CallNotifications.ACTION_SHOW_INCOMING
            putExtra(CallNotifications.EXTRA_CALL_ID, java.util.UUID.randomUUID().toString())
            putExtra(CallNotifications.EXTRA_CALLER_ID, "TEST-TEST")
        }
        ActivityScenario.launch<MainActivity>(incoming).use { scenario ->
            scenario.onActivity { activity ->
                assertWakeFlags(activity)

                activity.window.clearFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                )
                MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply {
                    isAccessible = true
                }.invoke(activity, incoming)
                assertWakeFlags(activity)
            }
        }
    }

    private fun assertWakeFlags(activity: MainActivity) {
        val flags = activity.window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON != 0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED != 0)
        }
    }
}
