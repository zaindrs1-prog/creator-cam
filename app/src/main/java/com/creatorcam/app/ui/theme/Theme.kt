package com.creatorcam.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.creatorcam.app.settings.AppTheme

private val DarkScheme = darkColorScheme(
    primary = CreatorColors.Record,
    onPrimary = Color.White,
    secondary = CreatorColors.Info,
    background = CreatorColors.Background,
    onBackground = CreatorColors.OnBackground,
    surface = CreatorColors.Surface,
    onSurface = CreatorColors.OnBackground,
    surfaceVariant = CreatorColors.SurfaceHigh,
    onSurfaceVariant = CreatorColors.OnSurfaceMuted,
    outline = CreatorColors.Outline,
    error = CreatorColors.Danger,
)

private val LightScheme = lightColorScheme(
    primary = CreatorColors.Record,
    onPrimary = Color.White,
    secondary = Color(0xFF0B5FFF),
    background = Color(0xFFF6F7F8),
    onBackground = Color(0xFF101418),
    surface = Color.White,
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE9ECEF),
    onSurfaceVariant = Color(0xFF5B6470),
    error = CreatorColors.Danger,
)

@Composable
fun CreatorCamTheme(
    theme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = CreatorTypography,
        content = content,
    )
}
