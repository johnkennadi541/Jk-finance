package com.skyplus.finance.presentation.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusPrimaryButton
import com.skyplus.finance.localization.LocalAppStrings

@Composable
fun AppLockGateScreen(
  authError: String?,
  onUnlockClick: () -> Unit,
) {
  val strings = LocalAppStrings.current
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(SkyPlusBrandColors.MidnightNavy, SkyPlusBrandColors.DeepSlate),
        ),
      )
      .padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = Icons.Filled.Lock,
      contentDescription = strings.unlockTitle,
      tint = SkyPlusBrandColors.EmeraldGreen,
      modifier = Modifier.size(64.dp),
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = strings.unlockTitle,
      style = MaterialTheme.typography.headlineMedium,
      color = SkyPlusBrandColors.PureWhite,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = strings.unlockSubtitle,
      style = MaterialTheme.typography.bodyMedium,
      color = SkyPlusBrandColors.TextSecondaryDark,
      textAlign = TextAlign.Center,
    )
    if (!authError.isNullOrBlank()) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = authError,
        style = MaterialTheme.typography.bodyMedium,
        color = SkyPlusBrandColors.WarningAmber,
        textAlign = TextAlign.Center,
      )
    }
    Spacer(modifier = Modifier.height(28.dp))
    SkyPlusPrimaryButton(
      text = strings.unlockButton,
      onClick = onUnlockClick,
      modifier = Modifier.testTag("unlock_ledger_button"),
    )
  }
}
