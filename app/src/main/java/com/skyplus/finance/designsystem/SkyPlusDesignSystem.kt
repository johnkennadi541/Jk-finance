package com.skyplus.finance.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.skyplus.finance.domain.model.AppThemeMode

/**
 * Official SKYPLUS Corporate Brand Colour Tokens.
 * Shared across all SKYPLUS financial and wealth applications.
 */
object SkyPlusBrandColors {
  // Primary & Secondary Corporate Brand Colours (Deep Ocean Blue, Royal Blue, Teal, Emerald, Soft Cyan, Gold)
  val DeepOceanBlue = Color(0xFF0A2E5C)
  val RoyalBlue = Color(0xFF2878D0)
  val RoyalBlueDark = Color(0xFF1D5FA8)
  val RoyalBlueBright = Color(0xFF4F96E8)
  val RoyalBlueContainerLight = Color(0xFFDCEBFA)
  val RoyalBlueContainerDark = Color(0xFF12365E)

  val Teal = Color(0xFF009683)
  val EmeraldGreen = Color(0xFF00BFA6)
  val EmeraldGreenDark = Color(0xFF009683)
  val EmeraldContainerLight = Color(0xFFD0F5EF)
  val EmeraldContainerDark = Color(0xFF06443B)

  // Premium Gold Accents (Wealth Highlights & Badges)
  val GoldAccent = Color(0xFFD4AF37)
  val GoldSoft = Color(0xFFF3D574)
  val GoldContainerLight = Color(0xFFFEF7E0)
  val GoldContainerDark = Color(0xFF3E3008)

  // Supporting Corporate Colours
  val MidnightNavy = Color(0xFF071B35)
  val DeepSlate = Color(0xFF102A43)
  val DeepSlateElevated = Color(0xFF1A3654)
  val PureWhite = Color(0xFFFFFFFF)
  val LightBackground = Color(0xFFF3F7FC)
  val SoftSurfaceVariantLight = Color(0xFFE6EEF8)
  val SoftCyan = Color(0xFF42D9F5)
  val SoftCyanContainerLight = Color(0xFFDDF7FD)
  val SoftCyanContainerDark = Color(0xFF0E4552)

  // Contrast & Neutral Text Tokens
  val TextPrimaryLight = Color(0xFF071B35)
  val TextSecondaryLight = Color(0xFF486581)
  val OutlineLight = Color(0xFFCBD9EA)
  val TextPrimaryDark = Color(0xFFF3F7FC)
  val TextSecondaryDark = Color(0xFF9FB3C8)
  val OutlineDark = Color(0xFF274566)

  // Functional Warning & Error Colours (used strictly for alerts/warnings/errors)
  val WarningAmber = Color(0xFFF59E0B)
  val WarningAmberContainerLight = Color(0xFFFEF3C7)
  val WarningAmberContainerDark = Color(0xFF4A2E05)
  val ErrorCoral = Color(0xFFEF4444)
  val ErrorCoralContainerLight = Color(0xFFFEE2E2)
  val ErrorCoralContainerDark = Color(0xFF4B1113)
}

object SkyPlusGradients {
  val HeroOceanGradient: androidx.compose.ui.graphics.Brush
    get() = androidx.compose.ui.graphics.Brush.linearGradient(
      colors = listOf(
        SkyPlusBrandColors.MidnightNavy,
        SkyPlusBrandColors.DeepOceanBlue,
        SkyPlusBrandColors.RoyalBlueDark,
        Color(0xFF0A4D5C),
      ),
    )

  val OceanTealGoldAccent: androidx.compose.ui.graphics.Brush
    get() = androidx.compose.ui.graphics.Brush.horizontalGradient(
      colors = listOf(
        SkyPlusBrandColors.DeepOceanBlue,
        SkyPlusBrandColors.Teal,
        SkyPlusBrandColors.GoldAccent,
      ),
    )

  val GoldAccentBorder: androidx.compose.ui.graphics.Brush
    get() = androidx.compose.ui.graphics.Brush.linearGradient(
      colors = listOf(
        SkyPlusBrandColors.GoldAccent.copy(alpha = 0.65f),
        SkyPlusBrandColors.SoftCyan.copy(alpha = 0.45f),
        SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.55f),
      ),
    )
}

@Immutable
data class SkyPlusSpacingTokens(
  val xxs: Dp = 4.dp,
  val xs: Dp = 8.dp,
  val sm: Dp = 12.dp,
  val md: Dp = 16.dp,
  val lg: Dp = 20.dp,
  val xl: Dp = 24.dp,
  val xxl: Dp = 32.dp,
  val cardPadding: Dp = 16.dp,
  val heroPadding: Dp = 20.dp,
  val gridGap: Dp = 12.dp,
  val iconSm: Dp = 18.dp,
  val iconMd: Dp = 22.dp,
  val iconLg: Dp = 28.dp,
  val minTouchTarget: Dp = 48.dp,
  val maxContentWidth: Dp = 720.dp,
  val cardRadius: Dp = 18.dp,
  val heroRadius: Dp = 22.dp,
  val buttonRadius: Dp = 14.dp,
)

val LocalSkyPlusSpacing = staticCompositionLocalOf { SkyPlusSpacingTokens() }

