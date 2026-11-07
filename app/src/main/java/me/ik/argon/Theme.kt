package me.ik.argon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ArgonColors {
    val Bg = Color(0xFF050507)
    val Surface = Color(0xFF101014)
    val SurfaceHigh = Color(0xFF1C1C23)
    val Text = Color(0xFFF5F5F7)
    val Text2 = Color(0xFF9B9BA4)
    val Text3 = Color(0xFF6E6E78)
    val Accent = Color(0xFF8B7CF6)
    val AccentSoft = Color(0x1F8B7CF6)
    val OnAccent = Color(0xFF0B0B10)
    val Danger = Color(0xFFFF6E64)
    val Hairline = Color(0x14FFFFFF)
}

// Shared type scale (macOS/Deepin direction: quiet hierarchy, restrained weights).
object ArgonType {
    val Hero = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
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
