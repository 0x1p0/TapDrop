package app.tapdrop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Brand {
    val A = Color(0xFF409EFF)
    val B = Color(0xFF8561FF)
    val C = Color(0xFFF26BD9)
    val Green = Color(0xFF34C77B)
    val GreenDeep = Color(0xFF1FA463)
    val Red = Color(0xFFFF5A5F)
    val gradient get() = Brush.linearGradient(listOf(A, B))
    val successGradient get() = Brush.linearGradient(listOf(Green, GreenDeep))
}

private val Dark = darkColorScheme(
    primary = Color(0xFF9A8BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2C2560),
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondary = Color(0xFF6CB6FF),
    onSecondary = Color(0xFF00213F),
    tertiary = Color(0xFFFF84DF),
    background = Color(0xFF0A0B10),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF0A0B10),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF1E1F2A),
    onSurfaceVariant = Color(0xFFA2A4B6),
    surfaceContainerLowest = Color(0xFF07080C),
    surfaceContainerLow = Color(0xFF111219),
    surfaceContainer = Color(0xFF16171F),
    surfaceContainerHigh = Color(0xFF1D1E28),
    surfaceContainerHighest = Color(0xFF252633),
    outline = Color(0xFF3A3C4D),
    outlineVariant = Color(0xFF262735),
    error = Color(0xFFFF6B6B),
    scrim = Color.Black,
)

private val Light = lightColorScheme(
    primary = Color(0xFF6A4DF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E4FF),
    onPrimaryContainer = Color(0xFF23126B),
    secondary = Color(0xFF2F8CF0),
    onSecondary = Color.White,
    tertiary = Color(0xFFD94DB8),
    background = Color(0xFFF6F6FA),
    onBackground = Color(0xFF12131A),
    surface = Color(0xFFF6F6FA),
    onSurface = Color(0xFF12131A),
    surfaceVariant = Color(0xFFE6E6EF),
    onSurfaceVariant = Color(0xFF5E6173),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F0F6),
    surfaceContainerHigh = Color(0xFFE9E9F1),
    surfaceContainerHighest = Color(0xFFE1E1EB),
    outline = Color(0xFFC9CAD8),
    outlineVariant = Color(0xFFE3E3EC),
    error = Color(0xFFE5484D),
    scrim = Color.Black,
)

private val base = Typography()
private val AppType = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun TapDropTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppType) {
        // Text outside a Surface would otherwise default to black (invisible in dark mode).
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

val sectionLabel: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.2.sp,
    )
