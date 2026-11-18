package com.skyplus.finance.presentation.budgets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skyplus.finance.designsystem.SkyPlusAnimatedProgressBar
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.domain.intelligence.BudgetRecommendation
import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.model.BudgetStatusItem
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.components.CategoryIconMapper
import com.skyplus.finance.presentation.intelligence.SmartBudgetRecommendationsCard
import com.skyplus.finance.presentation.theme.JetBrainsMonoFamily
import com.skyplus.finance.util.CurrencyFormatter
import java.math.BigDecimal
import java.time.YearMonth

@Composable
fun BudgetsTab(
  snapshot: DashboardSnapshot,
  allBudgetHistory: List<BudgetStatusItem>,
  onUpsertBudget: (
    categoryId: Long,
    yearMonth: String,
    limitText: String,
    currencyCode: String,
    rolloverEnabled: Boolean,
    rolloverText: String,
  ) -> Unit,
  onDeleteBudget: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  var showCreateDialog by remember { mutableStateOf(false) }
  var pendingRecommendation by remember { mutableStateOf<BudgetRecommendation?>(null) }

  if (showCreateDialog) {
    CreateBudgetDialog(
      snapshot = snapshot,
      onSave = { catId, ym, limit, cur, rollEnabled, rollAmt ->
        onUpsertBudget(catId, ym, limit, cur, rollEnabled, rollAmt)
        showCreateDialog = false
      },
      onDismiss = { showCreateDialog = false },
    )
  }

  val currentMonth = YearMonth.now().toString()
  pendingRecommendation?.let { rec ->
    val cur = snapshot.currenciesByCode[rec.currencyCode.uppercase()]
    AlertDialog(
      onDismissRequest = { pendingRecommendation = null },
      title = { Text("Confirm Budget Suggestion") },
      text = {
        Text(
          "Set ${rec.categoryName} budget for $currentMonth to ${CurrencyFormatter.formatWithEntity(rec.suggestedBudgetLimit, cur, rec.currencyCode)}? Your existing budget records are only updated when you confirm.",
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onUpsertBudget(
              rec.categoryId,
              currentMonth,
              rec.suggestedBudgetLimit.toPlainString(),
              rec.currencyCode,
              false,
              "0",
            )
            pendingRecommendation = null
          },
          modifier = Modifier.testTag("confirm_apply_budget_rec_button"),
        ) {
          Text("Confirm & Apply")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingRecommendation = null }) {
          Text(strings.cancel)
        }
      },
    )
  }

  val pastHistory = remember(allBudgetHistory, currentMonth) {
    allBudgetHistory.filter { it.yearMonth != currentMonth }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("${strings.budgetsTab} ($currentMonth)", style = MaterialTheme.typography.titleLarge)
          Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.testTag("create_budget_button"),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(strings.createBudget)
          }
        }
      }

      snapshot.intelligence?.forecastAndRecommendations?.budgetRecommendations?.let { recs ->
        item {
          SmartBudgetRecommendationsCard(
            recommendations = recs,
            currencyEntity = snapshot.primaryCurrency,
            onApplyRecommendation = { rec -> pendingRecommendation = rec },
          )
        }
      }

      if (snapshot.budgetStatuses.isEmpty()) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Text(
              text = strings.noBudgetsHint,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(20.dp),
            )
          }
        }
      } else {
        items(snapshot.budgetStatuses, key = { it.budgetId }) { item ->
          BudgetStatusCard(
            item = item,
            snapshot = snapshot,
            onDelete = { onDeleteBudget(item.budgetId) },
          )
        }
      }

      if (pastHistory.isNotEmpty()) {
        item {
          Spacer(modifier = Modifier.height(8.dp))
          Text(strings.budgetHistory, style = MaterialTheme.typography.titleMedium)
        }
        items(pastHistory, key = { "hist_${it.budgetId}" }) { item ->
          BudgetStatusCard(
            item = item,
            snapshot = snapshot,
            onDelete = { onDeleteBudget(item.budgetId) },
          )
        }
      }
    }
  }
}

