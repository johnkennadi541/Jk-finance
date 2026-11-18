package com.skyplus.finance.presentation.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.skyplus.finance.designsystem.SkyPlusAnimatedProgressBar
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusCategoryDonutChart
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.designsystem.SkyPlusGoldBadge
import com.skyplus.finance.designsystem.SkyPlusIncomeExpenseBarChart
import com.skyplus.finance.designsystem.SkyPlusMetricTile
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.designsystem.SkyPlusPrimaryButton
import com.skyplus.finance.designsystem.SkyPlusSecondaryButton
import com.skyplus.finance.designsystem.SkyPlusSpacingTokens
import com.skyplus.finance.designsystem.SkyPlusTrendLineChart
import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.report.FinancialReportData
import com.skyplus.finance.domain.report.ReportDetailLevel
import com.skyplus.finance.domain.report.ReportExportFormat
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.domain.report.ReportRequest
import com.skyplus.finance.domain.report.ReportSection
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.domain.usecase.PortfolioChartsBundle
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.components.CurrencyPickerDialog
import com.skyplus.finance.presentation.components.ExportReportModalSheet
import com.skyplus.finance.presentation.intelligence.CashflowForecastCard
import com.skyplus.finance.presentation.intelligence.FinancialHealthScoreCard
import com.skyplus.finance.presentation.intelligence.SpendingPatternsCard
import com.skyplus.finance.util.CurrencyFormatter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
  snapshot: DashboardSnapshot,
  reportPreview: FinancialReportData?,
  buildCharts: (ReportPeriodType, Long?, Long?) -> PortfolioChartsBundle,
  onGeneratePreview: (ReportRequest) -> Unit,
  onDismissPreview: () -> Unit,
  onExportReport: (ReportRequest, ReportExportFormat, saveToDevice: Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  val spacing = SkyPlusSpacingTokens()
  val today = remember { LocalDate.now() }

  var selectedPeriod by remember { mutableStateOf(ReportPeriodType.MONTHLY) }
  var startEpochDay by remember { mutableLongStateOf(YearMonth.from(today).atDay(1).toEpochDay()) }
  var endEpochDay by remember { mutableLongStateOf(YearMonth.from(today).atEndOfMonth().toEpochDay()) }
  var displayCurrency by remember(snapshot.primaryCurrencyCode) { mutableStateOf(snapshot.primaryCurrencyCode) }
  var detailLevel by remember { mutableStateOf(ReportDetailLevel.DETAILED) }
  var showCurrencyPicker by remember { mutableStateOf(false) }
  var showExportSheet by remember { mutableStateOf(false) }
  var pickingStart by remember { mutableStateOf(false) }
  var pickingEnd by remember { mutableStateOf(false) }

  val charts = remember(snapshot.allTransactions, displayCurrency, selectedPeriod, startEpochDay, endEpochDay) {
    buildCharts(selectedPeriod, startEpochDay, endEpochDay)
  }
  val displayCurEntity = snapshot.currenciesByCode[displayCurrency.uppercase()]

  fun currentRequest(): ReportRequest = ReportRequest(
    periodType = selectedPeriod,
    startEpochDay = startEpochDay,
    endEpochDay = endEpochDay,
    displayCurrencyCode = displayCurrency,
    detailLevel = detailLevel,
    includedSections = ReportSection.entries.toSet(),
  )

  if (showCurrencyPicker) {
    CurrencyPickerDialog(
      currencies = snapshot.allCurrencies,
      selectedCode = displayCurrency,
      onSelect = { displayCurrency = it.code },
      onDismiss = { showCurrencyPicker = false },
    )
  }

  if (showExportSheet) {
    ExportReportModalSheet(
      allCurrencies = snapshot.allCurrencies,
      defaultCurrencyCode = displayCurrency,
      initialPeriod = selectedPeriod,
      initialStartEpochDay = startEpochDay,
      initialEndEpochDay = endEpochDay,
      onPreview = onGeneratePreview,
      onExport = onExportReport,
      onDismiss = { showExportSheet = false },
    )
  }

  if (pickingStart || pickingEnd) {
    val initialDay = if (pickingStart) startEpochDay else endEpochDay
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
                startEpochDay = picked
                if (endEpochDay < picked) endEpochDay = picked
              } else {
                endEpochDay = picked
                if (startEpochDay > picked) startEpochDay = picked
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

  if (reportPreview != null) {
    ReportPreviewDialog(
      report = reportPreview,
      onDismiss = onDismissPreview,
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = spacing.maxContentWidth),
      contentPadding = PaddingValues(horizontal = spacing.md, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = strings.navReports,
              style = MaterialTheme.typography.headlineMedium,
              modifier = Modifier.testTag("reports_screen_title"),
            )
            Text(
              text = "Portfolio Analytics & Statement Export",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          SkyPlusPrimaryButton(
            text = strings.exportButtonLabel,
            icon = Icons.Filled.Download,
            onClick = { showExportSheet = true },
            modifier = Modifier.testTag("reports_export_button"),
          )
        }
      }

      // Period & Format Controls
      item {
        SkyPlusPortfolioCard {
          Text("Report Parameters", style = MaterialTheme.typography.titleMedium)
          FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                label = { Text(if (period == ReportPeriodType.CUSTOM) "Custom" else period.label, maxLines = 1) },
                modifier = Modifier.testTag("report_period_${period.name.lowercase()}"),
              )
            }
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = {
                selectedPeriod = ReportPeriodType.CUSTOM
                pickingStart = true
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(LocalDate.ofEpochDay(startEpochDay).toString())
            }
            OutlinedButton(
              onClick = {
                selectedPeriod = ReportPeriodType.CUSTOM
                pickingEnd = true
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(LocalDate.ofEpochDay(endEpochDay).toString())
            }
          }

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

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
              onClick = { showCurrencyPicker = true },
              modifier = Modifier.weight(1f),
            ) {
              Text("Currency: $displayCurrency")
            }
            SkyPlusSecondaryButton(
              text = "Preview",
              icon = Icons.Filled.Preview,
              onClick = { onGeneratePreview(currentRequest()) },
              modifier = Modifier.weight(1f).testTag("reports_preview_button"),
            )
          }
        }
      }

      // Period Summary Tiles
      item {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.gridGap)) {
          SkyPlusMetricTile(
            label = "Period Income",
            value = CurrencyFormatter.formatWithEntity(charts.periodIncome, displayCurEntity, displayCurrency),
            icon = Icons.Filled.Preview,
            accentColor = SkyPlusBrandColors.EmeraldGreen,
            modifier = Modifier.weight(1f),
          )
          SkyPlusMetricTile(
            label = "Period Expenses",
            value = CurrencyFormatter.formatWithEntity(charts.periodExpense, displayCurEntity, displayCurrency),
            icon = Icons.Filled.Download,
            accentColor = SkyPlusBrandColors.RoyalBlue,
            modifier = Modifier.weight(1f),
          )
        }
      }

      // Phase 3 Net Worth & Health Snapshot Card
      item {
        SkyPlusPortfolioCard(modifier = Modifier.testTag("reports_net_worth_health_card")) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(strings.netWorthChangeLabel, style = MaterialTheme.typography.titleMedium)
            SkyPlusGoldBadge(text = selectedPeriod.label)
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
              Text(strings.totalNetWorth, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = CurrencyFormatter.formatWithEntity(snapshot.totalNetWorth, displayCurEntity, displayCurrency),
                style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.Bold,
              )
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
              Text("Period Net Flow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = CurrencyFormatter.formatWithEntity(charts.periodNetSavings, displayCurEntity, displayCurrency),
                style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleLarge),
                color = if (charts.periodNetSavings >= BigDecimal.ZERO) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.ErrorCoral,
                fontWeight = FontWeight.Bold,
              )
            }
          }
          snapshot.intelligence?.let { intel ->
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(strings.healthTrendLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = intel.healthReport.overallScore?.let {
                  "$it / 100 (${intel.healthReport.band?.label ?: "Rated"})"
                } ?: "Insufficient Data",
                style = MaterialTheme.typography.labelLarge,
                color = SkyPlusBrandColors.Teal,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        }
      }

      // Income vs Expense Comparison Chart
      item {
        SkyPlusPortfolioCard {
          Text("Income vs Expenses", style = MaterialTheme.typography.titleMedium)
          if (charts.incomeVsExpenseBars.isEmpty()) {
            Text(
              text = "No transactions recorded in this date range.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            SkyPlusIncomeExpenseBarChart(points = charts.incomeVsExpenseBars)
          }
        }
      }

      // Net Trend Chart
      if (charts.netTrendPoints.size >= 2) {
        item {
          SkyPlusPortfolioCard {
            Text("Net Cashflow Trend", style = MaterialTheme.typography.titleMedium)
            SkyPlusTrendLineChart(points = charts.netTrendPoints)
          }
        }
      }

      // Category-wise Expenses Donut Chart
      item {
        SkyPlusPortfolioCard {
          Text("Category-wise Expenses", style = MaterialTheme.typography.titleMedium)
          if (charts.categorySlices.isEmpty()) {
            Text(
              text = "No expense categories recorded in this date range.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            SkyPlusCategoryDonutChart(
              slices = charts.categorySlices,
              centerTitle = "Expenses",
              centerValue = CurrencyFormatter.formatWithEntity(charts.periodExpense, displayCurEntity, displayCurrency),
            )
          }
        }
      }

      // Budget Utilization Overview
      if (snapshot.budgetStatuses.isNotEmpty()) {
        item {
          SkyPlusPortfolioCard {
            Text("Budget Utilization", style = MaterialTheme.typography.titleMedium)
            snapshot.budgetStatuses.forEach { b ->
              val cur = snapshot.currenciesByCode[b.currencyCode.uppercase()]
              val color = when (b.status) {
                BudgetHealthStatus.NORMAL -> SkyPlusBrandColors.EmeraldGreen
                BudgetHealthStatus.APPROACHING_LIMIT -> SkyPlusBrandColors.WarningAmber
                BudgetHealthStatus.EXCEEDED -> SkyPlusBrandColors.ErrorCoral
              }
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text(b.categoryNameEn, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                  Text(
                    text = "${b.utilizationPercent}% (${CurrencyFormatter.formatWithEntity(b.actualSpent, cur, b.currencyCode)})",
                    style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelSmall),
                  )
                }
                SkyPlusAnimatedProgressBar(
                  targetProgress = (b.utilizationPercent.toFloat() / 100f).coerceIn(0f, 1f),
                  barColor = color,
                  heightDp = 7,
                )
              }
            }
          }
        }
      }

      // Phase 2 Financial Intelligence Hub (Forecasting, Spending Patterns & Health Breakdown)
      snapshot.intelligence?.let { intel ->
        item {
          CashflowForecastCard(
            forecast = intel.forecastAndRecommendations.cashflowForecast,
            currencyEntity = snapshot.primaryCurrency,
          )
        }
        item {
          SpendingPatternsCard(
            report = intel.spendingReport,
            currencyEntity = snapshot.primaryCurrency,
          )
        }
        item {
          FinancialHealthScoreCard(
            report = intel.healthReport,
            showDetailedMetrics = true,
            currencyEntity = snapshot.primaryCurrency,
          )
        }
      }

      item { Spacer(modifier = Modifier.height(12.dp)) }
    }
  }
}

