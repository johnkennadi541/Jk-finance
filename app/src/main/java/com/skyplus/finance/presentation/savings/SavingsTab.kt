package com.skyplus.finance.presentation.savings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Savings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.designsystem.SkyPlusAnimatedProgressBar
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.intelligence.EmergencyFundAndGoalAdvisorCard
import com.skyplus.finance.presentation.theme.JetBrainsMonoFamily
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun SavingsTab(
  snapshot: DashboardSnapshot,
  onCreateGoal: (
    goalName: String,
    goalType: SavingsGoalType,
    targetText: String,
    initialSavedText: String,
    currencyCode: String,
    targetMonthsAhead: Int?,
    isEmergencyFund: Boolean,
  ) -> Unit,
  onContribute: (SavingsGoalEntity, String, String) -> Unit,
  onDeleteGoal: (SavingsGoalEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  var showCreateDialog by remember { mutableStateOf(false) }
  var contributingGoal by remember { mutableStateOf<SavingsGoalEntity?>(null) }

  if (showCreateDialog) {
    CreateSavingsGoalDialog(
      defaultCurrency = snapshot.primaryCurrencyCode,
      onSave = { name, type, target, saved, months, isEf ->
        onCreateGoal(name, type, target, saved, snapshot.primaryCurrencyCode, months, isEf)
        showCreateDialog = false
      },
      onDismiss = { showCreateDialog = false },
    )
  }

  contributingGoal?.let { goal ->
    ContributeGoalDialog(
      goal = goal,
      onContribute = { amt, note ->
        onContribute(goal, amt, note)
        contributingGoal = null
      },
      onDismiss = { contributingGoal = null },
    )
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
          Text(strings.savingsTab, style = MaterialTheme.typography.titleLarge)
          Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.testTag("create_savings_goal_button"),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(strings.createSavingsGoal)
          }
        }
      }

      snapshot.intelligence?.forecastAndRecommendations?.let { bundle ->
        item {
          EmergencyFundAndGoalAdvisorCard(
            emergencyRec = bundle.emergencyFundRecommendation,
            goalRecommendations = bundle.goalRecommendations,
            currencyEntity = snapshot.primaryCurrency,
          )
        }
      }

      if (snapshot.savingsGoals.isEmpty()) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(20.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Filled.Savings, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue, modifier = Modifier.size(32.dp))
              Text("Create an Emergency Fund or Personal Savings Goal", style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      } else {
        items(snapshot.savingsGoals, key = { it.id }) { goal ->
          val cur = snapshot.currenciesByCode[goal.currencyCode.uppercase()]
          val pct = DecimalUtils.percentage(goal.currentSavedAmount, goal.targetAmount)
          val remaining = goal.targetAmount.subtract(goal.currentSavedAmount).coerceAtLeast(BigDecimal.ZERO)
          val fraction = (pct.toFloat() / 100f).coerceIn(0f, 1f)

          SkyPlusPortfolioCard(
            modifier = Modifier.testTag("savings_goal_card_${goal.id}"),
          ) {
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
                  Text(goal.goalName, style = MaterialTheme.typography.titleMedium)
                  Text(
                    text = goal.goalCategory.name.replace("_", " ") +
                      (goal.targetDateEpochDay?.let { " • Target: ${LocalDate.ofEpochDay(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Text(
                  text = "$pct%",
                  style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                  color = SkyPlusBrandColors.EmeraldGreen,
                  fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { onDeleteGoal(goal) }) {
                  Icon(Icons.Filled.Delete, contentDescription = strings.delete)
                }
              }

              SkyPlusAnimatedProgressBar(
                targetProgress = fraction,
                barColor = if (goal.isEmergencyFund) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.RoyalBlue,
                heightDp = 9,
              )

              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                  Text(strings.currentSaved, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(
                    text = "${CurrencyFormatter.formatWithEntity(goal.currentSavedAmount, cur, goal.currencyCode)} / ${CurrencyFormatter.formatWithEntity(goal.targetAmount, cur, goal.currencyCode)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                  )
                }
                Column(horizontalAlignment = Alignment.End) {
                  Text(strings.remainingLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(
                    text = CurrencyFormatter.formatWithEntity(remaining, cur, goal.currencyCode),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }

              Button(
                onClick = { contributingGoal = goal },
                modifier = Modifier.fillMaxWidth().testTag("contribute_goal_${goal.id}"),
              ) {
                Text(strings.contributeNow)
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSavingsGoalDialog(
  defaultCurrency: String,
  onSave: (String, SavingsGoalType, String, String, Int?, Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var goalName by remember { mutableStateOf("") }
  var goalType by remember { mutableStateOf(SavingsGoalType.EMERGENCY_FUND) }
  var expanded by remember { mutableStateOf(false) }
  var targetText by remember { mutableStateOf("") }
  var savedText by remember { mutableStateOf("0") }
  var monthsText by remember { mutableStateOf("12") }
  var isEmergency by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.createSavingsGoal) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
          OutlinedTextField(
            value = goalType.name.replace("_", " "),
            onValueChange = {},
            readOnly = true,
            label = { Text(strings.categoryLabel) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
          )
          ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SavingsGoalType.entries.forEach { t ->
              DropdownMenuItem(
                text = { Text(t.name.replace("_", " ")) },
                onClick = {
                  goalType = t
                  isEmergency = (t == SavingsGoalType.EMERGENCY_FUND)
                  expanded = false
                },
              )
            }
          }
        }
        OutlinedTextField(
          value = goalName,
          onValueChange = { goalName = it },
          label = { Text(strings.goalName) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("goal_name_input"),
        )
        OutlinedTextField(
          value = targetText,
          onValueChange = { targetText = it },
          label = { Text("${strings.targetAmount} ($defaultCurrency)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("goal_target_input"),
        )
        OutlinedTextField(
          value = savedText,
          onValueChange = { savedText = it },
          label = { Text("${strings.currentSaved} ($defaultCurrency)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = monthsText,
          onValueChange = { monthsText = it },
          label = { Text("Target months ahead (optional)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(strings.isEmergencyFundGoal, modifier = Modifier.weight(1f))
          Switch(checked = isEmergency, onCheckedChange = { isEmergency = it })
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(goalName, goalType, targetText, savedText, monthsText.toIntOrNull(), isEmergency)
        },
        modifier = Modifier.testTag("save_goal_button"),
      ) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}

@Composable
private fun ContributeGoalDialog(
  goal: SavingsGoalEntity,
  onContribute: (String, String) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var amount by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("${strings.contributeNow} — ${goal.goalName}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = amount,
          onValueChange = { amount = it },
          label = { Text("${strings.amountLabel} (${goal.currencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          label = { Text(strings.descriptionOptional) },
          singleLine = true,
        )
      }
    },
    confirmButton = {
      Button(onClick = { onContribute(amount, note) }) { Text(strings.save) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}
