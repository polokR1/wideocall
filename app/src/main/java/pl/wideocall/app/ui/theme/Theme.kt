package pl.wideocall.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF62E6A7),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF15533B),
    onPrimaryContainer = Color(0xFFA4F4CC),
    secondaryContainer = Color(0xFF24483A),
    background = Color(0xFF07130F),
    surface = Color(0xFF10221B),
    surfaceVariant = Color(0xFF182D25),
    onSurface = Color(0xFFE6F2EC),
    onSurfaceVariant = Color(0xFFB7C9C0),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C49),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF94F7C7),
    onPrimaryContainer = Color(0xFF002114),
    secondaryContainer = Color(0xFFD1E8DC),
    background = Color(0xFFF5FBF7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3EEE8),
    onSurface = Color(0xFF15201A),
    onSurfaceVariant = Color(0xFF435149),
)

@Composable
fun WideocallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
