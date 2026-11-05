package app.tapdrop.ui

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.tapdrop.R

// The TapDrop design system for Android. docs/DESIGN.md is the source of truth; this file turns it into
// tokens (Td.colors, Td.type, Td.space, Td.radius, Td.motion) and a Material 3 Expressive theme so stock
// components (switches, dialogs, text fields) pick up the brand too. Components live in app.tapdrop.ui.design.

// MARK: - brand

/** The "Aurora" brand colors. Exact values: they match the app icon and the demo video. */
object Brand {
    val Blue = Color(0xFF409EFF)
    val Violet = Color(0xFF8561FF)
    val Orchid = Color(0xFFF26BD9)

    val Success = Color(0xFF2ED17A)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5A5F)

    /** Extra hues for icon chips only (never for text or status). */
    val Teal = Color(0xFF22C6B0)
    val Sky = Color(0xFF5AC8FA)

    /** The one gradient element per view: primary buttons, hero glyphs, progress. */
    val gradient: Brush get() = Brush.linearGradient(listOf(Blue, Violet))

    /** Blue → violet → orchid, for the hero glow only. */
    val heroGradient: Brush get() = Brush.linearGradient(listOf(Blue, Violet, Orchid))

    val successGradient: Brush get() = Brush.linearGradient(listOf(Success, Color(0xFF17B863)))

    // Legacy names, still used by screens that haven't moved to the design system yet.
    val A = Blue
    val B = Violet
    val C = Orchid
    val Green = Success
    val GreenDeep = Color(0xFF17B863)
    val Red = Danger
}

// MARK: - color tokens

/**
 * Semantic colors for the current appearance. Read them with `Td.colors`.
 * Neutrals follow DESIGN.md; the `*Text` variants are the same hue tuned to stay legible as text.
 */
@Immutable
data class TdColors(
    val isDark: Boolean,
    /** Window background. */
    val bg: Color,
    /** Cards, groups, tiles. */
    val surface: Color,
    /** Sheets, popovers, pressed rows. */
    val surfaceRaised: Color,
    /** Wells and tracks (progress tracks, inactive switch tracks). */
    val surfaceSunken: Color,
    /** 1 px borders: white 8 % in dark, black 7 % in light. */
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Brand violet for fills (selection, focus, accents). */
    val accent: Color,
    /** Violet that reads as text on [bg]/[surface]. */
    val accentText: Color,
    /** Tonal violet container (icon chips, secondary buttons, selected rows). */
    val accentSoft: Color,
    val success: Color,
    val successText: Color,
    val warning: Color,
    val warningText: Color,
    val danger: Color,
    val dangerText: Color,
    /** Tint laid over a backdrop blur (top bars, sheets). */
    val glass: Color,
    /** The 1 px top-edge highlight on glass. */
    val glassEdge: Color,
    val scrim: Color,
    /** Violet hero glow (35–45 % alpha). */
    val glow: Color,
    val shadow: Color,
)

private val DarkTokens = TdColors(
    isDark = true,
    bg = Color(0xFF0A0A0F),
    surface = Color(0xFF15151C),
    surfaceRaised = Color(0xFF1C1C25),
    surfaceSunken = Color(0xFF101016),
    hairline = Color.White.copy(alpha = 0.08f),
    text = Color(0xFFF5F5FA),
    textSecondary = Color(0xFFA0A2B4),
    textTertiary = Color(0xFF6B6D80),
    accent = Brand.Violet,
    accentText = Color(0xFFA690FF),
    accentSoft = Brand.Violet.copy(alpha = 0.16f),
    success = Brand.Success,
    successText = Color(0xFF4BE095),
    warning = Brand.Warning,
    warningText = Color(0xFFFFC24F),
    danger = Brand.Danger,
    dangerText = Color(0xFFFF7E82),
    glass = Color(0xFF14141B).copy(alpha = 0.72f),
    glassEdge = Color.White.copy(alpha = 0.10f),
    scrim = Color.Black.copy(alpha = 0.55f),
    glow = Brand.Violet.copy(alpha = 0.42f),
    shadow = Color.Black,
)

