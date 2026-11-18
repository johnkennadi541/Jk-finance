package com.skyplus.finance.presentation.transactions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.designsystem.SkyPlusPrimaryButton
import com.skyplus.finance.domain.intelligence.DetectedTransactionCandidate
import com.skyplus.finance.domain.intelligence.DetectionBatchResult
import com.skyplus.finance.domain.intelligence.DetectionOutcome
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.presentation.ConfirmedDetectedTransaction
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.time.LocalDate

private data class EditableCandidateState(
  val candidate: DetectedTransactionCandidate,
  val included: Boolean,
  val type: TransactionType,
  val amountText: String,
  val categoryId: Long,
  val description: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoDetectTransactionSheet(
  categories: List<CategoryEntity>,
  currenciesByCode: Map<String, CurrencyEntity>,
  onAnalyze: (rawText: String, sourceLabel: String) -> DetectionBatchResult,
  onConfirmSave: (List<ConfirmedDetectedTransaction>) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var rawText by remember { mutableStateOf("") }
  var sourceLabel by remember { mutableStateOf("Bank Message Import") }
  var batchResult by remember { mutableStateOf<DetectionBatchResult?>(null) }
  var importError by remember { mutableStateOf<String?>(null) }
  val editsMap = remember { mutableStateMapOf<String, EditableCandidateState>() }

  fun runAnalysis(text: String, source: String) {
    val result = onAnalyze(text, source)
    batchResult = result
    editsMap.clear()
    result.candidates.filter { it.outcome.canSave }.forEach { cand ->
      editsMap[cand.candidateId] = EditableCandidateState(
        candidate = cand,
        included = true,
        type = cand.type,
        amountText = cand.amount?.toPlainString() ?: "",
        categoryId = cand.suggestedCategoryId,
        description = cand.description,
      )
    }
  }

  val openDocLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri: Uri? ->
    if (uri != null) {
      val readResult = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
      }
      readResult.onSuccess { text ->
        if (text.isBlank()) {
          importError = "Selected file is empty."
        } else {
          importError = null
          rawText = text.take(24_000)
          sourceLabel = "Statement File Import"
          runAnalysis(rawText, sourceLabel)
        }
      }.onFailure { err ->
        importError = err.message ?: "Unable to read selected document."
      }
    }
  }

  val selectedItemsCount = editsMap.values.count { state ->
    state.included && (DecimalUtils.parsePositiveOrNull(state.amountText) != null)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
    modifier = Modifier
      .fillMaxWidth(0.95f)
      .testTag("autodetect_dialog"),
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
          Text("Detect Bank Transactions", style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Filled.Close, contentDescription = "Close")
        }
      }
    },
    text = {
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.10f))
              .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = SkyPlusBrandColors.EmeraldGreen, modifier = Modifier.size(18.dp))
            Text(
              text = "Processed 100% offline. No SMS or notification access permissions required — paste alerts or import a .txt/.csv file.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }

        item {
          OutlinedTextField(
            value = rawText,
            onValueChange = { rawText = it },
            label = { Text("Paste bank, UPI, or card alert message(s)") },
            placeholder = { Text("e.g., INR 1,450.00 debited from HDFC A/c XX4521 at Swiggy on 15-11-2026 UTR 63192011") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("autodetect_message_input"),
          )
        }

        item {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = { openDocLauncher.launch(arrayOf("text/plain", "text/csv", "text/*")) },
              modifier = Modifier
                .weight(1f)
                .testTag("autodetect_import_file_button"),
            ) {
              Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Import .txt/.csv", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SkyPlusPrimaryButton(
              text = "Analyze",
              onClick = {
                importError = null
                sourceLabel = "Bank Message Import"
                runAnalysis(rawText, sourceLabel)
              },
              enabled = rawText.isNotBlank(),
              modifier = Modifier
                .weight(1f)
                .testTag("autodetect_analyze_button"),
            )
          }
          if (!importError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(importError!!, style = MaterialTheme.typography.labelSmall, color = SkyPlusBrandColors.ErrorCoral)
          }
        }

        batchResult?.let { batch ->
          item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("autodetect_summary_row"),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              OutcomeCountChip("Detected: ${batch.actionableCount}", SkyPlusBrandColors.EmeraldGreen)
              if (batch.refundCount > 0) {
                OutcomeCountChip("Refunds: ${batch.refundCount}", SkyPlusBrandColors.RoyalBlue)
              }
              if (batch.duplicateCount > 0) {
                OutcomeCountChip("Duplicates: ${batch.duplicateCount}", SkyPlusBrandColors.WarningAmber)
              }
              if (batch.failedOrPendingCount > 0) {
                OutcomeCountChip("Failed/Pending: ${batch.failedOrPendingCount}", SkyPlusBrandColors.ErrorCoral)
              }
              if (batch.ignoredNonFinancialCount > 0) {
                OutcomeCountChip("Promo/OTP: ${batch.ignoredNonFinancialCount}", MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }

          if (batch.candidates.isEmpty()) {
            item {
              Text(
                text = "No messages found to analyze.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            items(batch.candidates, key = { it.candidateId }) { candidate ->
              if (candidate.outcome.canSave) {
                val editState = editsMap[candidate.candidateId]
                if (editState != null) {
                  ActionableDetectedCard(
                    state = editState,
                    categories = categories,
                    currenciesByCode = currenciesByCode,
                    onUpdate = { updated -> editsMap[candidate.candidateId] = updated },
                  )
                }
              } else {
                IgnoredDetectedCard(candidate = candidate)
              }
            }
          }
        }
      }
    },
    confirmButton = {
      if (batchResult != null && batchResult!!.actionableCount > 0) {
        SkyPlusPrimaryButton(
          text = "Confirm & Save ($selectedItemsCount)",
          icon = Icons.Filled.CheckCircle,
          enabled = selectedItemsCount > 0,
          onClick = {
            val confirmed = editsMap.values
              .filter { it.included }
              .mapNotNull { state ->
                val parsedAmt = DecimalUtils.parsePositiveOrNull(state.amountText) ?: return@mapNotNull null
                ConfirmedDetectedTransaction(
                  type = state.type,
                  amount = parsedAmt,
                  currencyCode = state.candidate.currencyCode,
                  categoryId = state.categoryId,
                  dateEpochDay = state.candidate.transactionDateEpochDay,
                  description = state.description.trim().ifBlank { state.candidate.merchantOrRecipient },
                  deduplicationKey = state.candidate.deduplicationKey,
                )
              }
            if (confirmed.isNotEmpty()) {
              onConfirmSave(confirmed)
              onDismiss()
            }
          },
          modifier = Modifier.testTag("autodetect_confirm_save_button"),
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}

@Composable
private fun OutcomeCountChip(label: String, color: androidx.compose.ui.graphics.Color) {
  Surface(
    color = color.copy(alpha = 0.12f),
    shape = RoundedCornerShape(8.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionableDetectedCard(
  state: EditableCandidateState,
  categories: List<CategoryEntity>,
  currenciesByCode: Map<String, CurrencyEntity>,
  onUpdate: (EditableCandidateState) -> Unit,
) {
  val cand = state.candidate
  var showCategoryMenu by remember { mutableStateOf(false) }

  val eligibleCategories = remember(categories, state.type) {
    val expected = when (state.type) {
      TransactionType.INCOME -> CategoryType.INCOME
      TransactionType.EXPENSE -> CategoryType.EXPENSE
      TransactionType.SAVINGS_ALLOCATION -> CategoryType.SAVINGS
    }
    categories.filter { it.type == expected }.ifEmpty { categories }
  }
  val activeCategory = categories.firstOrNull { it.id == state.categoryId }
    ?: eligibleCategories.firstOrNull()

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
      .padding(10.dp)
      .testTag("autodetect_candidate_${cand.candidateId}"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        Checkbox(
          checked = state.included,
          onCheckedChange = { onUpdate(state.copy(included = it)) },
        )
        Column {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutcomeCountChip(
              label = cand.outcome.label,
              color = if (cand.isRefundOrReversal) SkyPlusBrandColors.RoyalBlue else SkyPlusBrandColors.EmeraldGreen,
            )
            OutcomeCountChip(label = cand.detectionSource, color = SkyPlusBrandColors.Teal)
          }
          val dateStr = LocalDate.ofEpochDay(cand.transactionDateEpochDay).toString()
          val timeStr = cand.timeFormatted?.let { " • $it" }.orEmpty()
          val metaParts = listOfNotNull(
            cand.bankOrInstitution,
            cand.accountIdentifier,
            cand.paymentReference?.let { "Ref $it" },
          ).joinToString(" • ")
          Text(
            text = "$dateStr$timeStr${if (metaParts.isNotBlank()) " • $metaParts" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      val parsed = DecimalUtils.parsePositiveOrNull(state.amountText) ?: cand.amount
      if (parsed != null) {
        Text(
          text = CurrencyFormatter.formatWithEntity(parsed, currenciesByCode[cand.currencyCode.uppercase()], cand.currencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
          color = if (state.type == TransactionType.INCOME) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.ErrorCoral,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    // Type selector for quick user correction
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf(TransactionType.EXPENSE to "Expense", TransactionType.INCOME to "Income", TransactionType.SAVINGS_ALLOCATION to "Savings").forEach { (typeOption, label) ->
        FilterChip(
          selected = state.type == typeOption,
          onClick = {
            val matchingCat = categories.firstOrNull { cat ->
              val expected = when (typeOption) {
                TransactionType.INCOME -> CategoryType.INCOME
                TransactionType.EXPENSE -> CategoryType.EXPENSE
                TransactionType.SAVINGS_ALLOCATION -> CategoryType.SAVINGS
              }
              cat.type == expected
            }
            onUpdate(
              state.copy(
                type = typeOption,
                categoryId = matchingCat?.id ?: state.categoryId,
              ),
            )
          },
          label = { Text(label) },
        )
      }

      // Category correction dropdown
      FilterChip(
        selected = true,
        onClick = { showCategoryMenu = true },
        label = { Text("Category: ${activeCategory?.displayNameEn ?: cand.suggestedCategoryName}") },
      )
      DropdownMenu(
        expanded = showCategoryMenu,
        onDismissRequest = { showCategoryMenu = false },
      ) {
        eligibleCategories.forEach { cat ->
          DropdownMenuItem(
            text = { Text(cat.displayNameEn) },
            onClick = {
              onUpdate(state.copy(categoryId = cat.id))
              showCategoryMenu = false
            },
          )
        }
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = state.amountText,
        onValueChange = { onUpdate(state.copy(amountText = it)) },
        label = { Text("Amount (${cand.currencyCode})") },
        singleLine = true,
        modifier = Modifier.weight(0.42f),
      )
      OutlinedTextField(
        value = state.description,
        onValueChange = { onUpdate(state.copy(description = it)) },
        label = { Text("Description") },
        singleLine = true,
        modifier = Modifier.weight(0.58f),
      )
    }
  }
}

@Composable
private fun IgnoredDetectedCard(candidate: DetectedTransactionCandidate) {
  val color = when (candidate.outcome) {
    DetectionOutcome.DUPLICATE_EXISTING -> SkyPlusBrandColors.WarningAmber
    DetectionOutcome.FAILED_OR_PENDING -> SkyPlusBrandColors.ErrorCoral
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(color.copy(alpha = 0.08f))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    OutcomeCountChip(label = candidate.outcome.label, color = color)
    Text(
      text = candidate.statusReason,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = candidate.rawMessage,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
