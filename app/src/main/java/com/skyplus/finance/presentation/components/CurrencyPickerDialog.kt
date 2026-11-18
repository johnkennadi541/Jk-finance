package com.skyplus.finance.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.localization.LocalAppStrings

@Composable
fun CurrencyPickerDialog(
  currencies: List<CurrencyEntity>,
  selectedCode: String,
  onSelect: (CurrencyEntity) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var query by remember { mutableStateOf("") }

  val filtered = remember(currencies, query) {
    val q = query.trim().lowercase()
    if (q.isEmpty()) {
      currencies
    } else {
      currencies.filter {
        it.code.lowercase().contains(q) ||
          it.displayName.lowercase().contains(q) ||
          it.symbol.lowercase().contains(q)
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.currencyLabel) },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("currency_search_input"),
          placeholder = { Text(strings.searchCurrencyHint) },
          leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
          singleLine = true,
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp),
        ) {
          items(filtered, key = { it.code }) { currency ->
            val isSelected = currency.code.equals(selectedCode, ignoreCase = true)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clickable {
                  onSelect(currency)
                  onDismiss()
                }
                .padding(vertical = 10.dp, horizontal = 4.dp)
                .testTag("currency_option_${currency.code}"),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${currency.code}  •  ${currency.symbol}",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = "${currency.displayName} (${currency.decimalPrecision} dec)",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (isSelected) {
                Icon(
                  imageVector = Icons.Filled.Check,
                  contentDescription = "Selected",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(strings.cancel)
      }
    },
  )
}