@Composable
private fun ReportPreviewDialog(
  report: FinancialReportData,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(report.title) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          Text(
            text = "Period: ${report.dateRangeText} • Display: ${report.displayCurrencyCode}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (ReportSection.SUMMARY in report.includedSections) {
          item {
            HorizontalDivider()
            Text("Executive Summary", style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.RoyalBlue)
            Text("Opening Balance: ${report.openingBalanceFormatted}")
            Text("Total Income: ${report.totalIncomeFormatted}", color = SkyPlusBrandColors.EmeraldGreen)
            Text("Total Expenses: ${report.totalExpensesFormatted}")
            Text("Net Savings: ${report.netSavingsFormatted}", fontWeight = FontWeight.Bold)
            Text("Closing Balance: ${report.closingBalanceFormatted}")
          }
        }
        if (ReportSection.NET_WORTH_CHANGE in report.includedSections) {
          item {
            HorizontalDivider()
            Text("Net Worth Changes", style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.RoyalBlue)
            Text("Start Net Worth: ${report.netWorthStartFormatted}")
            Text("End Net Worth: ${report.netWorthEndFormatted}")
            Text(
              text = "Net Worth Change: ${report.netWorthChangeFormatted}",
              color = if (report.netWorthChangePositive) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.ErrorCoral,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
        if (ReportSection.FINANCIAL_HEALTH in report.includedSections) {
          item {
            HorizontalDivider()
            Text("Financial Health Trends", style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.RoyalBlue)
            Text("Score: ${report.healthScore?.let { "$it / 100" } ?: "N/A"} • ${report.healthBandLabel}")
            Text("Savings Rate: ${report.healthSavingsRatePercent} • DTI: ${report.healthDebtToIncomePercent} • Emergency: ${report.healthEmergencyMonths}")
            if (report.healthTrendSummary.isNotBlank()) {
              Text(report.healthTrendSummary, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
        if (ReportSection.CATEGORY_EXPENSES in report.includedSections && report.categoryExpenses.isNotEmpty()) {
          item {
            HorizontalDivider()
            Text("Category Expenses", style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.RoyalBlue)
            report.categoryExpenses.forEach { row ->
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.name)
                Text("${row.formattedAmount} (${row.sharePercent}%)")
              }
            }
          }
        }
        if (ReportSection.LOANS_AND_EMI in report.includedSections && (report.loanRows.isNotEmpty() || report.emiPaymentRows.isNotEmpty())) {
          item {
            HorizontalDivider()
            Text("Loans & EMI", style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.RoyalBlue)
            Text("EMI Paid in Period: ${report.totalEmiPaidInPeriodFormatted}", fontWeight = FontWeight.SemiBold)
            report.loanRows.forEach { l ->
              Text("${l.loanName}: ${l.outstandingFormatted} • EMI ${l.emiFormatted}")
            }
            report.emiPaymentRows.take(10).forEach { e ->
              Text("${e.date} • ${e.loanName}: Paid ${e.totalPaidFormatted} (Prin ${e.principalFormatted})")
            }
          }
        }
        if (ReportSection.SAVINGS_GOALS in report.includedSections && report.savingsGoalRows.isNotEmpty()) {
          item {
            HorizontalDivider()
            Text("Savings Goals", style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.RoyalBlue)
            report.savingsGoalRows.forEach { g ->
              Text("${g.goalName}: ${g.savedFormatted} / ${g.targetFormatted} (${g.progressPercent}%)")
            }
          }
        }
        if (ReportSection.TRANSACTION_HISTORY in report.includedSections) {
          item {
            HorizontalDivider()
            Text(
              text = "Transactions (${report.transactionRows.size})",
              style = MaterialTheme.typography.titleMedium,
              color = SkyPlusBrandColors.RoyalBlue,
            )
            if (report.transactionRows.isEmpty()) {
              Text("No transactions recorded in this date range.")
            } else {
              report.transactionRows.take(25).forEach { row ->
                Text("${row.date} • ${row.categoryName}: ${row.originalFormatted}")
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}
