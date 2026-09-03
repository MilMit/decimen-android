package io.decimen.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF69F0AE),
    onPrimary = Color(0xFF003921),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF07110D),
    surface = Color(0xFF101B16),
    surfaceVariant = Color(0xFF1B2822),
    onBackground = Color(0xFFE5F2EA),
    onSurface = Color(0xFFE5F2EA),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C49),
    secondary = Color(0xFF006A61),
    background = Color(0xFFF5FBF7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFDDE9E1),
)

@Composable
fun DecimenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
