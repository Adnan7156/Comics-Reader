package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = NaturalMossGold,
    onPrimary = NaturalDeepOlive,
    primaryContainer = NaturalOliveContainer,
    onPrimaryContainer = NaturalLightCream,
    secondary = NaturalSandVariant,
    onSecondary = NaturalSiltGray,
    background = NaturalCharcoalBg,
    onBackground = NaturalLightCream,
    surface = NaturalSiltGray,
    onSurface = NaturalLightCream,
    surfaceVariant = NaturalSageVariant,
    onSurfaceVariant = NaturalSandVariant,
    outline = NaturalSlateTaupe
)

private val LightColorScheme = lightColorScheme(
    primary = NaturalWarmOlive,
    onPrimary = NaturalAlabaster,
    primaryContainer = NaturalSoftSand,
    onPrimaryContainer = NaturalWoodBark,
    secondary = NaturalSlateTaupe,
    onSecondary = NaturalAlabaster,
    background = NaturalAlabaster,
    onBackground = NaturalDarkCharcoal,
    surface = NaturalAlabaster,
    onSurface = NaturalDarkCharcoal,
    surfaceVariant = NaturalPebbleGray,
    onSurfaceVariant = NaturalWoodBark,
    outline = NaturalSoftSand
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
