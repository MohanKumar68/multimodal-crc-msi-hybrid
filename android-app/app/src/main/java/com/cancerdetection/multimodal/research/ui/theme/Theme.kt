package com.cancerdetection.multimodal.research.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = PathologyTeal,
    onPrimary = Color.White,
    primaryContainer = PathologyTealContainer,
    onPrimaryContainer = PathologyOnTealContainer,
    secondary = PathologyNavyMedium,
    onSecondary = Color.White,
    secondaryContainer = PathologySurfaceVariant,
    onSecondaryContainer = PathologyNavy,
    tertiary = PathologyTealVibrant,
    onTertiary = Color.White,
    background = PathologyBackground,
    onBackground = PathologyNavy,
    surface = PathologySurface,
    onSurface = PathologyNavy,
    surfaceVariant = PathologySurfaceVariant,
    onSurfaceVariant = PathologyTextSecondary,
    outline = PathologyCardBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = StatusErrorColor,
    errorContainer = StatusErrorContainer,
    onError = Color.White,
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun MultimodalResearchTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
