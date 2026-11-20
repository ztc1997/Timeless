package com.ztc1997.timeless.appconfig

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** 无时的 MD3 配色：偏夜空的紫罗兰 + 星月高亮色。 */
private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF5B4B9E),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFE8DEFF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF1E1440),
    secondary = androidx.compose.ui.graphics.Color(0xFF6A5E7D),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFF0E9F6),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF231D2E),
    tertiary = androidx.compose.ui.graphics.Color(0xFF8A5A00),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDFA8),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF2C1B00),
    surface = androidx.compose.ui.graphics.Color(0xFFFDF7FF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFECE4F5),
    background = androidx.compose.ui.graphics.Color(0xFFFDF7FF),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFC9B6FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF241A40),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF3C2E64),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFEADDFF),
    secondary = androidx.compose.ui.graphics.Color(0xFFD0C2DD),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF443A50),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFEDE3F5),
    tertiary = androidx.compose.ui.graphics.Color(0xFFFFD98A),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF614500),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFE4B3),
    surface = androidx.compose.ui.graphics.Color(0xFF16121F),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2636),
    background = androidx.compose.ui.graphics.Color(0xFF16121F),
)

@Composable
fun TimelessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
