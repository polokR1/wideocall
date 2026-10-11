package pl.wideocall.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import pl.wideocall.app.call.AppUiState
import pl.wideocall.app.call.CallPhase
import pl.wideocall.app.call.EphemeralChatMessage
import pl.wideocall.app.ui.theme.WideocallTheme

class ChatSecurityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun endingChatRequiresExplicitConfirmationAndCanBeCancelled() {
        var ended = false
        var cancelled = false
        compose.setContent { WideocallTheme {
            EndChatConfirmationDialog("Test", onCancel = { cancelled = true }, onConfirm = { ended = true })
        } }
        compose.onNodeWithText("Zakończyć połączenie czatu?").assertIsDisplayed()
        assertFalse(ended)
        compose.onNodeWithText("Zostaw czat aktywny").performClick()
        assertTrue(cancelled)
        assertFalse(ended)
        compose.onNodeWithText("Zakończ połączenie czatu").performClick()
        assertTrue(ended)
    }

    @Test fun deliveryStatusDoesNotClaimMessageWasRead() {
        val state = AppUiState("ABCD-EF23", "Test", emptyList(), chatMessages = listOf(
            EphemeralChatMessage("pending", "Czeka", true, 0, pending = true),
            EphemeralChatMessage("delivered", "Dostarczona", true, 0, pending = false),
        ))
        compose.setContent { WideocallTheme {
            EphemeralChatPanel(state, onClose = {}, onSend = { false }, onDraftChanged = {})
        } }
        compose.onNodeWithText("Czeka na dostarczenie").assertIsDisplayed()
        compose.onNodeWithText("Dostarczono na telefon").assertIsDisplayed()
        compose.onNodeWithText("Odbiór potwierdzony").assertDoesNotExist()
        compose.onNodeWithText("Przeczytano").assertDoesNotExist()
    }

    @Test fun failedChatSendKeepsDraftAndDisplaysError() {
        val state = mutableStateOf(AppUiState("ABCD-EF23", "Test", emptyList(), phase = CallPhase.IN_CHAT, chatReady = true))
        compose.setContent {
            WideocallTheme {
                EphemeralChatPanel(
                    state = state.value,
                    onClose = {},
                    onDraftChanged = { state.value = state.value.copy(chatDraft = it) },
                    onSend = {
                        state.value = state.value.copy(chatSendError = "Nie wysłano. Tekst zachowano.")
                        false
                    },
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Sekretny szkic")
        compose.onNodeWithContentDescription("Wyślij").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("Sekretny szkic")
        compose.onNodeWithText("Nie wysłano. Tekst zachowano.").assertIsDisplayed()
    }

    @Test fun safetyNumberRequiresExplicitManualConfirmation() {
        val number = "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890 12345 67890"
        val state = mutableStateOf(AppUiState("ABCD-EF23", "Test", emptyList(), safetyNumber = number, callId = "session-1234"))
        compose.setContent {
            WideocallTheme {
                SecurityStatusIcon(state.value, onVerify = {
                    state.value = state.value.copy(peerIdentityVerified = true)
                    true
                })
            }
        }
        compose.onNodeWithContentDescription("Transport szyfrowany — dotknij, aby zweryfikować rozmówcę").performClick()
        compose.onNodeWithText(number).assertIsDisplayed()
        compose.onNodeWithText("Tożsamość nie została jeszcze ręcznie potwierdzona.").assertIsDisplayed()
        compose.onNodeWithText("Numery są identyczne").performClick()
        compose.onNodeWithContentDescription("Tożsamość ręcznie potwierdzona — numer bezpieczeństwa").assertExists()
        compose.onNodeWithText(number).assertDoesNotExist()
    }
}