private val LightTokens = TdColors(
    isDark = false,
    bg = Color(0xFFF4F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEAEAF0),
    hairline = Color.Black.copy(alpha = 0.07f),
    text = Color(0xFF111118),
    textSecondary = Color(0xFF5E6173),
    textTertiary = Color(0xFF8E90A2),
    accent = Brand.Violet,
    accentText = Color(0xFF6A45F0),
    accentSoft = Brand.Violet.copy(alpha = 0.11f),
    success = Brand.Success,
    successText = Color(0xFF138A4B),
    warning = Brand.Warning,
    warningText = Color(0xFF9A6200),
    danger = Brand.Danger,
    dangerText = Color(0xFFD5353B),
    glass = Color(0xFFF7F7FA).copy(alpha = 0.80f),
    glassEdge = Color.White.copy(alpha = 0.70f),
    scrim = Color.Black.copy(alpha = 0.32f),
    glow = Brand.Violet.copy(alpha = 0.30f),
    shadow = Color(0xFF1A1440),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9479FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2257),
    onPrimaryContainer = Color(0xFFE5DEFF),
    inversePrimary = Color(0xFF6A45F0),
    secondary = Brand.Blue,
    onSecondary = Color(0xFF001A33),
    secondaryContainer = Color(0xFF22202F),
    onSecondaryContainer = Color(0xFFE6E1FF),
    tertiary = Brand.Orchid,
    onTertiary = Color(0xFF3A0031),
    tertiaryContainer = Color(0xFF4A1A43),
    onTertiaryContainer = Color(0xFFFFD8F3),
    background = DarkTokens.bg,
    onBackground = DarkTokens.text,
    surface = DarkTokens.bg,
    onSurface = DarkTokens.text,
    surfaceVariant = DarkTokens.surfaceRaised,
    onSurfaceVariant = DarkTokens.textSecondary,
    surfaceTint = Brand.Violet,
    inverseSurface = DarkTokens.text,
    inverseOnSurface = DarkTokens.surface,
    error = DarkTokens.dangerText,
    onError = Color(0xFF2D0003),
    errorContainer = Color(0xFF3A1215),
    onErrorContainer = Color(0xFFFFD9DA),
    outline = DarkTokens.textTertiary,
    outlineVariant = Color(0xFF26262F),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A2A35),
    surfaceDim = DarkTokens.bg,
    surfaceContainerLowest = Color(0xFF08080C),
    surfaceContainerLow = DarkTokens.surface,
    surfaceContainer = Color(0xFF18181F),
    surfaceContainerHigh = DarkTokens.surfaceRaised,
    surfaceContainerHighest = Color(0xFF24242E),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF6A45F0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECE6FF),
    onPrimaryContainer = Color(0xFF26135F),
    inversePrimary = Color(0xFFA690FF),
    secondary = Color(0xFF1F7FE6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE8FB),
    onSecondaryContainer = Color(0xFF1F1A3A),
    tertiary = Color(0xFFC93DAE),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0F6),
    onTertiaryContainer = Color(0xFF3D0032),
    background = LightTokens.bg,
    onBackground = LightTokens.text,
    surface = LightTokens.bg,
    onSurface = LightTokens.text,
    surfaceVariant = Color(0xFFE6E6EE),
    onSurfaceVariant = LightTokens.textSecondary,
    surfaceTint = Brand.Violet,
    inverseSurface = Color(0xFF1C1C25),
    inverseOnSurface = Color(0xFFF5F5FA),
    error = LightTokens.dangerText,
    onError = Color.White,
    errorContainer = Color(0xFFFFE1E2),
    onErrorContainer = Color(0xFF5A0A0E),
    outline = LightTokens.textTertiary,
    outlineVariant = Color(0xFFDDDDE6),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCDCE4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightTokens.surface,
    surfaceContainer = Color(0xFFEFEFF5),
    surfaceContainerHigh = Color(0xFFE9E9F0),
    surfaceContainerHighest = Color(0xFFE2E2EA),
)

// MARK: - type

private fun geist(weight: Int) = Font(
    R.font.geist,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun geistMono(weight: Int) = Font(
    R.font.geist_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Geist (variable, SIL OFL; license in assets/licenses). Used for all text. */
val GeistFamily = FontFamily(geist(400), geist(500), geist(600), geist(700))

/** Geist Mono, for codes and numbers that must line up. */
val GeistMonoFamily = FontFamily(geistMono(400), geistMono(500), geistMono(600))

private val Trimless = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: TextUnit = 0.sp, family: FontFamily = GeistFamily) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking,
    lineHeightStyle = Trimless,
)

/** The DESIGN.md type scale. Read with `Td.type`; Material components get the same scale via `MaterialTheme.typography`. */
@Immutable
object TdType {
    /** 34/40 Bold, −1 %: screen titles on hero screens. */
    val display = style(34, 40, FontWeight.Bold, (-0.01).em)
    /** 26/32 SemiBold, −0.5 %: large titles, device names. */
    val headline = style(26, 32, FontWeight.SemiBold, (-0.005).em)
    /** 20/26 SemiBold: sheet titles, section heroes. */
    val title = style(20, 26, FontWeight.SemiBold, (-0.002).em)
    /** 17/22 SemiBold: row and tile titles. */
    val subtitle = style(17, 22, FontWeight.SemiBold)
    /** 16/24 Regular: running text. */
    val body = style(16, 24, FontWeight.Normal)
    /** 14/20 Regular: secondary lines under titles. */
    val bodySmall = style(14, 20, FontWeight.Normal)
    /** 14/20 Medium: buttons, pills, labels. */
    val label = style(14, 20, FontWeight.Medium)
    /** 12/16 Medium: captions, footnotes, section headers. */
    val caption = style(12, 16, FontWeight.Medium, 0.01.em)
    /** Geist Mono 16/24 Medium: codes, addresses. Tabular figures. */
    val mono = style(16, 24, FontWeight.Medium, 0.sp, GeistMonoFamily)
    /** Geist with tabular figures, for numbers that change (percentages, sizes). */
    val numeric = style(14, 20, FontWeight.Medium).copy(fontFeatureSettings = "tnum")
}

