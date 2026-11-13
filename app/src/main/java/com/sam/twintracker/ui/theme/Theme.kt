package com.sam.twintracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Baby identity colours (categorical slots 1 & 2), stepped separately for light and dark surfaces.
private val Baby1Light = Color(0xFF2A78D6)
private val Baby2Light = Color(0xFFEB6834)
private val Baby1Dark = Color(0xFF3987E5)
private val Baby2Dark = Color(0xFFD95926)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2A5DA8),
    secondary = Color(0xFFB4532A),
    background = Color(0xFFFCFCFB),
    surface = Color(0xFFFCFCFB),
    surfaceVariant = Color(0xFFF0EFEC),
    onSurfaceVariant = Color(0xFF52514E),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC2F5),
    secondary = Color(0xFFF2A584),
    background = Color(0xFF121211),
    surface = Color(0xFF1A1A19),
    surfaceVariant = Color(0xFF2A2A28),
    onSurfaceVariant = Color(0xFFC3C2B7),
)

@Composable
fun babyColor(baby: Int): Color {
    val dark = isSystemInDarkTheme()
    return when (baby) {
        1 -> if (dark) Baby1Dark else Baby1Light
        else -> if (dark) Baby2Dark else Baby2Light
    }
}

/** Reserved status colours — always shown together with an icon and a label. */
object StatusColors {
    val good @Composable get() = if (isSystemInDarkTheme()) Color(0xFF4CB782) else Color(0xFF1E8A57)
    val warning @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE0A93B) else Color(0xFF9A6A00)
    val critical @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF08080) else Color(0xFFC62828)
}

@Composable
fun TwinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