@Composable
private fun BudgetStatusCard(
  item: BudgetStatusItem,
  snapshot: DashboardSnapshot,
  onDelete: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val cur = snapshot.currenciesByCode[item.currencyCode.uppercase()]
  val statusColor = when (item.status) {
    BudgetHealthStatus.NORMAL -> SkyPlusBrandColors.EmeraldGreen
    BudgetHealthStatus.APPROACHING_LIMIT -> SkyPlusBrandColors.WarningAmber
    BudgetHealthStatus.EXCEEDED -> SkyPlusBrandColors.ErrorCoral
  }
  val statusLabel = when (item.status) {
    BudgetHealthStatus.NORMAL -> strings.onTrackLabel
    BudgetHealthStatus.APPROACHING_LIMIT -> strings.approachingLimitWarning
    BudgetHealthStatus.EXCEEDED -> strings.overBudgetWarning
  }
  val title = item.categoryNameEn
  val fraction = (item.utilizationPercent.toFloat() / 100f).coerceIn(0f, 1f)

  SkyPlusPortfolioCard(
    modifier = Modifier.testTag("budget_card_${item.budgetId}"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color(item.colorHex).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = CategoryIconMapper.iconFor(item.iconKey),
              contentDescription = title,
              tint = Color(item.colorHex),
              modifier = Modifier.size(18.dp),
            )
          }
          Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
              text = item.yearMonth + if (item.rolloverEnabled) " • Rollover" else "",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Surface(
          color = statusColor.copy(alpha = 0.15f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (item.status != BudgetHealthStatus.NORMAL) {
              Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(14.dp),
              )
            }
            Text(
              text = statusLabel,
              style = MaterialTheme.typography.labelSmall,
              color = statusColor,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        IconButton(onClick = onDelete) {
          Icon(Icons.Filled.Delete, contentDescription = strings.delete, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      SkyPlusAnimatedProgressBar(
        targetProgress = fraction,
        barColor = statusColor,
        heightDp = 9,
      )

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
          Text(strings.spentLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = "${CurrencyFormatter.formatWithEntity(item.actualSpent, cur, item.currencyCode)} / ${CurrencyFormatter.formatWithEntity(item.effectiveLimit, cur, item.currencyCode)}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
            fontWeight = FontWeight.SemiBold,
          )
        }
        Column(horizontalAlignment = Alignment.End) {
          Text(strings.remainingLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = CurrencyFormatter.formatWithEntity(item.remainingAmount, cur, item.currencyCode),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
            color = if (item.remainingAmount < BigDecimal.ZERO) SkyPlusBrandColors.ErrorCoral else statusColor,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateBudgetDialog(
  snapshot: DashboardSnapshot,
  onSave: (Long, String, String, String, Boolean, String) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val expenseCategories = remember(snapshot.categoriesById) {
    snapshot.categoriesById.values.filter { it.type == CategoryType.EXPENSE }
  }
  var selectedCategoryId by remember(expenseCategories) {
    mutableLongStateOf(expenseCategories.firstOrNull()?.id ?: 1L)
  }
  var dropdownExpanded by remember { mutableStateOf(false) }
  var limitText by remember { mutableStateOf("") }
  var rolloverEnabled by remember { mutableStateOf(false) }
  var rolloverText by remember { mutableStateOf("0") }
  val currentMonth = YearMonth.now().toString()

  val selectedCategory = expenseCategories.firstOrNull { it.id == selectedCategoryId }
  val selectedCategoryLabel = selectedCategory?.displayNameEn ?: strings.categoryLabel

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.createBudget) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExposedDropdownMenuBox(
          expanded = dropdownExpanded,
          onExpandedChange = { dropdownExpanded = it },
        ) {
          OutlinedTextField(
            value = selectedCategoryLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(strings.categoryLabel) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
            modifier = Modifier
              .fillMaxWidth()
              .menuAnchor(MenuAnchorType.PrimaryNotEditable),
          )
          ExposedDropdownMenu(
            expanded = dropdownExpanded,
            onDismissRequest = { dropdownExpanded = false },
          ) {
            expenseCategories.forEach { cat ->
              val label = cat.displayNameEn
              DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                  selectedCategoryId = cat.id
                  dropdownExpanded = false
                },
              )
            }
          }
        }

        OutlinedTextField(
          value = limitText,
          onValueChange = { limitText = it },
          label = { Text("${strings.monthlyLimit} (${snapshot.primaryCurrencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("budget_limit_input"),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(strings.rolloverEnabled, modifier = Modifier.weight(1f))
          Switch(checked = rolloverEnabled, onCheckedChange = { rolloverEnabled = it })
        }

        if (rolloverEnabled) {
          OutlinedTextField(
            value = rolloverText,
            onValueChange = { rolloverText = it },
            label = { Text(strings.rolloverAmount) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            selectedCategoryId,
            currentMonth,
            limitText,
            snapshot.primaryCurrencyCode,
            rolloverEnabled,
            rolloverText,
          )
        },
        modifier = Modifier.testTag("save_budget_button"),
      ) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}