private val TdTypography = Typography(
    displayLarge = style(44, 52, FontWeight.Bold, (-0.012).em),
    displayMedium = style(38, 44, FontWeight.Bold, (-0.011).em),
    displaySmall = TdType.display,
    headlineLarge = style(30, 36, FontWeight.SemiBold, (-0.007).em),
    headlineMedium = TdType.headline,
    headlineSmall = style(22, 28, FontWeight.SemiBold, (-0.004).em),
    titleLarge = TdType.title,
    titleMedium = TdType.subtitle,
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = TdType.body,
    bodyMedium = TdType.bodySmall,
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = TdType.label,
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = TdType.caption,
)

// MARK: - space, radius, shapes

/** 4-pt grid. Standard paddings are 8 / 12 / 16 / 20 / 24 / 32. */
@Immutable
object TdSpace {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    /** Horizontal page gutter. */
    val gutter: Dp = 20.dp
    /** Minimum touch target. */
    val touch: Dp = 48.dp
}

/** Corner radii. Cards 28, settings groups 24 outside / 6 inside with 2 dp gaps, chips 12, buttons are pills. */
@Immutable
object TdRadius {
    val card: Dp = 28.dp
    val group: Dp = 24.dp
    val segment: Dp = 6.dp
    val segmentGap: Dp = 2.dp
    val chip: Dp = 12.dp
    val sheet: Dp = 32.dp
    val field: Dp = 16.dp
}

private val TdShapes = Shapes(
    extraSmall = RoundedCornerShape(TdRadius.segment),
    small = RoundedCornerShape(TdRadius.chip),
    medium = RoundedCornerShape(TdRadius.field),
    large = RoundedCornerShape(TdRadius.group),
    extraLarge = RoundedCornerShape(TdRadius.card),
)

// MARK: - motion

/**
 * Springs from DESIGN.md (SwiftUI response/damping converted with stiffness = (2π / response)²).
 * Everything that changes state animates with one of these; [quick] replaces them under reduced motion.
 */
@Immutable
object TdMotion {
    /** Response 0.38, damping 0.86: most state changes. */
    fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 273f)
    /** Response 0.5, damping 0.72: hero moments, things arriving. */
    fun <T> emphasized(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.72f, stiffness = 158f)
    /** Presses and toggles: fast with a little life. */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.62f, stiffness = 760f)
    /** Ambient changes (glows, backdrops): no overshoot. */
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 90f)
    /** The reduced-motion replacement: a quick fade. */
    fun <T> quick(): FiniteAnimationSpec<T> = tween(durationMillis = 140)
}

/** True when the user turned animations off (Settings → Accessibility → Remove animations). */
val LocalReducedMotion = staticCompositionLocalOf { false }

// MARK: - theme

private val LocalTdColors = staticCompositionLocalOf { DarkTokens }

/** One-stop access to the design tokens: `Td.colors.text`, `Td.type.title`, `Td.space.md`, `Td.motion.standard()`. */
object Td {
    val colors: TdColors
        @Composable @ReadOnlyComposable get() = LocalTdColors.current
    val type: TdType get() = TdType
    val space: TdSpace get() = TdSpace
    val radius: TdRadius get() = TdRadius
    val motion: TdMotion get() = TdMotion
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current

    /** [TdMotion.standard], or a quick fade under reduced motion. */
    @Composable @ReadOnlyComposable
    fun <T> spring(emphasized: Boolean = false): FiniteAnimationSpec<T> = when {
        LocalReducedMotion.current -> TdMotion.quick()
        emphasized -> TdMotion.emphasized()
        else -> TdMotion.standard()
    }
}

/**
 * The app theme: brand color schemes, Geist type scale, shapes, expressive motion, and the Td tokens.
 * Wrap every activity's content in it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TapDropTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val reduced = remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    val tokens = if (darkTheme) DarkTokens else LightTokens
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        motionScheme = if (reduced) MotionScheme.standard() else MotionScheme.expressive(),
        shapes = TdShapes,
        typography = TdTypography,
    ) {
        CompositionLocalProvider(
            LocalTdColors provides tokens,
            LocalReducedMotion provides reduced,
            // Text outside a Surface would otherwise default to black (invisible in dark mode).
            LocalContentColor provides tokens.text,
            content = content,
        )
    }
}

/** Section header style ("Send", "Recent"). Prefer the `SectionHeader` component. */
val sectionLabel: TextStyle
    @Composable get() = TdType.caption.copy(
        color = Td.colors.textSecondary,
        fontSize = 13.sp,
        letterSpacing = 0.02.em,
    )
