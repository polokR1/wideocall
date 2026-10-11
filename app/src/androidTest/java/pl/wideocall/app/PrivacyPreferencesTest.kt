package pl.wideocall.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.data.Contact
import java.util.UUID

class PrivacyPreferencesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun historyMigrationAndRenamingPreserveIdentityAndOtherContacts() {
        val name = "test-preferences-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int) = context.getSharedPreferences(name, mode)
        }
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            raw.edit().putString("recent_callers_v1", "[{\"id\":\"ABCD-EF23\",\"lastCallAt\":123}]")
                .putString("my_id", "MNPR-ST45").putString("pinned_key_test", "unchanged").commit()
            val prefs = AppPreferences(isolated)
            assertFalse(raw.contains("recent_callers_v1"))
            prefs.saveContact(Contact("ABCD-EF23", "Motorola"))
            prefs.saveContact(Contact("GHJK-LM45", "Samsung"))
            assertTrue(prefs.renameContact("ABCD-EF23", "  Żółta Motorola  ").isSuccess)
            val restored = AppPreferences(isolated)
            assertEquals("Żółta Motorola", restored.contacts().first { it.id == "ABCD-EF23" }.name)
            assertEquals("Samsung", restored.contacts().first { it.id == "GHJK-LM45" }.name)
            assertEquals("MNPR-ST45", restored.myId)
            assertEquals("unchanged", raw.getString("pinned_key_test", null))
            assertTrue(restored.renameContact("ABCD-EF23", " ").isFailure)
            assertTrue(restored.renameContact("ZZZZ-ZZ23", "Nie zapisany").isFailure)
            assertEquals(2, restored.contacts().size)
            assertFalse(raw.contains("recent_callers_v1"))
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun packagedAppRequestsMediaButNotLocationOrNearbyDevices() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(requested.containsAll(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)))
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE").forEach {
            assertFalse("Zbędne uprawnienie: $it", requested.contains("android.permission.$it"))
        }
    }
}
