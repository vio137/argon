package me.ik.argon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Argon design tokens v2 - "Argon discharge" direction (visual redesign,
 * built from scratch; supersedes the v1 "quiet instrument" azure system).
 *
 * Deep graphite base, layered translucent surfaces, hairline borders, and a
 * single argon-discharge violet accent reserved for selection, focus, the
 * secure indicator, links and the loading track. Depth comes from surface
 * steps, blur and hairlines - never heavy shadows or hue gradients.
 * (Direction record: DESIGN-DIRECTION-2.md. Unslop by Damir Sokolovsky, CC BY 4.0.)
 */
object ArgonColors {
    val Bg = Color(0xFF0B0C10)
    val BgRaise = Color(0xFF13151B)
    val Surface = Color(0xFF181A22)
    val SurfaceHigh = Color(0xFF21242E)
    val Text = Color(0xFFF4F5F8)
    val Text2 = Color(0xFFA8AEBC)
    val Text3 = Color(0xFF6E7584)
    val Accent = Color(0xFF9B8CFF)
    val AccentSoft = Color(0x1F9B8CFF)
    val OnAccent = Color(0xFF0D0A1A)
    val Danger = Color(0xFFFF7A70)
    val Hairline = Color(0x17FFFFFF)
    val HairlineStrong = Color(0x29FFFFFF)
}

// Shared type scale (macOS/Deepin direction: quiet hierarchy, restrained weights).
object ArgonType {
    val Hero = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp)
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val Heading = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val Body = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val BodySmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val Caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val Section =
        TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, letterSpacing = 1.1.sp)
    val Micro = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp)
}

// 4dp-base spacing scale.
object ArgonSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp
}

// v2 radii: controls 16, cards 20, the unified deck keeps a 26 capsule.
object ArgonRadii {
    val Control = 16.dp
    val Card = 20.dp
    val Panel = 26.dp
}

@Composable
fun ArgonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = ArgonColors.Bg,
                surfaceContainer = ArgonColors.BgRaise,
                surface = ArgonColors.Surface,
                surfaceVariant = ArgonColors.SurfaceHigh,
                primary = ArgonColors.Accent,
                onPrimary = ArgonColors.OnAccent,
                onBackground = ArgonColors.Text,
                onSurface = ArgonColors.Text,
                onSurfaceVariant = ArgonColors.Text2,
                error = ArgonColors.Danger,
            ),
        content = content,
    )
}
