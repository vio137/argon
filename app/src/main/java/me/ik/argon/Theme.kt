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
 * Argon design tokens - "quiet instrument" direction.
 *
 * World: argon the element - dark lab, machined graphite, one cool discharge
 * glow. All chrome comes from one cool-tinted neutral ramp; the azure accent
 * is reserved for selection, focus, links and the loading track. Depth comes
 * from surface steps and hairlines, never heavy shadows or hue gradients.
 * (Direction record: DESIGN-DIRECTION.md. Unslop by Damir Sokolovsky, CC BY 4.0.)
 */
object ArgonColors {
    val Bg = Color(0xFF08090C)
    val Surface = Color(0xFF101318)
    val SurfaceHigh = Color(0xFF181C23)
    val Text = Color(0xFFF2F4F8)
    val Text2 = Color(0xFF9AA1AD)
    val Text3 = Color(0xFF626A76)
    val Accent = Color(0xFF7AA7FF)
    val AccentSoft = Color(0x1F7AA7FF)
    val OnAccent = Color(0xFF0A0D14)
    val Danger = Color(0xFFFF7A70)
    val Hairline = Color(0x12FFFFFF)
    val HairlineStrong = Color(0x1FFFFFFF)
}

// Shared type scale (macOS/Deepin direction: quiet hierarchy, restrained weights).
object ArgonType {
    val Hero = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp)
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val Heading = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val Body = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val BodySmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val Caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)

    // Long-form reading (Reader view): article display sizes sit between
    // Title and Hero, with a looser measure for paragraphs.
    val ArticleTitle = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, lineHeight = 31.sp)
    val ArticleBody = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 27.sp)
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

// Nested radii: controls 10, cards 14-16, the command capsule stays a capsule.
object ArgonRadii {
    val Control = 10.dp
    val Card = 14.dp
    val Panel = 16.dp
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
                onPrimary = ArgonColors.OnAccent,
                onBackground = ArgonColors.Text,
                onSurface = ArgonColors.Text,
                onSurfaceVariant = ArgonColors.Text2,
                error = ArgonColors.Danger,
            ),
        content = content,
    )
}
