package com.skyplus.finance.presentation.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.designsystem.SkyPlusAnimatedProgressBar
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusCategoryDonutChart
import com.skyplus.finance.designsystem.SkyPlusEmptyState
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.designsystem.SkyPlusGoldBadge
import com.skyplus.finance.designsystem.SkyPlusHeroCard
import com.skyplus.finance.designsystem.SkyPlusIncomeExpenseBarChart
import com.skyplus.finance.designsystem.SkyPlusMetricTile
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.designsystem.SkyPlusSectionHeader
import com.skyplus.finance.designsystem.SkyPlusSpacingTokens
import com.skyplus.finance.designsystem.SkyPlusTrendLineChart
import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.domain.usecase.PortfolioChartsBundle
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.components.CategoryIconMapper
import com.skyplus.finance.presentation.intelligence.FinancialHealthScoreCard
import com.skyplus.finance.presentation.intelligence.SmartAlertsCard
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

private enum class HomeChartMode(val label: String) {
  CASHFLOW("Income vs Expenses"),
  TREND("Net Trend"),
  CATEGORIES("Categories"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
  snapshot: DashboardSnapshot,
  buildCharts: (ReportPeriodType, Long?, Long?) -> PortfolioChartsBundle,
  onQuickAddTransaction: (TransactionType) -> Unit,
  onEditTransaction: (TransactionEntity) -> Unit,
  onMarkBillPaid: (Long) -> Unit,
  onNavigateToTransactions: () -> Unit,
  onNavigateToBudgets: () -> Unit,
  onNavigateToLoans: () -> Unit,
  onNavigateToSavings: () -> Unit,
  onNavigateToReports: () -> Unit = {},
  onUpdateSafeToSpendConfig: (
    reserved: String,
    savingsTarget: String,
    confirmedIncome: String,
    essentialExpenses: String,
  ) -> Unit,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  val cur = snapshot.primaryCurrency
  val today = remember { LocalDate.now() }

  var showConfigDialog by remember { mutableStateOf(false) }
  var chartPeriod by remember { mutableStateOf(ReportPeriodType.MONTHLY) }
  var chartMode by remember { mutableStateOf(HomeChartMode.CASHFLOW) }
  var customStartEpochDay by remember { mutableLongStateOf(YearMonth.from(today).atDay(1).toEpochDay()) }
  var customEndEpochDay by remember { mutableLongStateOf(today.toEpochDay()) }
  var pickingStart by remember { mutableStateOf(false) }
  var pickingEnd by remember { mutableStateOf(false) }

  val chartsBundle = remember(snapshot.allTransactions, snapshot.primaryCurrencyCode, chartPeriod, customStartEpochDay, customEndEpochDay) {
    buildCharts(chartPeriod, customStartEpochDay, customEndEpochDay)
  }

  if (showConfigDialog) {
    SafeToSpendConfigDialog(
      snapshot = snapshot,
      onSave = { r, s, i, e ->
        onUpdateSafeToSpendConfig(r, s, i, e)
        showConfigDialog = false
      },
      onDismiss = { showConfigDialog = false },
    )
  }

  if (pickingStart || pickingEnd) {
    val initialDay = if (pickingStart) customStartEpochDay else customEndEpochDay
    val state = rememberDatePickerState(
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
            state.selectedDateMillis?.let { millis ->
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
    ) { DatePicker(state = state) }
  }

  val spacing = SkyPlusSpacingTokens()

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = spacing.maxContentWidth),
      contentPadding = PaddingValues(horizontal = spacing.md, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
      // Top Brand Header
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val isDarkSurface = MaterialTheme.colorScheme.background.luminance() < 0.5f
          val brandColor = if (isDarkSurface) {
            SkyPlusBrandColors.SoftCyan
          } else {
            SkyPlusBrandColors.RoyalBlue
          }
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = strings.appName,
              style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
              ),
              color = brandColor,
              modifier = Modifier.testTag("home_brand_heading"),
            )
            Text(
              text = strings.dashboardTitle,
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onBackground,
              modifier = Modifier.testTag("home_dashboard_title"),
            )
          }
          Surface(
            color = SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
          ) {
            Text(
              text = snapshot.primaryCurrencyCode,
              style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelLarge),
              color = SkyPlusBrandColors.RoyalBlue,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }
        }
      }

      // 1. Total Net Worth Hero Card
      item {
        NetWorthHeroCard(snapshot = snapshot)
      }

      // 2. Symmetrical 2x2 Key Financial Metrics Grid
      item {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.gridGap)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.gridGap),
          ) {
            SkyPlusMetricTile(
              label = strings.availableBalance,
              value = CurrencyFormatter.formatWithEntity(
                snapshot.safeToSpend.availableBalance,
                cur,
                snapshot.primaryCurrencyCode,
              ),
              icon = Icons.Filled.AccountBalanceWallet,
              accentColor = SkyPlusBrandColors.RoyalBlue,
              modifier = Modifier.weight(1f).testTag("metric_available_balance"),
            )
            SkyPlusMetricTile(
              label = strings.monthlyIncome,
              value = CurrencyFormatter.formatWithEntity(
                snapshot.monthlyIncome,
                cur,
                snapshot.primaryCurrencyCode,
              ),
              icon = Icons.Filled.ArrowDownward,
              accentColor = SkyPlusBrandColors.EmeraldGreen,
              modifier = Modifier.weight(1f).testTag("metric_monthly_income"),
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.gridGap),
          ) {
            SkyPlusMetricTile(
              label = strings.monthlyExpenses,
              value = CurrencyFormatter.formatWithEntity(
                snapshot.monthlyExpenses,
                cur,
                snapshot.primaryCurrencyCode,
              ),
              icon = Icons.Filled.ArrowUpward,
              accentColor = SkyPlusBrandColors.RoyalBlue,
              modifier = Modifier.weight(1f).testTag("metric_monthly_expenses"),
            )
            SkyPlusMetricTile(
              label = strings.netSavings,
              value = CurrencyFormatter.formatWithEntity(
                snapshot.monthlyNetSavings,
                cur,
                snapshot.primaryCurrencyCode,
              ),
              icon = Icons.Filled.TrendingUp,
              accentColor = if (snapshot.monthlyNetSavings >= BigDecimal.ZERO) {
                SkyPlusBrandColors.EmeraldGreen
              } else {
                SkyPlusBrandColors.ErrorCoral
              },
              modifier = Modifier.weight(1f).testTag("metric_net_savings"),
            )
          }
        }
      }

      // 3. Safe-to-Spend Daily Allowance Card
      item {
        SafeToSpendCard(
          snapshot = snapshot,
          onConfigure = { showConfigDialog = true },
        )
      }

      // 4. Quick Actions
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(strings.quickActions, style = MaterialTheme.typography.titleMedium)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            QuickActionTile(
              label = strings.addExpense,
              icon = Icons.Filled.ArrowUpward,
              accent = SkyPlusBrandColors.RoyalBlue,
              testTag = "quick_add_expense",
              onClick = { onQuickAddTransaction(TransactionType.EXPENSE) },
              modifier = Modifier.weight(1f),
            )
            QuickActionTile(
              label = strings.addIncome,
              icon = Icons.Filled.ArrowDownward,
              accent = SkyPlusBrandColors.EmeraldGreen,
              testTag = "quick_add_income",
              onClick = { onQuickAddTransaction(TransactionType.INCOME) },
              modifier = Modifier.weight(1f),
            )
            QuickActionTile(
              label = strings.addLoan,
              icon = Icons.Filled.AccountBalance,
              accent = SkyPlusBrandColors.RoyalBlue,
              testTag = "quick_add_loan",
              onClick = onNavigateToLoans,
              modifier = Modifier.weight(1f),
            )
            QuickActionTile(
              label = strings.addSavings,
              icon = Icons.Filled.Savings,
              accent = SkyPlusBrandColors.EmeraldGreen,
              testTag = "quick_add_savings",
              onClick = { onQuickAddTransaction(TransactionType.SAVINGS_ALLOCATION) },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      // 4b. Financial Health Score & Smart Alerts (Phase 2 Intelligence)
      snapshot.intelligence?.let { intel ->
        item {
          FinancialHealthScoreCard(
            report = intel.healthReport,
            onOpenFullAnalytics = onNavigateToReports,
            showDetailedMetrics = false,
            currencyEntity = cur,
          )
        }
        item {
          SmartAlertsCard(
            spendingReport = intel.spendingReport,
            currencyEntity = cur,
          )
        }
      }

      // 5. Financial Performance Interactive Charts
      item {
        FinancialPerformanceCard(
          snapshot = snapshot,
          selectedPeriod = chartPeriod,
          selectedMode = chartMode,
          customStartEpochDay = customStartEpochDay,
          customEndEpochDay = customEndEpochDay,
          chartsBundle = chartsBundle,
          onSelectPeriod = { chartPeriod = it },
          onSelectMode = { chartMode = it },
          onPickCustomStart = { pickingStart = true },
          onPickCustomEnd = { pickingEnd = true },
        )
      }

      // 6. Budget Overview
      item {
        BudgetOverviewCard(
          snapshot = snapshot,
          onManageBudgets = onNavigateToBudgets,
        )
      }

      // 7. Active Loans & EMI
      item {
        ActiveLoansOverviewCard(
          snapshot = snapshot,
          onClick = onNavigateToLoans,
        )
      }

      // 8. Savings Goals & Emergency Fund
      item {
        SavingsOverviewCard(
          snapshot = snapshot,
          onClick = onNavigateToSavings,
        )
      }

      // Multi-currency breakdown when multi-currency data exists
      if (snapshot.currencyBreakdowns.size > 1 ||
        (snapshot.currencyBreakdowns.size == 1 && !snapshot.currencyBreakdowns.first().currencyCode.equals(snapshot.primaryCurrencyCode, ignoreCase = true))
      ) {
        item {
          MultiCurrencyHoldingsCard(snapshot = snapshot)
        }
      }

      // 9. Recent Transactions
      item {
        SkyPlusSectionHeader(
          title = strings.recentTransactions,
          actionLabel = if (snapshot.allTransactions.isNotEmpty()) "${strings.viewAll} (${snapshot.allTransactions.size})" else null,
          onAction = onNavigateToTransactions,
        )
      }

      if (snapshot.recentTransactions.isEmpty()) {
        item {
          SkyPlusEmptyState(
            icon = Icons.Filled.Payments,
            title = strings.noTransactionsTitle,
            subtitle = strings.noTransactionsHint,
            actionLabel = strings.addExpense,
            onAction = { onQuickAddTransaction(TransactionType.EXPENSE) },
          )
        }
      } else {
        items(snapshot.recentTransactions, key = { it.id }) { tx ->
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

@Composable
private fun NetWorthHeroCard(snapshot: DashboardSnapshot) {
  val strings = LocalAppStrings.current
  val cur = snapshot.primaryCurrency

  SkyPlusHeroCard(modifier = Modifier.testTag("net_worth_hero_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = strings.totalNetWorth,
        style = MaterialTheme.typography.labelLarge,
        color = SkyPlusBrandColors.SoftCyan,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        snapshot.intelligence?.healthReport?.overallScore?.let { score ->
          SkyPlusGoldBadge(text = "Score $score")
        }
        Surface(
          color = SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.20f),
          shape = RoundedCornerShape(10.dp),
        ) {
          Text(
            text = snapshot.primaryCurrencyCode,
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelSmall),
            color = SkyPlusBrandColors.EmeraldGreen,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
    }

    Text(
      text = CurrencyFormatter.formatWithEntity(snapshot.totalNetWorth, cur, snapshot.primaryCurrencyCode),
      style = SkyPlusFonts.numericStyle(MaterialTheme.typography.displaySmall),
      color = SkyPlusBrandColors.PureWhite,
      modifier = Modifier.testTag("total_net_worth_amount"),
    )

    HorizontalDivider(color = SkyPlusBrandColors.PureWhite.copy(alpha = 0.14f))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = strings.assetsLabel,
          style = MaterialTheme.typography.labelSmall,
          color = SkyPlusBrandColors.PureWhite.copy(alpha = 0.72f),
        )
        Text(
          text = CurrencyFormatter.formatWithEntity(snapshot.totalAssets, cur, snapshot.primaryCurrencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
          color = SkyPlusBrandColors.EmeraldGreen,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
        Text(
          text = strings.liabilitiesLabel,
          style = MaterialTheme.typography.labelSmall,
          color = SkyPlusBrandColors.PureWhite.copy(alpha = 0.72f),
        )
        Text(
          text = CurrencyFormatter.formatWithEntity(snapshot.totalLiabilities, cur, snapshot.primaryCurrencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
          color = if (snapshot.totalLiabilities > BigDecimal.ZERO) SkyPlusBrandColors.WarningAmber else SkyPlusBrandColors.PureWhite,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SafeToSpendCard(
  snapshot: DashboardSnapshot,
  onConfigure: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val s = snapshot.safeToSpend
  val cur = snapshot.primaryCurrency
  val accentColor = when {
    s.isInsufficientFunds -> SkyPlusBrandColors.ErrorCoral
    s.isTightBudget -> SkyPlusBrandColors.WarningAmber
    else -> SkyPlusBrandColors.EmeraldGreen
  }
  val statusMessage = when {
    s.isInsufficientFunds -> strings.statusInsufficient
    s.isTightBudget -> strings.statusTight
    else -> strings.statusHealthy
  }

  SkyPlusPortfolioCard(modifier = Modifier.testTag("safe_to_spend_hero_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = strings.safeToSpendLabel,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          verticalAlignment = Alignment.Bottom,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = CurrencyFormatter.formatWithEntity(s.dailyLimit, cur, s.currencyCode),
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.headlineMedium),
            color = accentColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("safe_to_spend_daily_amount"),
          )
          Text(
            text = strings.perDaySuffix,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
          )
        }
      }

      IconButton(
        onClick = onConfigure,
        modifier = Modifier.testTag("configure_safe_to_spend_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Tune,
          contentDescription = strings.configureSafeToSpend,
          tint = SkyPlusBrandColors.RoyalBlue,
        )
      }
    }

    Surface(
      color = accentColor.copy(alpha = 0.12f),
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = statusMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(modifier = Modifier.weight(1f)) {
        Text(strings.remainingDays, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${s.remainingDaysInMonth}", style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium))
      }
      Column(modifier = Modifier.weight(1f)) {
        val billsAndEmi = s.unpaidUpcomingBills.add(s.plannedEmiPayments)
        Text(strings.upcomingBillsAndEmis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = CurrencyFormatter.formatWithEntity(billsAndEmi, cur, s.currencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
        )
      }
      Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
        Text(strings.savingsTarget, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = CurrencyFormatter.formatWithEntity(s.savingsAllocation, cur, s.currencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
        )
      }
    }
  }
}

@Composable
private fun QuickActionTile(
  label: String,
  icon: ImageVector,
  accent: Color,
  testTag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SkyPlusPortfolioCard(
    modifier = modifier
      .minimumInteractiveComponentSize()
      .testTag(testTag),
    onClick = onClick,
    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = label,
          tint = accent,
          modifier = Modifier.size(20.dp),
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FinancialPerformanceCard(
  snapshot: DashboardSnapshot,
  selectedPeriod: ReportPeriodType,
  selectedMode: HomeChartMode,
  customStartEpochDay: Long,
  customEndEpochDay: Long,
  chartsBundle: PortfolioChartsBundle,
  onSelectPeriod: (ReportPeriodType) -> Unit,
  onSelectMode: (HomeChartMode) -> Unit,
  onPickCustomStart: () -> Unit,
  onPickCustomEnd: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val cur = snapshot.primaryCurrency

  SkyPlusPortfolioCard(
    modifier = Modifier
      .animateContentSize()
      .testTag("financial_performance_card"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.BarChart, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
        Text(strings.financialPerformance, style = MaterialTheme.typography.titleMedium)
      }
      Text(
        text = CurrencyFormatter.formatWithEntity(chartsBundle.periodNetSavings, cur, snapshot.primaryCurrencyCode),
        style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelLarge),
        color = if (chartsBundle.periodNetSavings >= BigDecimal.ZERO) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.ErrorCoral,
      )
    }

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      ReportPeriodType.entries.forEach { period ->
        FilterChip(
          selected = selectedPeriod == period,
          onClick = { onSelectPeriod(period) },
          label = { Text(if (period == ReportPeriodType.CUSTOM) "Custom" else period.label, maxLines = 1) },
          modifier = Modifier.testTag("home_chart_period_${period.name.lowercase()}"),
        )
      }
    }

    if (selectedPeriod == ReportPeriodType.CUSTOM) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPickCustomStart, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(LocalDate.ofEpochDay(customStartEpochDay).toString(), style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onPickCustomEnd, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(LocalDate.ofEpochDay(customEndEpochDay).toString(), style = MaterialTheme.typography.labelSmall)
        }
      }
    }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      HomeChartMode.entries.forEachIndexed { idx, mode ->
        SegmentedButton(
          selected = selectedMode == mode,
          onClick = { onSelectMode(mode) },
          shape = SegmentedButtonDefaults.itemShape(index = idx, count = HomeChartMode.entries.size),
        ) {
          Text(mode.label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
      }
    }

    when (selectedMode) {
      HomeChartMode.CASHFLOW -> {
        if (chartsBundle.incomeVsExpenseBars.isEmpty()) {
          Text(
            text = "Record income or expenses in this period to view interactive cashflow comparisons.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          SkyPlusIncomeExpenseBarChart(points = chartsBundle.incomeVsExpenseBars)
        }
      }

      HomeChartMode.TREND -> {
        if (chartsBundle.netTrendPoints.size < 2) {
          Text(
            text = "Add transactions across multiple intervals to view your net cashflow trend.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          SkyPlusTrendLineChart(points = chartsBundle.netTrendPoints)
        }
      }

      HomeChartMode.CATEGORIES -> {
        if (chartsBundle.categorySlices.isEmpty()) {
          Text(
            text = "No expense categories recorded in this period.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          SkyPlusCategoryDonutChart(
            slices = chartsBundle.categorySlices,
            centerTitle = "Expenses",
            centerValue = CurrencyFormatter.formatWithEntity(chartsBundle.periodExpense, cur, snapshot.primaryCurrencyCode),
          )
        }
      }
    }
  }
}

@Composable
private fun BudgetOverviewCard(
  snapshot: DashboardSnapshot,
  onManageBudgets: () -> Unit,
) {
  val strings = LocalAppStrings.current
  SkyPlusPortfolioCard(
    onClick = onManageBudgets,
    modifier = Modifier.testTag("budget_overview_card"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(strings.budgetOverview, style = MaterialTheme.typography.titleMedium)
      Text(
        text = if (snapshot.budgetStatuses.isEmpty()) "Set Up" else "${snapshot.budgetStatuses.size} Active",
        style = MaterialTheme.typography.labelSmall,
        color = SkyPlusBrandColors.RoyalBlue,
        fontWeight = FontWeight.Bold,
      )
    }

    if (snapshot.budgetStatuses.isEmpty()) {
      Text(
        text = strings.noBudgetsHint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      snapshot.budgetStatuses.take(3).forEach { b ->
        val cur = snapshot.currenciesByCode[b.currencyCode.uppercase()]
        val barColor = when (b.status) {
          BudgetHealthStatus.NORMAL -> SkyPlusBrandColors.EmeraldGreen
          BudgetHealthStatus.APPROACHING_LIMIT -> SkyPlusBrandColors.WarningAmber
          BudgetHealthStatus.EXCEEDED -> SkyPlusBrandColors.ErrorCoral
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(b.categoryNameEn, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
              text = "${CurrencyFormatter.formatWithEntity(b.actualSpent, cur, b.currencyCode)} / ${CurrencyFormatter.formatWithEntity(b.effectiveLimit, cur, b.currencyCode)}",
              style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelSmall),
            )
          }
          SkyPlusAnimatedProgressBar(
            targetProgress = (b.utilizationPercent.toFloat() / 100f).coerceIn(0f, 1f),
            barColor = barColor,
            heightDp = 7,
          )
        }
      }
    }
  }
}

@Composable
private fun ActiveLoansOverviewCard(
  snapshot: DashboardSnapshot,
  onClick: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val cur = snapshot.primaryCurrency
  SkyPlusPortfolioCard(
    onClick = onClick,
    modifier = Modifier.testTag("debt_emi_snapshot_card"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(strings.debtAndEmiSnapshot, style = MaterialTheme.typography.titleMedium)
      Text(
        text = "${snapshot.activeLoanCount} ${strings.activeLoansLabel}",
        style = MaterialTheme.typography.labelSmall,
        color = if (snapshot.activeLoanCount > 0) SkyPlusBrandColors.WarningAmber else SkyPlusBrandColors.EmeraldGreen,
        fontWeight = FontWeight.Bold,
      )
    }

    if (snapshot.activeLoanCount == 0) {
      Text(
        text = strings.noActiveLoans,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
          Text(strings.totalOutstandingDebt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = CurrencyFormatter.formatWithEntity(snapshot.totalOutstandingDebt, cur, snapshot.primaryCurrencyCode),
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text(strings.monthlyEmiTotal, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = CurrencyFormatter.formatWithEntity(snapshot.totalMonthlyEmi, cur, snapshot.primaryCurrencyCode),
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
          )
        }
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
          Text(strings.nextEmiDue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = snapshot.nextEmiDueEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text(strings.estDebtFreeDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = snapshot.estimatedDebtFreeEpochDay?.let { "${LocalDate.ofEpochDay(it)} (${strings.estimatedLabel})" } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun SavingsOverviewCard(
  snapshot: DashboardSnapshot,
  onClick: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val ef = snapshot.emergencyFund
  val cur = snapshot.primaryCurrency
  val progressFraction = (ef.progressPercent.toFloat() / 100f).coerceIn(0f, 1f)

  SkyPlusPortfolioCard(
    onClick = onClick,
    modifier = Modifier.testTag("emergency_fund_card"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = SkyPlusBrandColors.EmeraldGreen)
        Text(strings.emergencyFundTitle, style = MaterialTheme.typography.titleMedium)
      }
      Text(
        text = "${ef.monthsCovered} mo",
        style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
        color = SkyPlusBrandColors.EmeraldGreen,
        fontWeight = FontWeight.Bold,
      )
    }

    SkyPlusAnimatedProgressBar(
      targetProgress = progressFraction,
      barColor = SkyPlusBrandColors.EmeraldGreen,
      heightDp = 8,
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column {
        Text(strings.currentSavings, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = CurrencyFormatter.formatWithEntity(ef.currentEmergencySavings, cur, ef.currencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.bodyLarge),
          fontWeight = FontWeight.SemiBold,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(strings.monthlyEssentialExpenses, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = CurrencyFormatter.formatWithEntity(ef.monthlyEssentialExpenses, cur, ef.currencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.bodyLarge),
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun MultiCurrencyHoldingsCard(snapshot: DashboardSnapshot) {
  val strings = LocalAppStrings.current
  SkyPlusPortfolioCard {
    Text(strings.multiCurrencySummary, style = MaterialTheme.typography.titleMedium)
    snapshot.currencyBreakdowns.forEach { item ->
      val cur = snapshot.currenciesByCode[item.currencyCode.uppercase()]
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${item.currencyCode} (${cur?.symbol ?: item.currencyCode})",
          style = MaterialTheme.typography.labelLarge,
        )
        Text(
          text = CurrencyFormatter.formatWithEntity(item.netBalance, cur, item.currencyCode),
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.bodyLarge),
          color = if (item.netBalance >= BigDecimal.ZERO) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.ErrorCoral,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
fun TransactionRowCard(
  tx: TransactionEntity,
  snapshot: DashboardSnapshot,
  onClick: () -> Unit,
  onMarkPaid: () -> Unit,
) {
  val cat = snapshot.categoriesById[tx.categoryId]
  val cur = snapshot.currenciesByCode[tx.currencyCode.uppercase()]
  val catName = cat?.displayNameEn ?: "Other"
  val title = tx.description.ifBlank { catName }
  val accent = when (tx.type) {
    TransactionType.INCOME -> SkyPlusBrandColors.EmeraldGreen
    TransactionType.EXPENSE -> SkyPlusBrandColors.ErrorCoral
    TransactionType.SAVINGS_ALLOCATION -> SkyPlusBrandColors.RoyalBlue
  }
  val prefix = when (tx.type) {
    TransactionType.INCOME -> "+"
    TransactionType.EXPENSE -> "-"
    TransactionType.SAVINGS_ALLOCATION -> "→"
  }

  SkyPlusPortfolioCard(
    onClick = onClick,
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    modifier = Modifier.testTag("tx_row_${tx.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(Color(cat?.colorHex ?: 0xFF2878D0).copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = CategoryIconMapper.iconFor(cat?.iconKey ?: "category"),
          contentDescription = catName,
          tint = Color(cat?.colorHex ?: 0xFF2878D0),
          modifier = Modifier.size(20.dp),
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "$catName • ${LocalDate.ofEpochDay(tx.transactionDateEpochDay)}" +
            if (tx.isUpcomingBill && !tx.isPaid) " • UNPAID BILL" else "",
          style = MaterialTheme.typography.bodyMedium,
          color = if (tx.isUpcomingBill && !tx.isPaid) SkyPlusBrandColors.WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Column(horizontalAlignment = Alignment.End) {
        Text(
          text = "$prefix${CurrencyFormatter.formatWithEntity(tx.amount, cur, tx.currencyCode)}",
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
          color = accent,
          fontWeight = FontWeight.Bold,
        )
        if (tx.isUpcomingBill && !tx.isPaid) {
          TextButton(
            onClick = onMarkPaid,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
          ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Mark Paid", style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
  }
}

@Composable
private fun SafeToSpendConfigDialog(
  snapshot: DashboardSnapshot,
  onSave: (String, String, String, String) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val settings = snapshot.settings
  var reserved by remember {
    mutableStateOf(DecimalUtils.toCanonicalString(settings?.reservedAmount ?: BigDecimal.ZERO))
  }
  var savings by remember {
    mutableStateOf(DecimalUtils.toCanonicalString(settings?.monthlySavingsAllocation ?: BigDecimal.ZERO))
  }
  var confirmedIncome by remember {
    mutableStateOf(DecimalUtils.toCanonicalString(settings?.confirmedRemainingIncome ?: BigDecimal.ZERO))
  }
  var essentials by remember {
    mutableStateOf(DecimalUtils.toCanonicalString(settings?.monthlyEssentialExpenses ?: BigDecimal.ZERO))
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.configureSafeToSpend) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = reserved,
          onValueChange = { reserved = it },
          label = { Text("${strings.reservedAmount} (${snapshot.primaryCurrencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
        OutlinedTextField(
          value = savings,
          onValueChange = { savings = it },
          label = { Text("${strings.savingsTarget} (${snapshot.primaryCurrencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
        OutlinedTextField(
          value = confirmedIncome,
          onValueChange = { confirmedIncome = it },
          label = { Text("${strings.confirmedRemainingIncome} (${snapshot.primaryCurrencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
        OutlinedTextField(
          value = essentials,
          onValueChange = { essentials = it },
          label = { Text("${strings.monthlyEssentialExpenses} (${snapshot.primaryCurrencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
      }
    },
    confirmButton = {
      Button(onClick = { onSave(reserved, savings, confirmedIncome, essentials) }) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}
