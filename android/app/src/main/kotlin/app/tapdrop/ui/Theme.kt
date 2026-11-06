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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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

/**
 * "Lumen": light, in the dark. A tap is the phone's screen lighting the notch and going dark, so TapDrop is made of
 * night and light: near-black, bone white, and a cool glow where they meet. There is no accent hue; colour comes from
 * the user's own photos. Semantic colours (danger, warning) appear only where something needs fixing.
 */
object Brand {
    /** Near-black: the dark background, ink in light mode. */
    val Night = Color(0xFF08080A)
    /** Raised dark surfaces. */
    val Graphite = Color(0xFF17171A)
    /** Bone white: text in dark mode, primary fills, light backgrounds. */
    val Bone = Color(0xFFF4F3EF)
    /** Ink for light mode text and fills. */
    val Ink = Color(0xFF0B0B0D)
    /** The glow: the cool white of a screen's light. */
    val Light = Color(0xFFD6DEFF)

    val Danger = Color(0xFFFF7A72)
    val Warning = Color(0xFFF0B45A)
}

// MARK: - color tokens

/**
 * Semantic colors for the current appearance. Read them with `Td.colors`.
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
    /** The primary fill: bone in dark mode, ink in light (primary buttons, selection, progress). */
    val accent: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    /** Emphasis that reads as text on [bg]/[surface] (links, selected labels). */
    val accentText: Color,
    /** A quiet tonal fill (secondary buttons, selected rows, icon wells). */
    val accentSoft: Color,
    /** "It worked": marks, ticks and live dots. Bone/ink with the glow, never green. */
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
    /** The light: the halo around live dots, the spill under the notch line. */
    val glow: Color,
    val shadow: Color,
)

private val DarkTokens = TdColors(
    isDark = true,
    bg = Brand.Night,
    surface = Color(0xFF111114),
    surfaceRaised = Brand.Graphite,
    surfaceSunken = Color(0xFF0D0D10),
    hairline = Color.White.copy(alpha = 0.08f),
    text = Brand.Bone,
    textSecondary = Color(0xFFA2A2A8),
    textTertiary = Color(0xFF6E6E75),
    accent = Brand.Bone,
    onAccent = Brand.Night,
    accentText = Brand.Bone,
    accentSoft = Color.White.copy(alpha = 0.07f),
    success = Brand.Bone,
    successText = Brand.Bone,
    warning = Brand.Warning,
    warningText = Color(0xFFF5C77E),
    danger = Brand.Danger,
    dangerText = Color(0xFFFF9A93),
    glass = Color(0xFF121216).copy(alpha = 0.72f),
    glassEdge = Color.White.copy(alpha = 0.10f),
    scrim = Color.Black.copy(alpha = 0.6f),
    glow = Brand.Light.copy(alpha = 0.55f),
    shadow = Color.Black,
)

private val LightTokens = TdColors(
    isDark = false,
    bg = Color(0xFFEFEDE7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFE6E3DB),
    hairline = Color.Black.copy(alpha = 0.07f),
    text = Brand.Ink,
    textSecondary = Color(0xFF5F5E59),
    textTertiary = Color(0xFF8C8A84),
    accent = Brand.Ink,
    onAccent = Brand.Bone,
    accentText = Brand.Ink,
    accentSoft = Color.Black.copy(alpha = 0.055f),
    success = Brand.Ink,
    successText = Brand.Ink,
    warning = Color(0xFFC98A1E),
    warningText = Color(0xFF8A5A00),
    danger = Color(0xFFD9443C),
    dangerText = Color(0xFFB8322B),
    glass = Color(0xFFF7F6F2).copy(alpha = 0.82f),
    glassEdge = Color.White.copy(alpha = 0.75f),
    scrim = Color.Black.copy(alpha = 0.32f),
    glow = Color.White.copy(alpha = 0.95f),
    shadow = Color(0xFF1A1814),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Brand.Bone,
    onPrimary = Brand.Night,
    primaryContainer = Color(0xFF26262B),
    onPrimaryContainer = Brand.Bone,
    inversePrimary = Brand.Ink,
    secondary = Color(0xFFC9C8C2),
    onSecondary = Brand.Night,
    secondaryContainer = Color(0xFF1E1E22),
    onSecondaryContainer = Brand.Bone,
    tertiary = Brand.Light,
    onTertiary = Brand.Night,
    tertiaryContainer = Color(0xFF22242C),
    onTertiaryContainer = Brand.Light,
    background = DarkTokens.bg,
    onBackground = DarkTokens.text,
    surface = DarkTokens.bg,
    onSurface = DarkTokens.text,
    surfaceVariant = DarkTokens.surfaceRaised,
    onSurfaceVariant = DarkTokens.textSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = DarkTokens.text,
    inverseOnSurface = DarkTokens.surface,
    error = DarkTokens.dangerText,
    onError = Color(0xFF2D0003),
    errorContainer = Color(0xFF3A1513),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = DarkTokens.textTertiary,
    outlineVariant = Color(0xFF26262B),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A2A2F),
    surfaceDim = DarkTokens.bg,
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = DarkTokens.surface,
    surfaceContainer = Color(0xFF141417),
    surfaceContainerHigh = DarkTokens.surfaceRaised,
    surfaceContainerHighest = Color(0xFF212125),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Brand.Ink,
    onPrimary = Brand.Bone,
    primaryContainer = Color(0xFFE2DFD7),
    onPrimaryContainer = Brand.Ink,
    inversePrimary = Brand.Bone,
    secondary = Color(0xFF3E3D39),
    onSecondary = Brand.Bone,
    secondaryContainer = Color(0xFFE6E3DB),
    onSecondaryContainer = Brand.Ink,
    tertiary = Color(0xFF3B4466),
    onTertiary = Brand.Bone,
    tertiaryContainer = Color(0xFFE3E6F0),
    onTertiaryContainer = Color(0xFF1B2035),
    background = LightTokens.bg,
    onBackground = LightTokens.text,
    surface = LightTokens.bg,
    onSurface = LightTokens.text,
    surfaceVariant = Color(0xFFE6E3DB),
    onSurfaceVariant = LightTokens.textSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF1C1C1F),
    inverseOnSurface = Brand.Bone,
    error = LightTokens.dangerText,
    onError = Color.White,
    errorContainer = Color(0xFFFDE2DF),
    onErrorContainer = Color(0xFF5A0E0A),
    outline = LightTokens.textTertiary,
    outlineVariant = Color(0xFFDCD9D1),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDDAD2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightTokens.surface,
    surfaceContainer = Color(0xFFF4F2EC),
    surfaceContainerHigh = Color(0xFFEDEAE3),
    surfaceContainerHighest = Color(0xFFE6E3DB),
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
val GeistFamily = FontFamily(geist(300), geist(400), geist(500), geist(600), geist(700))

