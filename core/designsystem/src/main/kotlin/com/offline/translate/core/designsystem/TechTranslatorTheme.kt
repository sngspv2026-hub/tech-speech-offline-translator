package com.offline.translate.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF66D9EF),
    onPrimary = Color(0xFF003640),
    primaryContainer = Color(0xFF004E5C),
    onPrimaryContainer = Color(0xFFB8EAFF),
    secondary = Color(0xFFA6E22E),
    onSecondary = Color(0xFF1F3700),
    tertiary = Color(0xFFFD971F),
    background = Color(0xFF121619),
    surface = Color(0xFF1B2126),
    surfaceVariant = Color(0xFF262F36)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006778),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EBFF),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF3E6A00),
    onSecondary = Color.White,
    tertiary = Color(0xFF8B5000),
    background = Color(0xFFF6FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE0E8EC)
)

val TranslatorTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    )
)

@Composable
fun TechTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = TranslatorTypography,
        content = content
    )
}
