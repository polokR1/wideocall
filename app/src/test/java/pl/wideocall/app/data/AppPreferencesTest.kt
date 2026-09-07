package pl.wideocall.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun normalizeId_formatsValidIdentifier() {
        assertEquals("AB3D-EF7H", AppPreferences.normalizeId("ab3d ef7h"))
    }

    @Test
    fun normalizeId_removesAmbiguousAndExtraCharacters() {
        assertEquals("ABCD-EFGH", AppPreferences.normalizeId("A1B0C-DIEOFGHZZ"))
    }
}
