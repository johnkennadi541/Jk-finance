package com.skyplus.finance.presentation.theme

import androidx.compose.runtime.Composable
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusBrandTheme
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.domain.model.AppLanguage
import com.skyplus.finance.domain.model.AppThemeMode

/**
 * Facade delegating to the centralized SKYPLUS Corporate Design System tokens.
 */
object SkyPlusColors {
  val DeepSlate = SkyPlusBrandColors.DeepSlate
  val DeepSlateElevated = SkyPlusBrandColors.DeepSlateElevated
  val DeepNight = SkyPlusBrandColors.MidnightNavy
  val CardDark = SkyPlusBrandColors.DeepSlate
  val RoyalBlue = SkyPlusBrandColors.RoyalBlue
  val Emerald = SkyPlusBrandColors.EmeraldGreen
  val EmeraldDark = SkyPlusBrandColors.EmeraldGreenDark
  val SoftCyan = SkyPlusBrandColors.SoftCyan
  val WarmAmber = SkyPlusBrandColors.WarningAmber
  val CoralRed = SkyPlusBrandColors.ErrorCoral
  val CleanWhite = SkyPlusBrandColors.PureWhite
  val SoftSurfaceLight = SkyPlusBrandColors.LightBackground
  val TextPrimaryDark = SkyPlusBrandColors.TextPrimaryDark
  val TextSecondaryDark = SkyPlusBrandColors.TextSecondaryDark
}

val PlusJakartaFontFamily = SkyPlusFonts.PrimarySans
val JetBrainsMonoFamily = SkyPlusFonts.NumericMono

@Composable
fun SkyPlusTheme(
  themeMode: AppThemeMode = AppThemeMode.SYSTEM,
  @Suppress("UNUSED_PARAMETER") language: AppLanguage = AppLanguage.ENGLISH,
  content: @Composable () -> Unit,
) {
  SkyPlusBrandTheme(themeMode = themeMode, content = content)
}
