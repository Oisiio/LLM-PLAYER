package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = LlmPrimary,
    onPrimary = LlmTextPrimary,
    primaryContainer = LlmPrimaryContainer,
    onPrimaryContainer = LlmPrimaryLight,
    secondary = LlmPrimaryLight,
    onSecondary = LlmBackground,
    secondaryContainer = LlmContainerHigh,
    onSecondaryContainer = LlmTextPrimary,
    background = LlmBackground,
    onBackground = LlmTextPrimary,
    surface = LlmSurface,
    onSurface = LlmTextPrimary,
    surfaceVariant = LlmContainer,
    onSurfaceVariant = LlmTextSecondary,
    outline = LlmBorder,
    outlineVariant = LlmBorderSubtle,
    error = LlmError
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SleekBluePrimary,
    onPrimary = SleekBlueOnPrimary,
    primaryContainer = SleekBluePrimaryContainer,
    onPrimaryContainer = SleekBlueOnPrimaryContainer,
    secondary = SleekSecondary,
    onSecondary = SleekOnSecondary,
    secondaryContainer = SleekSecondaryContainer,
    onSecondaryContainer = SleekOnSecondaryContainer,
    background = SleekBackground,
    onBackground = SleekOnBackground,
    surface = SleekSurface,
    onSurface = SleekOnSurface,
    surfaceVariant = SleekSurfaceVariant,
    onSurfaceVariant = SleekOnSurfaceVariant,
    outline = SleekOutline
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
