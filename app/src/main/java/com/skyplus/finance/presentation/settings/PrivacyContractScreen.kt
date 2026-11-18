package com.skyplus.finance.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.localization.LocalAppStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyContractScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  val clauses = listOf(
    "1. Zero Advertisements" to
      "SKYPLUS FINANCE contains no third-party advertising SDKs, ad networks, or sponsored financial placements.",
    "2. Zero Data Selling & Zero Trackers" to
      "Your spending habits, balances, loans, and categories are never sold, brokered, or sent to behavioral analytics trackers.",
    "3. Hardware-Protected AES-256 Storage" to
      "Your financial portfolio is stored in a SQLCipher AES-256 encrypted database protected by an Android Keystore key (StrongBox-backed where hardware supports it). Keep a device screen lock active for strongest protection.",
    "4. Optional Google Account Linking" to
      "SKYPLUS FINANCE opens directly in Guest Mode with zero mandatory registration. Linking a Google account is strictly optional and used only for private appDataFolder encrypted backup.",
    "5. User-Controlled Financial Reports" to
      "You control when and where reports are generated, saved, or shared using PDF, Microsoft Word (.docx), or CSV exports across Weekly, Monthly, Yearly, and Custom date ranges.",
    "6. Permanent Deletion After Explicit Confirmation" to
      "You can permanently erase all transactions, budgets, loans, and savings goals from Profile after explicit two-step confirmation. Signing out of Google never deletes your local portfolio.",
  )

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = { Text(strings.privacySecurityTitle) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("privacy_back_button")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .widthIn(max = 680.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = SkyPlusBrandColors.EmeraldGreen)
            Text(
              text = "SKYPLUS FINANCE Privacy Commitments",
              style = MaterialTheme.typography.titleLarge,
            )
          }
        }
        clauses.forEach { (title, body) ->
          item {
            SkyPlusPortfolioCard {
              Text(title, style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.RoyalBlue)
              Text(body, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    }
  }
}
