package com.skyplus.finance.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.theme.JetBrainsMonoFamily
import com.skyplus.finance.util.DecimalUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickTransactionBottomSheet(
  initialType: TransactionType,
  editingTransaction: TransactionEntity?,
  categories: List<CategoryEntity>,
  currencies: List<CurrencyEntity>,
  defaultCurrencyCode: String,
  onSave: (
    existingId: Long?,
    type: TransactionType,
    amountText: String,
    categoryId: Long,
    currencyCode: String,
    dateEpochDay: Long,
    description: String,
    isUpcomingBill: Boolean,
    isRecurring: Boolean,
    submissionToken: String,
  ) -> Unit,
  onDelete: (Long) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var selectedType by remember(editingTransaction, initialType) {
    mutableStateOf(editingTransaction?.type ?: initialType)
  }
  var amountText by remember(editingTransaction) {
    mutableStateOf(
      editingTransaction?.let { DecimalUtils.toCanonicalString(it.amount) } ?: "",
    )
  }
  var selectedCurrencyCode by remember(editingTransaction, defaultCurrencyCode) {
    mutableStateOf(editingTransaction?.currencyCode ?: defaultCurrencyCode)
  }
  var selectedDateEpochDay by remember(editingTransaction) {
    mutableLongStateOf(editingTransaction?.transactionDateEpochDay ?: LocalDate.now().toEpochDay())
  }
  var description by remember(editingTransaction) {
    mutableStateOf(editingTransaction?.description ?: "")
  }
  var isUpcomingBill by remember(editingTransaction) {
    mutableStateOf(editingTransaction?.isUpcomingBill ?: false)
  }
  var isRecurring by remember(editingTransaction) {
    mutableStateOf(editingTransaction?.isRecurring ?: false)
  }
  var showCurrencyDialog by remember { mutableStateOf(false) }
  var showDatePicker by remember { mutableStateOf(false) }
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var validationError by remember { mutableStateOf<String?>(null) }
  var hasSubmitted by remember { mutableStateOf(false) }
  val submissionToken = remember(editingTransaction) { UUID.randomUUID().toString() }

  val filteredCategories = remember(categories, selectedType) {
    val targetCatType = when (selectedType) {
      TransactionType.EXPENSE -> CategoryType.EXPENSE
      TransactionType.INCOME -> CategoryType.INCOME
      TransactionType.SAVINGS_ALLOCATION -> CategoryType.SAVINGS
    }
    categories.filter { it.type == targetCatType }
  }

  var selectedCategoryId by remember(editingTransaction, filteredCategories) {
    mutableLongStateOf(
      editingTransaction?.categoryId?.takeIf { id -> filteredCategories.any { it.id == id } }
        ?: filteredCategories.firstOrNull()?.id
        ?: 1L,
    )
  }

  val activeCurrency = currencies.firstOrNull { it.code.equals(selectedCurrencyCode, ignoreCase = true) }
  val maxDecimals = (activeCurrency?.decimalPrecision ?: 2).coerceIn(0, 3)

  if (showCurrencyDialog) {
    CurrencyPickerDialog(
      currencies = currencies,
      selectedCode = selectedCurrencyCode,
      onSelect = { cur -> selectedCurrencyCode = cur.code },
      onDismiss = { showCurrencyDialog = false },
    )
  }

  if (showDatePicker) {
    val datePickerState = rememberDatePickerState(
      initialSelectedDateMillis = LocalDate.ofEpochDay(selectedDateEpochDay)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli(),
    )
    DatePickerDialog(
      onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(
          onClick = {
            datePickerState.selectedDateMillis?.let { millis ->
              selectedDateEpochDay = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
            }
            showDatePicker = false
          },
        ) {
          Text(strings.save)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDatePicker = false }) { Text(strings.cancel) }
      },
    ) {
      DatePicker(state = datePickerState)
    }
  }

  if (showDeleteConfirm && editingTransaction != null) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text(strings.confirmDeleteTitle) },
      text = { Text(strings.confirmDeleteMessage) },
      confirmButton = {
        Button(
          onClick = {
            showDeleteConfirm = false
            onDelete(editingTransaction.id)
            onDismiss()
          },
          colors = ButtonDefaults.buttonColors(containerColor = SkyPlusBrandColors.ErrorCoral),
        ) {
          Text(strings.delete)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel) }
      },
    )
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .navigationBarsPadding()
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = if (editingTransaction != null) strings.editTransactionTitle else strings.transactionSheetTitle,
          style = MaterialTheme.typography.titleLarge,
        )
        if (editingTransaction != null) {
          IconButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.testTag("delete_transaction_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Delete,
              contentDescription = strings.delete,
              tint = SkyPlusBrandColors.ErrorCoral,
            )
          }
        }
      }

      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val types = listOf(
          TransactionType.EXPENSE to strings.expenseType,
          TransactionType.INCOME to strings.incomeType,
          TransactionType.SAVINGS_ALLOCATION to strings.savingsType,
        )
        types.forEachIndexed { idx, (txType, label) ->
          SegmentedButton(
            selected = selectedType == txType,
            onClick = {
              selectedType = txType
              if (txType != TransactionType.EXPENSE) isUpcomingBill = false
            },
            shape = SegmentedButtonDefaults.itemShape(index = idx, count = types.size),
            modifier = Modifier.testTag("tx_type_${txType.name.lowercase()}"),
          ) {
            Text(label, maxLines = 1)
          }
        }
      }

      // Large financial amount display + Currency chip
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
          .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        OutlinedButton(
          onClick = { showCurrencyDialog = true },
          modifier = Modifier.testTag("tx_currency_button"),
        ) {
          Text(
            text = "${activeCurrency?.symbol ?: selectedCurrencyCode} $selectedCurrencyCode",
            fontWeight = FontWeight.Bold,
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = if (amountText.isEmpty()) "0" else amountText,
          style = MaterialTheme.typography.displaySmall.copy(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 30.sp,
          ),
          color = when (selectedType) {
            TransactionType.INCOME -> SkyPlusBrandColors.EmeraldGreen
            TransactionType.EXPENSE -> SkyPlusBrandColors.ErrorCoral
            TransactionType.SAVINGS_ALLOCATION -> SkyPlusBrandColors.RoyalBlue
          },
          textAlign = TextAlign.End,
          modifier = Modifier
            .weight(1f)
            .testTag("tx_amount_display"),
          maxLines = 1,
        )
      }

      if (validationError != null) {
        Text(
          text = validationError!!,
          color = SkyPlusBrandColors.ErrorCoral,
          style = MaterialTheme.typography.bodyMedium,
        )
      }

      // Large Numeric Keypad
      NumericAmountKeypad(
        allowDecimal = maxDecimals > 0,
        onDigit = { digit ->
          validationError = null
          val next = appendAmountDigit(amountText, digit, maxDecimals)
          amountText = next
        },
        onBackspace = {
          validationError = null
          if (amountText.isNotEmpty()) {
            amountText = amountText.dropLast(1)
          }
        },
      )

      // Category chips
      Text(strings.categoryLabel, style = MaterialTheme.typography.labelLarge)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        filteredCategories.forEach { cat ->
          val label = cat.displayNameEn
          FilterChip(
            selected = selectedCategoryId == cat.id,
            onClick = { selectedCategoryId = cat.id },
            label = { Text(label) },
            leadingIcon = {
              Icon(
                imageVector = CategoryIconMapper.iconFor(cat.iconKey),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            },
            modifier = Modifier.testTag("cat_chip_${cat.nameKey}"),
          )
        }
      }

      // Date row + Description
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = { showDatePicker = true },
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Icon(Icons.Filled.CalendarToday, contentDescription = strings.dateLabel, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(LocalDate.ofEpochDay(selectedDateEpochDay).toString())
        }

        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text(strings.descriptionOptional) },
          singleLine = true,
          modifier = Modifier
            .weight(1f)
            .testTag("tx_description_input"),
        )
      }

      if (selectedType == TransactionType.EXPENSE) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = strings.upcomingUnpaidBill,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          Switch(
            checked = isUpcomingBill,
            onCheckedChange = { isUpcomingBill = it },
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = strings.recurringTransaction,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Switch(
          checked = isRecurring,
          onCheckedChange = { isRecurring = it },
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedButton(
          onClick = onDismiss,
          modifier = Modifier
            .weight(1f)
            .height(50.dp),
        ) {
          Text(strings.cancel)
        }
        Button(
          onClick = {
            if (hasSubmitted) return@Button
            val parsed = DecimalUtils.parsePositiveOrNull(amountText)
            if (parsed == null) {
              validationError = strings.invalidAmountError
              return@Button
            }
            hasSubmitted = true
            onSave(
              editingTransaction?.id,
              selectedType,
              amountText,
              selectedCategoryId,
              selectedCurrencyCode,
              selectedDateEpochDay,
              description,
              isUpcomingBill,
              isRecurring,
              submissionToken,
            )
            onDismiss()
          },
          modifier = Modifier
            .weight(1f)
            .height(50.dp)
            .testTag("save_transaction_button"),
        ) {
          Text(strings.save)
        }
      }
    }
  }
}

@Composable
private fun NumericAmountKeypad(
  allowDecimal: Boolean,
  onDigit: (String) -> Unit,
  onBackspace: () -> Unit,
) {
  val rows = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(if (allowDecimal) "." else "00", "0", "BACK"),
  )
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    rows.forEach { row ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        row.forEach { key ->
          Box(
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
              .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp),
              )
              .clickable {
                if (key == "BACK") onBackspace() else onDigit(key)
              }
              .testTag("keypad_$key"),
            contentAlignment = Alignment.Center,
          ) {
            if (key == "BACK") {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Backspace",
              )
            } else {
              Text(
                text = key,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        }
      }
    }
  }
}

private fun appendAmountDigit(current: String, token: String, maxDecimals: Int): String {
  if (token == ".") {
    if (maxDecimals <= 0 || current.contains(".")) return current
    return if (current.isEmpty()) "0." else "$current."
  }
  val dotIndex = current.indexOf('.')
  if (dotIndex >= 0) {
    val existingDecimals = current.length - dotIndex - 1
    if (existingDecimals >= maxDecimals) return current
  }
  if (current == "0" && token != ".") return token
  if (current.length >= 12) return current
  return current + token
}
