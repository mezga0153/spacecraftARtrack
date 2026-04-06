package com.spacecraftartrack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MoonYellow = Color(0xFFFFD966)
val ArtemisOrange = Color(0xFFFF6B35)
val SunYellow = Color(0xFFFFD740)
val SpaceBlue = Color(0xFF0D1B2A)
val StarWhite = Color(0xFFF0F0FF)

private val DarkColorScheme = darkColorScheme(
    primary = ArtemisOrange,
    secondary = MoonYellow,
    background = SpaceBlue,
    surface = Color(0xFF1A2940),
    onPrimary = Color.White,
    onSecondary = SpaceBlue,
    onBackground = StarWhite,
    onSurface = StarWhite,
)

@Composable
fun SpacecraftARTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
