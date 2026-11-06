package me.ik.argon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ArgonColors {
    val Bg = Color(0xFF050507)
    val Surface = Color(0xFF101014)
    val SurfaceHigh = Color(0xFF1C1C23)
    val Text = Color(0xFFF5F5F7)
    val Text2 = Color(0xFF9B9BA4)
    val Text3 = Color(0xFF6E6E78)
    val Accent = Color(0xFF8B7CF6)
    val AccentSoft = Color(0x1F8B7CF6)
    val Danger = Color(0xFFFF6E64)
    val Hairline = Color(0x14FFFFFF)
}

@Composable
fun ArgonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = ArgonColors.Bg,
                surface = ArgonColors.Surface,
                surfaceVariant = ArgonColors.SurfaceHigh,
                primary = ArgonColors.Accent,
                onPrimary = Color(0xFF0B0B10),
                onBackground = ArgonColors.Text,
                onSurface = ArgonColors.Text,
                onSurfaceVariant = ArgonColors.Text2,
                error = ArgonColors.Danger,
            ),
        content = content,
    )
}
