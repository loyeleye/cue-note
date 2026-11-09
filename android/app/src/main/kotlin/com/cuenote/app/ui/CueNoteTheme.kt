package com.cuenote.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cuenote.app.R

/*
 * Cue Note's look, "Rehearsal Room": plum for anything you tap, brass for the
 * moment that matters, paper-cream pages, and Fraunces for headings over the
 * phone's own sans for everything you read. The dashboard (web/src/app.css)
 * and the sign-in emails use the same colours; change them together.
 *
 * Android's wallpaper colours (Material You) are left off on purpose, so the
 * app looks the same on every phone and matches the dashboard.
 */

/** The deep plum-black of a stage at night: dark mode, and the recording screen. */
val Stage = Color(0xFF1B1620)

private val Plum = Color(0xFF5B3A8C)
private val PlumDeep = Color(0xFF3E2566)
private val Lilac = Color(0xFFEEE6F7)
private val BrassInk = Color(0xFF7A5512) // brass for words: readable on paper
private val BrassSoft = Color(0xFFF6E9CF)
private val Ink = Color(0xFF231C2B)
private val Paper = Color(0xFFF7F3EC)
private val Muted = Color(0xFF645A6D)
private val Line = Color(0xFFE4DCCF)
private val ControlLine = Color(0xFF8C74B4)

private val LightColors = lightColorScheme(
    primary = Plum,
    onPrimary = Color.White,
    primaryContainer = Lilac,
    onPrimaryContainer = PlumDeep,
    inversePrimary = Color(0xFFC4A8F0),
    secondary = Color(0xFF6A5F73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1ECE3),
    onSecondaryContainer = Color(0xFF4A4152),
    tertiary = BrassInk,
    onTertiary = Color.White,
    tertiaryContainer = BrassSoft,
    onTertiaryContainer = Color(0xFF4A3208),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1ECE3),
    onSurfaceVariant = Muted,
    surfaceTint = Plum,
    outline = ControlLine,
    outlineVariant = Line,
    // Cards and sheets sit on the paper as white pages.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFAF6),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    // Plum itself is too dark to read on the night background; this lighter one passes.
    primary = Color(0xFFC4A8F0),
    onPrimary = Color(0xFF2A1747),
    primaryContainer = Color(0xFF433060),
    onPrimaryContainer = Lilac,
    inversePrimary = Plum,
    secondary = Color(0xFFCFC4D8),
    onSecondary = Color(0xFF2E2735),
    secondaryContainer = Color(0xFF3A3242),
    onSecondaryContainer = Color(0xFFE6DDEC),
    tertiary = Color(0xFFF0C978),
    onTertiary = Color(0xFF3A2706),
    tertiaryContainer = Color(0xFF5A4317),
    onTertiaryContainer = Color(0xFFFBE7C0),
    background = Stage,
    onBackground = Color(0xFFEEE8F2),
    surface = Stage,
    onSurface = Color(0xFFEEE8F2),
    surfaceVariant = Color(0xFF2E2735),
    onSurfaceVariant = Color(0xFFACA2B5),
    surfaceTint = Color(0xFFC4A8F0),
    outline = Color(0xFF7D6A99),
    outlineVariant = Color(0xFF3A3242),
    surfaceContainerLowest = Color(0xFF16121A),
    surfaceContainerLow = Color(0xFF211B26),
    surfaceContainer = Color(0xFF241E2A),
    surfaceContainerHigh = Color(0xFF2A2330),
    surfaceContainerHighest = Color(0xFF2E2735),
    error = Color(0xFFF2A49B),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Fraunces, a soft bookish serif, for headings only. Body text stays the phone's own font. */
val Fraunces = FontFamily(
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
)

private val CueTypography = Typography().run {
    fun TextStyle.serif() = copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold)
    copy(
        displayLarge = displayLarge.serif(),
        displayMedium = displayMedium.serif(),
        displaySmall = displaySmall.serif(),
        headlineLarge = headlineLarge.serif(),
        headlineMedium = headlineMedium.serif(),
        headlineSmall = headlineSmall.serif(),
        titleLarge = titleLarge.serif(),
        titleMedium = titleMedium.serif(),
    )
}

private val CueShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CueNoteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = CueTypography,
        shapes = CueShapes,
        content = content,
    )
}
