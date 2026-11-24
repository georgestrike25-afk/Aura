package app.aura.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aura.core.settings.ThemeMode

/**
 * Paleta «Artistic Evolution»: obsidiana, grafito, champán, marfil y jade (ver docs/DISENO.md).
 * Los nombres anteriores (Night, Deep, Solar…) quedan como alias para no romper pantallas existentes.
 */
object AuraPalette {
    val Obsidian = Color(0xFF111418)
    val Graphite = Color(0xFF20262C)
    val Champagne = Color(0xFFD8BA8B)
    val Ivory = Color(0xFFEDE9E1)
    val Jade = Color(0xFF719A91)

    val Night = Obsidian
    val Deep = Graphite
    val Petrol = Jade
    val Solar = Champagne
    val White = Color(0xFFFFFFFF)
    val Mist = Ivory
    val Paper = Color(0xFFF5F2EC)

    // Escalones tonales derivados (no son colores de marca).
    val NightRaised = Color(0xFF181C21)
    val Ink = Obsidian
    val InkMuted = Color(0xFF5E636A)
    val MistMuted = Color(0xFFA9A49A)
    val SolarDeep = Color(0xFF8E6E3E)
    val JadeDeep = Color(0xFF4C7068)
    val Danger = Color(0xFFD9826B)
}

/** Tokens semánticos propios que Material 3 no modela. */
@Immutable
data class AuraTokens(
    val accent: Color,
    val onAccent: Color,
    val heroText: Color,
    val mutedText: Color,
    val card: Color,
    val cardBorder: Color,
    val stale: Color,
)

private val DarkTokens = AuraTokens(
    accent = AuraPalette.Solar,
    onAccent = AuraPalette.Night,
    heroText = AuraPalette.White,
    mutedText = AuraPalette.MistMuted,
    card = AuraPalette.NightRaised,
    cardBorder = Color(0x1AD8BA8B),
    stale = AuraPalette.Solar,
)

private val LightTokens = AuraTokens(
    accent = AuraPalette.SolarDeep,
    onAccent = AuraPalette.Night,
    heroText = AuraPalette.Ink,
    mutedText = AuraPalette.InkMuted,
    card = AuraPalette.White,
    cardBorder = Color(0x1A8E6E3E),
    stale = AuraPalette.SolarDeep,
)

val LocalAuraTokens = staticCompositionLocalOf { DarkTokens }

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = AuraPalette.Solar,
    onPrimary = AuraPalette.Night,
    primaryContainer = AuraPalette.Graphite,
    onPrimaryContainer = AuraPalette.Ivory,
    secondary = AuraPalette.Jade,
    onSecondary = AuraPalette.Obsidian,
    secondaryContainer = AuraPalette.Deep,
    onSecondaryContainer = AuraPalette.White,
    tertiary = AuraPalette.Jade,
    background = AuraPalette.Obsidian,
    onBackground = AuraPalette.Ivory,
    surface = AuraPalette.Obsidian,
    onSurface = AuraPalette.Ivory,
    surfaceVariant = AuraPalette.Graphite,
    onSurfaceVariant = AuraPalette.Ivory,
    surfaceContainer = AuraPalette.NightRaised,
    surfaceContainerHigh = AuraPalette.Deep,
    surfaceContainerLow = AuraPalette.NightRaised,
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = AuraPalette.Danger,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = AuraPalette.Graphite,
    onPrimary = AuraPalette.Ivory,
    primaryContainer = AuraPalette.Ivory,
    onPrimaryContainer = AuraPalette.Obsidian,
    secondary = AuraPalette.JadeDeep,
    onSecondary = AuraPalette.White,
    secondaryContainer = AuraPalette.Mist,
    onSecondaryContainer = AuraPalette.Night,
    tertiary = AuraPalette.JadeDeep,
    background = AuraPalette.Paper,
    onBackground = AuraPalette.Ink,
    surface = AuraPalette.Paper,
    onSurface = AuraPalette.Ink,
    surfaceVariant = AuraPalette.Ivory,
    onSurfaceVariant = AuraPalette.InkMuted,
    surfaceContainer = AuraPalette.White,
    surfaceContainerHigh = AuraPalette.Ivory,
    surfaceContainerLow = AuraPalette.White,
    outline = Color(0x33101D2B),
    outlineVariant = Color(0x1A101D2B),
)

/** Números tabulares: la hora no "baila" al cambiar de minuto. */
private const val TABULAR = "tnum"

private val AuraTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 88.sp, lineHeight = 92.sp, letterSpacing = (-2).sp, fontFeatureSettings = TABULAR),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TABULAR),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

private val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun AuraTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAuraTokens provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AuraTypography,
            shapes = AuraShapes,
            content = content,
        )
    }
}

object Aura {
    val tokens: AuraTokens
        @Composable get() = LocalAuraTokens.current
}