object SkyPlusFonts {
  val PrimarySans = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
  )

  val NumericMono = FontFamily(
    Font(R.font.roboto, FontWeight.Normal),
    Font(R.font.roboto, FontWeight.Medium),
    Font(R.font.roboto, FontWeight.SemiBold),
    Font(R.font.roboto, FontWeight.Bold),
  )

  fun numericStyle(base: TextStyle): TextStyle =
    base.copy(
      fontFamily = NumericMono,
      fontFeatureSettings = "tnum, lnum",
      letterSpacing = 0.sp,
    )
}

val SkyPlusTypography = Typography(
  displaySmall = TextStyle(
    fontFamily = SkyPlusFonts.NumericMono,
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    lineHeight = 36.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum, lnum",
  ),
  headlineMedium = TextStyle(
    fontFamily = SkyPlusFonts.PrimarySans,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.25).sp,
  ),
  titleLarge = TextStyle(
    fontFamily = SkyPlusFonts.PrimarySans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
  ),
  titleMedium = TextStyle(
    fontFamily = SkyPlusFonts.PrimarySans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 21.sp,
  ),
  bodyLarge = TextStyle(
    fontFamily = SkyPlusFonts.PrimarySans,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  ),
  bodyMedium = TextStyle(
    fontFamily = SkyPlusFonts.PrimarySans,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
  ),
  labelLarge = TextStyle(
    fontFamily = SkyPlusFonts.PrimarySans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
  ),
  labelSmall = TextStyle(
    fontFamily = SkyPlusFonts.PrimarySans,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
  ),
)

val SkyPlusBrandShapes = Shapes(
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(18.dp),
  large = RoundedCornerShape(22.dp),
)

private val SkyPlusLightScheme = lightColorScheme(
  primary = SkyPlusBrandColors.RoyalBlue,
  onPrimary = SkyPlusBrandColors.PureWhite,
  primaryContainer = SkyPlusBrandColors.RoyalBlueContainerLight,
  onPrimaryContainer = SkyPlusBrandColors.MidnightNavy,
  secondary = SkyPlusBrandColors.EmeraldGreen,
  onSecondary = SkyPlusBrandColors.MidnightNavy,
  secondaryContainer = SkyPlusBrandColors.EmeraldContainerLight,
  onSecondaryContainer = SkyPlusBrandColors.MidnightNavy,
  tertiary = SkyPlusBrandColors.SoftCyan,
  onTertiary = SkyPlusBrandColors.MidnightNavy,
  tertiaryContainer = SkyPlusBrandColors.SoftCyanContainerLight,
  onTertiaryContainer = SkyPlusBrandColors.MidnightNavy,
  error = SkyPlusBrandColors.ErrorCoral,
  onError = SkyPlusBrandColors.PureWhite,
  errorContainer = SkyPlusBrandColors.ErrorCoralContainerLight,
  onErrorContainer = Color(0xFF7F1D1D),
  background = SkyPlusBrandColors.LightBackground,
  onBackground = SkyPlusBrandColors.TextPrimaryLight,
  surface = SkyPlusBrandColors.PureWhite,
  onSurface = SkyPlusBrandColors.TextPrimaryLight,
  surfaceVariant = SkyPlusBrandColors.SoftSurfaceVariantLight,
  onSurfaceVariant = SkyPlusBrandColors.TextSecondaryLight,
  outline = SkyPlusBrandColors.OutlineLight,
)

private val SkyPlusDarkScheme = darkColorScheme(
  primary = SkyPlusBrandColors.RoyalBlueBright,
  onPrimary = SkyPlusBrandColors.MidnightNavy,
  primaryContainer = SkyPlusBrandColors.RoyalBlueContainerDark,
  onPrimaryContainer = SkyPlusBrandColors.PureWhite,
  secondary = SkyPlusBrandColors.EmeraldGreen,
  onSecondary = SkyPlusBrandColors.MidnightNavy,
  secondaryContainer = SkyPlusBrandColors.EmeraldContainerDark,
  onSecondaryContainer = SkyPlusBrandColors.EmeraldContainerLight,
  tertiary = SkyPlusBrandColors.SoftCyan,
  onTertiary = SkyPlusBrandColors.MidnightNavy,
  tertiaryContainer = SkyPlusBrandColors.SoftCyanContainerDark,
  onTertiaryContainer = SkyPlusBrandColors.PureWhite,
  error = SkyPlusBrandColors.ErrorCoral,
  onError = SkyPlusBrandColors.PureWhite,
  errorContainer = SkyPlusBrandColors.ErrorCoralContainerDark,
  onErrorContainer = Color(0xFFFECACA),
  background = SkyPlusBrandColors.MidnightNavy,
  onBackground = SkyPlusBrandColors.TextPrimaryDark,
  surface = SkyPlusBrandColors.DeepSlate,
  onSurface = SkyPlusBrandColors.TextPrimaryDark,
  surfaceVariant = SkyPlusBrandColors.DeepSlateElevated,
  onSurfaceVariant = SkyPlusBrandColors.TextSecondaryDark,
  outline = SkyPlusBrandColors.OutlineDark,
)

@Composable
fun SkyPlusBrandTheme(
  themeMode: AppThemeMode = AppThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val darkTheme = when (themeMode) {
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
  }
  val colorScheme = if (darkTheme) SkyPlusDarkScheme else SkyPlusLightScheme

  CompositionLocalProvider(LocalSkyPlusSpacing provides SkyPlusSpacingTokens()) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = SkyPlusTypography,
      shapes = SkyPlusBrandShapes,
      content = content,
    )
  }
}
