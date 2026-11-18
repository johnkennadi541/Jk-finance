package com.skyplus.finance.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusPrimaryButton
import com.skyplus.finance.designsystem.SkyPlusSecondaryButton
import com.skyplus.finance.domain.report.ReportDetailLevel
import com.skyplus.finance.domain.report.ReportExportFormat
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.domain.report.ReportRequest
import com.skyplus.finance.domain.report.ReportSection
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportReportModalSheet(
  allCurrencies: List<CurrencyEntity>,
  defaultCurrencyCode: String,
  initialPeriod: ReportPeriodType = ReportPeriodType.MONTHLY,
  initialStartEpochDay: Long? = null,
  initialEndEpochDay: Long? = null,
  onPreview: (ReportRequest) -> Unit,
  onExport: (ReportRequest, ReportExportFormat, saveToDevice: Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val today = remember { LocalDate.now() }

  var selectedPeriod by remember { mutableStateOf(initialPeriod) }
  var startEpochDay by remember {
    mutableLongStateOf(initialStartEpochDay ?: YearMonth.from(today).atDay(1).toEpochDay())
  }
  var endEpochDay by remember {
    mutableLongStateOf(initialEndEpochDay ?: YearMonth.from(today).atEndOfMonth().toEpochDay())
  }
  var detailLevel by remember { mutableStateOf(ReportDetailLevel.DETAILED) }
  var selectedFormat by remember { mutableStateOf(ReportExportFormat.PDF) }
  var displayCurrency by remember { mutableStateOf(defaultCurrencyCode) }
  var selectedSections by remember { mutableStateOf(ReportSection.entries.toSet()) }

  var pickingStartDate by remember { mutableStateOf(false) }
  var pickingEndDate by remember { mutableStateOf(false) }
  var showCurrencyPicker by remember { mutableStateOf(false) }

  if (showCurrencyPicker) {
    CurrencyPickerDialog(
      currencies = allCurrencies,
      selectedCode = displayCurrency,
      onSelect = { displayCurrency = it.code },
      onDismiss = { showCurrencyPicker = false },
    )
  }

  if (pickingStartDate || pickingEndDate) {
    val currentDay = if (pickingStartDate) startEpochDay else endEpochDay
    val pickerState = rememberDatePickerState(
      initialSelectedDateMillis = LocalDate.ofEpochDay(currentDay)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli(),
    )
    DatePickerDialog(
      onDismissRequest = {
        pickingStartDate = false
        pickingEndDate = false
      },
      confirmButton = {
        TextButton(
          onClick = {
            pickerState.selectedDateMillis?.let { millis ->
              val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
              if (pickingStartDate) {
                startEpochDay = picked
                if (endEpochDay < picked) endEpochDay = picked
              } else {
                endEpochDay = picked
                if (startEpochDay > picked) startEpochDay = picked
              }
            }
            pickingStartDate = false
            pickingEndDate = false
          },
        ) { Text("Apply") }
      },
      dismissButton = {
        TextButton(
          onClick = {
            pickingStartDate = false
            pickingEndDate = false
          },
        ) { Text("Cancel") }
      },
    ) {
      DatePicker(state = pickerState)
    }
  }

  fun buildRequest(): ReportRequest = ReportRequest(
    periodType = selectedPeriod,
    startEpochDay = startEpochDay,
    endEpochDay = endEpochDay,
    displayCurrencyCode = displayCurrency,
    detailLevel = detailLevel,
    includedSections = selectedSections.ifEmpty { setOf(ReportSection.SUMMARY) },
  )

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
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Export Financial Report",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "Generate branded PDF, Microsoft Word (.docx), or CSV statements across any date range.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Text("1. Report Period", style = MaterialTheme.typography.labelLarge)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportPeriodType.entries.forEach { period ->
          FilterChip(
            selected = selectedPeriod == period,
            onClick = {
              selectedPeriod = period
              when (period) {
                ReportPeriodType.DAILY -> {
                  startEpochDay = today.toEpochDay()
                  endEpochDay = today.toEpochDay()
                }
                ReportPeriodType.WEEKLY -> {
                  startEpochDay = today.minusDays(6).toEpochDay()
                  endEpochDay = today.toEpochDay()
                }
                ReportPeriodType.MONTHLY -> {
                  startEpochDay = YearMonth.from(today).atDay(1).toEpochDay()
                  endEpochDay = YearMonth.from(today).atEndOfMonth().toEpochDay()
                }
                ReportPeriodType.YEARLY -> {
                  startEpochDay = LocalDate.of(today.year, 1, 1).toEpochDay()
                  endEpochDay = LocalDate.of(today.year, 12, 31).toEpochDay()
                }
                ReportPeriodType.CUSTOM -> Unit
              }
            },
            label = { Text(period.label) },
            modifier = Modifier.testTag("export_period_${period.name.lowercase()}"),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = {
            selectedPeriod = ReportPeriodType.CUSTOM
            pickingStartDate = true
          },
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("From: ${LocalDate.ofEpochDay(startEpochDay)}")
        }
        OutlinedButton(
          onClick = {
            selectedPeriod = ReportPeriodType.CUSTOM
            pickingEndDate = true
          },
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("To: ${LocalDate.ofEpochDay(endEpochDay)}")
        }
      }

      Text("2. Export Format", style = MaterialTheme.typography.labelLarge)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportExportFormat.entries.forEach { format ->
          val icon = when (format) {
            ReportExportFormat.PDF -> Icons.Filled.PictureAsPdf
            ReportExportFormat.DOCX -> Icons.Filled.Description
            ReportExportFormat.CSV -> Icons.Filled.TableChart
          }
          FilterChip(
            selected = selectedFormat == format,
            onClick = { selectedFormat = format },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text(format.extension.uppercase()) },
            modifier = Modifier.testTag("export_format_${format.extension}"),
          )
        }
      }

      Text("3. Detail Level & Currency", style = MaterialTheme.typography.labelLarge)
      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ReportDetailLevel.entries.forEachIndexed { idx, level ->
          SegmentedButton(
            selected = detailLevel == level,
            onClick = { detailLevel = level },
            shape = SegmentedButtonDefaults.itemShape(index = idx, count = ReportDetailLevel.entries.size),
          ) {
            Text(level.label, maxLines = 1)
          }
        }
      }

      OutlinedButton(
        onClick = { showCurrencyPicker = true },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Display Currency: $displayCurrency")
      }

      if (selectedFormat != ReportExportFormat.CSV) {
        Text("4. Included Sections", style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          ReportSection.entries.forEach { section ->
            val checked = section in selectedSections
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  selectedSections = if (checked && selectedSections.size > 1) {
                    selectedSections - section
                  } else {
                    selectedSections + section
                  }
                }
                .padding(vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = checked,
                onCheckedChange = { isChecked ->
                  selectedSections = if (!isChecked && selectedSections.size > 1) {
                    selectedSections - section
                  } else {
                    selectedSections + section
                  }
                },
              )
              Text(section.label, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }

      HorizontalDivider()

      if (selectedFormat != ReportExportFormat.CSV) {
        SkyPlusSecondaryButton(
          text = "Preview In App",
          icon = Icons.Filled.Preview,
          onClick = {
            onPreview(buildRequest())
            onDismiss()
          },
          modifier = Modifier.fillMaxWidth().testTag("export_preview_button"),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        SkyPlusSecondaryButton(
          text = "Save",
          icon = Icons.Filled.Download,
          onClick = {
            onExport(buildRequest(), selectedFormat, true)
            onDismiss()
          },
          modifier = Modifier.weight(1f).testTag("export_save_button"),
        )
        SkyPlusPrimaryButton(
          text = "Share",
          icon = Icons.Filled.Share,
          onClick = {
            onExport(buildRequest(), selectedFormat, false)
            onDismiss()
          },
          modifier = Modifier.weight(1f).testTag("export_share_button"),
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
    }
  }
}
