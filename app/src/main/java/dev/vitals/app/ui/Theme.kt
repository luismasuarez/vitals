package dev.vitals.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF06080B)
    val Surface = Color(0xFF0E1218)
    val SurfaceLow = Color(0xFF0B0F14)
    val Surface2 = Color(0xFF141A22)
    val Surface3 = Color(0xFF1A222C)
    val Line = Color(0x12FFFFFF)
    val LineStrong = Color(0x24FFFFFF)
    val Text = Color(0xFFE8EDF4)
    val Muted = Color(0xFF8B97A8)
    val Dim = Color(0xFF5C6675)
    val Accent = Color(0xFF7BF1A8)
    val AccentDim = Color(0x1F7BF1A8)
    val Rx = Color(0xFF5AA9FF)
    val Tx = Color(0xFFFF8F6B)
    val Warn = Color(0xFFF5D76E)
    val Danger = Color(0xFFFF6B6B)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF06120C),
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface2,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.LineStrong,
    error = Palette.Danger,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun VitalsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
