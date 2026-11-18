package com.skyplus.finance.presentation.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusEmptyState
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.designsystem.SkyPlusPrimaryButton
import com.skyplus.finance.designsystem.SkyPlusSpacingTokens
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.home.TransactionRowCard
import com.skyplus.finance.util.CurrencyFormatter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

private enum class TxPeriodFilter(val label: String) {
  ALL("All"),
  WEEKLY("Weekly"),
  MONTHLY("Monthly"),
  YEARLY("Yearly"),
  CUSTOM("Custom"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
  snapshot: DashboardSnapshot,
  onAddTransaction: () -> Unit,
  onEditTransaction: (TransactionEntity) -> Unit,
  onMarkBillPaid: (Long) -> Unit,
  onOpenAutoDetect: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  val spacing = SkyPlusSpacingTokens()
  val today = remember { LocalDate.now() }

  var searchQuery by remember { mutableStateOf("") }
  var selectedType by remember { mutableStateOf<TransactionType?>(null) }
  var selectedCurrency by remember { mutableStateOf<String?>(null) }
  var periodFilter by remember { mutableStateOf(TxPeriodFilter.ALL) }
  var customStartEpochDay by remember { mutableLongStateOf(YearMonth.from(today).atDay(1).toEpochDay()) }
  var customEndEpochDay by remember { mutableLongStateOf(today.toEpochDay()) }
  var pickingStart by remember { mutableStateOf(false) }
  var pickingEnd by remember { mutableStateOf(false) }

  if (pickingStart || pickingEnd) {
    val initialDay = if (pickingStart) customStartEpochDay else customEndEpochDay
    val pickerState = rememberDatePickerState(
      initialSelectedDateMillis = LocalDate.ofEpochDay(initialDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
      onDismissRequest = {
        pickingStart = false
        pickingEnd = false
      },
      confirmButton = {
        TextButton(
          onClick = {
            pickerState.selectedDateMillis?.let { millis ->
              val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
              if (pickingStart) {
                customStartEpochDay = picked
                if (customEndEpochDay < picked) customEndEpochDay = picked
              } else {
                customEndEpochDay = picked
                if (customStartEpochDay > picked) customStartEpochDay = picked
              }
            }
            pickingStart = false
            pickingEnd = false
          },
        ) { Text(strings.save) }
      },
      dismissButton = {
        TextButton(
          onClick = {
            pickingStart = false
            pickingEnd = false
          },
        ) { Text(strings.cancel) }
      },
    ) { DatePicker(state = pickerState) }
  }

  val usedCurrencies = remember(snapshot.allTransactions) {
    snapshot.allTransactions.map { it.currencyCode.uppercase() }.distinct().sorted()
  }

  val filteredTransactions = remember(
    snapshot.allTransactions,
    snapshot.categoriesById,
    searchQuery,
    selectedType,
    selectedCurrency,
    periodFilter,
    customStartEpochDay,
    customEndEpochDay,
  ) {
    val q = searchQuery.trim().lowercase()
    val range: ClosedRange<Long>? = when (periodFilter) {
      TxPeriodFilter.ALL -> null
      TxPeriodFilter.WEEKLY -> today.minusDays(6).toEpochDay()..today.toEpochDay()
      TxPeriodFilter.MONTHLY -> YearMonth.from(today).atDay(1).toEpochDay()..YearMonth.from(today).atEndOfMonth().toEpochDay()
      TxPeriodFilter.YEARLY -> LocalDate.of(today.year, 1, 1).toEpochDay()..LocalDate.of(today.year, 12, 31).toEpochDay()
      TxPeriodFilter.CUSTOM -> minOf(customStartEpochDay, customEndEpochDay)..maxOf(customStartEpochDay, customEndEpochDay)
    }

    snapshot.allTransactions.filter { tx ->
      val typeMatches = selectedType == null || tx.type == selectedType
      val curMatches = selectedCurrency == null || tx.currencyCode.equals(selectedCurrency, ignoreCase = true)
      val dateMatches = range == null || tx.transactionDateEpochDay in range
      val catName = snapshot.categoriesById[tx.categoryId]?.displayNameEn?.lowercase().orEmpty()
      val queryMatches = q.isEmpty() ||
        tx.description.lowercase().contains(q) ||
        catName.contains(q) ||
        tx.currencyCode.lowercase().contains(q)
      typeMatches && curMatches && dateMatches && queryMatches
    }
  }

  // Per-currency net totals for the filtered view (never combining different currencies falsely)
  val filteredTotalsByCurrency = remember(filteredTransactions) {
    filteredTransactions
      .filter { it.isPaid }
      .groupBy { it.currencyCode.uppercase() }
      .mapValues { (_, list) ->
        val inc = list.filter { it.type == TransactionType.INCOME }.fold(BigDecimal.ZERO) { a, t -> a.add(t.amount) }
        val exp = list.filter { it.type == TransactionType.EXPENSE }.fold(BigDecimal.ZERO) { a, t -> a.add(t.amount) }
        inc to exp
      }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = spacing.maxContentWidth),
      contentPadding = PaddingValues(horizontal = spacing.md, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = strings.navTransactions,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("transactions_screen_title"),
              )
              Text(
                text = "${filteredTransactions.size} entries shown",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            SkyPlusPrimaryButton(
              text = "Add Entry",
              icon = Icons.Filled.Add,
              onClick = onAddTransaction,
              modifier = Modifier.testTag("transactions_add_button"),
            )
          }
          OutlinedButton(
            onClick = onOpenAutoDetect,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("transactions_auto_detect_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.AutoAwesome,
              contentDescription = null,
              tint = SkyPlusBrandColors.RoyalBlue,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Detect Transaction from Bank Message or .txt/.csv")
          }
        }
      }

      item {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text("Search description, category, or currency...") },
          leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("transactions_search_input"),
        )
      }

      // Period Filter
      item {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TxPeriodFilter.entries.forEach { p ->
            FilterChip(
              selected = periodFilter == p,
              onClick = { periodFilter = p },
              label = { Text(p.label) },
            )
          }
        }
      }

      if (periodFilter == TxPeriodFilter.CUSTOM) {
        item {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
              Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("From: ${LocalDate.ofEpochDay(customStartEpochDay)}")
            }
            OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
              Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("To: ${LocalDate.ofEpochDay(customEndEpochDay)}")
            }
          }
        }
      }

      // Type & Currency Filters
      item {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = selectedType == null,
            onClick = { selectedType = null },
            label = { Text("All Types") },
          )
          FilterChip(
            selected = selectedType == TransactionType.EXPENSE,
            onClick = { selectedType = if (selectedType == TransactionType.EXPENSE) null else TransactionType.EXPENSE },
            label = { Text(strings.expenseType) },
          )
          FilterChip(
            selected = selectedType == TransactionType.INCOME,
            onClick = { selectedType = if (selectedType == TransactionType.INCOME) null else TransactionType.INCOME },
            label = { Text(strings.incomeType) },
          )
          FilterChip(
            selected = selectedType == TransactionType.SAVINGS_ALLOCATION,
            onClick = {
              selectedType = if (selectedType == TransactionType.SAVINGS_ALLOCATION) null else TransactionType.SAVINGS_ALLOCATION
            },
            label = { Text(strings.savingsType) },
          )
          if (usedCurrencies.size > 1) {
            usedCurrencies.forEach { code ->
              FilterChip(
                selected = selectedCurrency.equals(code, ignoreCase = true),
                onClick = { selectedCurrency = if (selectedCurrency.equals(code, true)) null else code },
                label = { Text(code) },
              )
            }
          }
        }
      }

      if (filteredTotalsByCurrency.isNotEmpty()) {
        item {
          SkyPlusPortfolioCard {
            Text("Filtered Period Totals", style = MaterialTheme.typography.titleMedium)
            filteredTotalsByCurrency.forEach { (code, pair) ->
              val cur = snapshot.currenciesByCode[code]
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(code, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  Text(
                    text = "+${CurrencyFormatter.formatWithEntity(pair.first, cur, code)}",
                    style = SkyPlusFonts.numericStyle(MaterialTheme.typography.bodyMedium),
                    color = SkyPlusBrandColors.EmeraldGreen,
                  )
                  Text(
                    text = "-${CurrencyFormatter.formatWithEntity(pair.second, cur, code)}",
                    style = SkyPlusFonts.numericStyle(MaterialTheme.typography.bodyMedium),
                    color = SkyPlusBrandColors.ErrorCoral,
                  )
                }
              }
            }
          }
        }
      }

      if (filteredTransactions.isEmpty()) {
        item {
          SkyPlusEmptyState(
            icon = Icons.Filled.Payments,
            title = "No matching transactions",
            subtitle = "Adjust your filters or add a new entry.",
            actionLabel = "Add Entry",
            onAction = onAddTransaction,
          )
        }
      } else {
        items(filteredTransactions, key = { it.id }) { tx ->
          TransactionRowCard(
            tx = tx,
            snapshot = snapshot,
            onClick = { onEditTransaction(tx) },
            onMarkPaid = { onMarkBillPaid(tx.id) },
          )
        }
      }

      item { Spacer(modifier = Modifier.height(12.dp)) }
    }
  }
}
