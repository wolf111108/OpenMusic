package io.github.openmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF1D2E46)
internal val MutedInk = Color(0xFF64768F)
internal val PracticeBlue = Color(0xFF2D67D7)
internal val SoftBlue = Color(0xFFEDF3FF)
internal val PracticeGreen = Color(0xFF168060)
internal val SoftGreen = Color(0xFFEAF7F0)
internal val PracticeAmber = Color(0xFF976510)
internal val SoftAmber = Color(0xFFFFF4DF)
internal val PageBackground = Color(0xFFF3F6FB)

@Composable
fun OpenMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PracticeBlue,
            onPrimary = Color.White,
            primaryContainer = SoftBlue,
            onPrimaryContainer = Ink,
            secondary = PracticeGreen,
            secondaryContainer = SoftGreen,
            background = PageBackground,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFEDF1F7),
            onSurfaceVariant = MutedInk,
            outline = Color(0xFF8B9CB3),
            outlineVariant = Color(0xFFDBE4F0),
        ),
        typography = Typography(),
        content = content,
    )
}