/** Geist Mono, for codes and numbers that must line up. */
val GeistMonoFamily = FontFamily(geistMono(400), geistMono(500), geistMono(600))

/** Instrument Serif (SIL OFL; license in assets/licenses), regular and italic: the display face for the big moments. */
val SerifFamily = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

private val Trimless = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: TextUnit = 0.sp, family: FontFamily = GeistFamily) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking,
    lineHeightStyle = Trimless,
)

/** The type scale. Read with `Td.type`; Material components get the same scale via `MaterialTheme.typography`. */
@Immutable
object TdType {
    /** Instrument Serif 62/60, −2 %: the one big line on a hero screen ("Ready to tap."). */
    val hero = style(62, 60, FontWeight.Normal, (-0.02).em, SerifFamily)
    /** Instrument Serif 42/44, −2 %: screen titles. */
    val display = style(42, 44, FontWeight.Normal, (-0.02).em, SerifFamily)
    /** Geist 24/30 Medium, −2 %: device names, section heroes. */
    val headline = style(24, 30, FontWeight.Medium, (-0.02).em)
    /** Geist 19/24 Medium, −1 %: sheet titles. */
    val title = style(19, 24, FontWeight.Medium, (-0.01).em)
    /** Geist 16/22 Medium: row and tile titles. */
    val subtitle = style(16, 22, FontWeight.Medium, (-0.005).em)
    /** Geist 16/24 Regular: running text. */
    val body = style(16, 24, FontWeight.Normal)
    /** Geist 14/20 Regular: secondary lines under titles. */
    val bodySmall = style(14, 20, FontWeight.Normal)
    /** Geist 14/20 Medium: buttons, pills, labels. */
    val label = style(14, 20, FontWeight.Medium)
    /** Geist 12/16 Regular: captions and footnotes. */
    val caption = style(12, 16, FontWeight.Normal, 0.005.em)
    /** Geist Mono 11/14, +14 %, set in capitals: section headers and readings ("RECENT", "112 LX"). */
    val eyebrow = style(11, 14, FontWeight.Normal, 0.14.em, GeistMonoFamily)
    /** Geist Mono 16/24 Medium: codes, addresses. Tabular figures. */
    val mono = style(16, 24, FontWeight.Medium, 0.sp, GeistMonoFamily)
    /** Geist with tabular figures, for numbers that change (percentages, sizes). */
    val numeric = style(14, 20, FontWeight.Medium).copy(fontFeatureSettings = "tnum")
    /** Geist Light 26/30, −2 %, tabular: big readings (lux, counts). */
    val reading = style(26, 30, FontWeight.Light, (-0.02).em).copy(fontFeatureSettings = "tnum")
}

private val TdTypography = Typography(
    displayLarge = TdType.hero,
    displayMedium = style(50, 52, FontWeight.Normal, (-0.02).em, SerifFamily),
    displaySmall = TdType.display,
    headlineLarge = style(30, 36, FontWeight.Medium, (-0.02).em),
    headlineMedium = TdType.headline,
    headlineSmall = style(21, 26, FontWeight.Medium, (-0.015).em),
    titleLarge = TdType.title,
    titleMedium = TdType.subtitle,
    titleSmall = style(15, 20, FontWeight.Medium),
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
fun TapDropTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // System, unless Settings → Appearance says Light or Dark.
    val appearance by app.tapdrop.TapDropApp.instance.prefs.appearance.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = darkTheme ?: when (appearance) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val reduced = remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    val tokens = if (dark) DarkTokens else LightTokens
    // The status and navigation bars follow the app's look, not only the system's (Settings → Appearance).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
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

/** Section header style ("SEND", "RECENT"): Geist Mono capitals. Prefer the `SectionHeader` component. */
val sectionLabel: TextStyle
    @Composable get() = TdType.eyebrow.copy(color = Td.colors.textSecondary)
