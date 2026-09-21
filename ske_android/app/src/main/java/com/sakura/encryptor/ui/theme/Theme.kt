package com.sakura.encryptor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Lets any composable read the currently selected accent. */
val LocalAccentTheme: ProvidableCompositionLocal<AccentTheme> =
    staticCompositionLocalOf { AccentTheme.Indigo }

private fun buildLight(accent: AccentTheme) = lightColorScheme(
    primary = lightAccent(accent).primary,
    onPrimary = lightAccent(accent).onPrimary,
    primaryContainer = lightAccent(accent).primaryContainer,
    onPrimaryContainer = lightAccent(accent).onPrimaryContainer,
    secondary = lightAccent(accent).secondary,
    onSecondary = lightAccent(accent).onSecondary,
    secondaryContainer = lightAccent(accent).secondaryContainer,
    onSecondaryContainer = lightAccent(accent).onSecondaryContainer,
    tertiary = lightAccent(accent).tertiary,
    onTertiary = lightAccent(accent).onTertiary,
    background = Neutral.BackgroundLight,
    onBackground = Neutral.OnBackgroundLight,
    surface = Neutral.SurfaceLight,
    onSurface = Neutral.OnBackgroundLight,
    surfaceVariant = Neutral.SurfaceVariantLight,
    onSurfaceVariant = Neutral.OnSurfaceVariantLight,
    surfaceContainer = Neutral.SurfaceContainerLight,
    surfaceContainerHigh = Neutral.SurfaceVariantLight,
    outline = Neutral.OutlineLight,
    outlineVariant = Neutral.OutlineVariantLight,
    error = Neutral.ErrorLight,
    onError = Neutral.OnErrorLight,
    errorContainer = Neutral.ErrorContainerLight,
    scrim = Color(0x99000000),
)

private fun buildDark(accent: AccentTheme) = darkColorScheme(
    primary = darkAccent(accent).primary,
    onPrimary = darkAccent(accent).onPrimary,
    primaryContainer = darkAccent(accent).primaryContainer,
    onPrimaryContainer = darkAccent(accent).onPrimaryContainer,
    secondary = darkAccent(accent).secondary,
    onSecondary = darkAccent(accent).onSecondary,
    secondaryContainer = darkAccent(accent).secondaryContainer,
    onSecondaryContainer = darkAccent(accent).onSecondaryContainer,
    tertiary = darkAccent(accent).tertiary,
    onTertiary = darkAccent(accent).onTertiary,
    background = Neutral.BackgroundDark,
    onBackground = Neutral.OnBackgroundDark,
    surface = Neutral.SurfaceDark,
    onSurface = Neutral.OnBackgroundDark,
    surfaceVariant = Neutral.SurfaceVariantDark,
    onSurfaceVariant = Neutral.OnSurfaceVariantDark,
    surfaceContainer = Neutral.SurfaceContainerDark,
    surfaceContainerHigh = Neutral.SurfaceVariantDark,
    outline = Neutral.OutlineDark,
    outlineVariant = Neutral.OutlineVariantDark,
    error = Neutral.ErrorDark,
    onError = Neutral.OnErrorDark,
    errorContainer = Neutral.ErrorContainerDark,
    scrim = Color(0xCC000000),
)

/**
 * App theme.
 *
 * @param accent the accent palette; switchable at runtime from Settings.
 * @param darkTheme follow the system by default.
 */
@Composable
fun SakuraTheme(
    accent: AccentTheme = AccentTheme.Indigo,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) buildDark(accent) else buildLight(accent)

    CompositionLocalProvider(LocalAccentTheme provides accent) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SakuraTypography,
            shapes = SakuraShapes,
            content = content,
        )
    }
}
