package pl.wideocall.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.wideocall.app.data.Contact
import pl.wideocall.app.ui.theme.WideocallTheme

class PrivacyDialogsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explainsLiveTransmissionBeforeSystemPermissionAndAllowsCancellation() {
        var confirmed = false
        var cancelled = false
        compose.setContent { WideocallTheme {
            MediaPermissionExplanation(onDismiss = { cancelled = true }, onContinue = { confirmed = true })
        } }
        compose.onNodeWithText("Kamera i mikrofon podczas rozmowy").assertIsDisplayed()
        compose.onNodeWithText("Wideocall nie zapisuje nagrania rozmowy", substring = true).assertIsDisplayed()
        assertFalse(confirmed)
        compose.onNodeWithText("Nie teraz").performClick()
        assertTrue(cancelled)
        assertFalse(confirmed)
    }

    @Test fun renameDialogRejectsBlankAndSubmitsLocalAlias() {
        var saved: String? = null
        compose.setContent { WideocallTheme {
            EditContactNameDialog(Contact("ABCD-EF23", "Motorola"), onDismiss = {}, onSave = {
                saved = it; Result.success(Unit)
            })
        } }
        compose.onNode(hasSetTextAction()).performTextReplacement(" ")
        compose.onNodeWithText("Zapisz nazwę").performClick()
        compose.onNodeWithText("Wpisz nazwę urządzenia.").assertIsDisplayed()
        assertNull(saved)
        compose.onNode(hasSetTextAction()).performTextReplacement("  Moja Motorola  ")
        compose.onNodeWithText("Zapisz nazwę").performClick()
        assertEquals("Moja Motorola", saved)
    }
}
