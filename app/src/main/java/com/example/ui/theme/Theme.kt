package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val JarvisColorScheme = lightColorScheme(
  primary = BronzeAccent,
  onPrimary = Alabaster,
  primaryContainer = WarmLinenDeep,
  onPrimaryContainer = CharcoalInk,
  secondary = SageGranted,
  onSecondary = Alabaster,
  tertiary = AmberGlow,
  background = Alabaster,
  onBackground = CharcoalInk,
  surface = WarmLinen,
  onSurface = CharcoalInk,
  surfaceVariant = WarmLinenDeep,
  onSurfaceVariant = MutedStone,
  outline = HairlineBorder,
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = JarvisColorScheme, typography = Typography, content = content)
}
