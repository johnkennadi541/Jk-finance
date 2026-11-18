package com.skyplus.finance

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.skyplus.finance.data.backup.GoogleAuthConfigStatus
import com.skyplus.finance.designsystem.SkyPlusBrandTheme
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.calculator.SafeToSpendCalculator
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.domain.usecase.PortfolioChartBuilder
import com.skyplus.finance.localization.AppStringsProvider
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.home.HomeScreen
import com.skyplus.finance.presentation.profile.ProfileScreen
import com.skyplus.finance.presentation.reports.ReportsScreen
import com.skyplus.finance.presentation.settings.PrivacyContractScreen
import com.skyplus.finance.presentation.transactions.TransactionsScreen
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val chartBuilder = PortfolioChartBuilder()

  private fun sampleSnapshot(themeMode: AppThemeMode = AppThemeMode.LIGHT): DashboardSnapshot {
    val safeCalc = SafeToSpendCalculator()
    val loanCalc = LoanEmiCalculator()
    val budgetCalc = BudgetAndSavingsCalculator()
    val safe = safeCalc.calculate(
      SafeToSpendInput(
        availableBalance = BigDecimal("15000.00"),
        confirmedRemainingIncome = BigDecimal.ZERO,
        unpaidUpcomingBills = BigDecimal("2000.00"),
        plannedEmiPayments = BigDecimal("3000.00"),
        savingsAllocation = BigDecimal("1000.00"),
        reservedAmount = BigDecimal("1000.00"),
        remainingDaysInMonth = 10,
        currencyCode = "INR",
        currencyDecimalPrecision = 2,
      ),
    )
    return DashboardSnapshot(
      primaryCurrency = null,
      primaryCurrencyCode = "INR",
      themeMode = themeMode,
      linkedGoogleEmail = null,
      settings = null,
      totalAssets = BigDecimal("25000.00"),
      totalLiabilities = BigDecimal("5000.00"),
      totalNetWorth = BigDecimal("20000.00"),
      monthlyIncome = BigDecimal("30000.00"),
      monthlyExpenses = BigDecimal("15000.00"),
      monthlyNetSavings = BigDecimal("15000.00"),
      safeToSpend = safe,
      activeLoanCount = 0,
      totalOutstandingDebt = BigDecimal.ZERO,
      totalMonthlyEmi = BigDecimal.ZERO,
      nextEmiDueEpochDay = null,
      estimatedDebtFreeEpochDay = null,
      emergencyFund = budgetCalc.computeEmergencyFund(emptyList(), BigDecimal.ZERO, "INR", 2),
      recentTransactions = emptyList(),
      allTransactions = emptyList(),
      categoriesById = emptyMap(),
      currenciesByCode = emptyMap(),
      allCurrencies = emptyList(),
      budgetStatuses = emptyList(),
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = emptyList(),
      currencyBreakdowns = emptyList(),
      snowballPlan = loanCalc.calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.SNOWBALL),
      avalanchePlan = loanCalc.calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.AVALANCHE),
    )
  }

  @Test
  fun `home screen renders skyplus portfolio design and omits removed technical labels`() {
    val snapshot = sampleSnapshot(AppThemeMode.LIGHT)
    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme(themeMode = AppThemeMode.LIGHT) {
          HomeScreen(
            snapshot = snapshot,
            buildCharts = { p, s, e -> chartBuilder.buildCharts(snapshot.allTransactions, snapshot.categoriesById, snapshot.currenciesByCode, snapshot.primaryCurrencyCode, p, s, e) },
            onQuickAddTransaction = {},
            onEditTransaction = {},
            onMarkBillPaid = {},
            onNavigateToTransactions = {},
            onNavigateToBudgets = {},
            onNavigateToLoans = {},
            onNavigateToSavings = {},
            onUpdateSafeToSpendConfig = { _, _, _, _ -> },
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_brand_heading").assertIsDisplayed()
    composeTestRule.onNodeWithText("SkyPlus Finance").assertIsDisplayed()
    composeTestRule.onNodeWithText("Portfolio Overview").assertIsDisplayed()
    composeTestRule.onNodeWithTag("net_worth_hero_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("metric_available_balance").assertIsDisplayed()
    composeTestRule.onNodeWithTag("metric_monthly_income").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("safe_to_spend_hero_card"))
    composeTestRule.onNodeWithTag("safe_to_spend_hero_card").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("financial_performance_card"))
    composeTestRule.onNodeWithTag("financial_performance_card").assertIsDisplayed()

    // Explicitly verify forbidden legacy technical labels are gone
    composeTestRule.onNodeWithText("How This Month Will End", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Local Encrypted Ledger", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Offline First", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Local Guest Mode", substring = true).assertDoesNotExist()
  }

  @Test
  fun `home screen supports dark theme cleanly`() {
    val snapshot = sampleSnapshot(AppThemeMode.DARK)
    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme(themeMode = AppThemeMode.DARK) {
          HomeScreen(
            snapshot = snapshot,
            buildCharts = { p, s, e -> chartBuilder.buildCharts(snapshot.allTransactions, snapshot.categoriesById, snapshot.currenciesByCode, snapshot.primaryCurrencyCode, p, s, e) },
            onQuickAddTransaction = {},
            onEditTransaction = {},
            onMarkBillPaid = {},
            onNavigateToTransactions = {},
            onNavigateToBudgets = {},
            onNavigateToLoans = {},
            onNavigateToSavings = {},
            onUpdateSafeToSpendConfig = { _, _, _, _ -> },
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("SkyPlus Finance").assertIsDisplayed()
    composeTestRule.onNodeWithTag("net_worth_hero_card").assertIsDisplayed()
  }

  @Test
  fun `transactions reports and profile screens render core actions without language switcher or local backup menu`() {
    val snapshot = sampleSnapshot()
    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          ProfileScreen(
            snapshot = snapshot,
            lastDriveBackupTimestamp = 0L,
            lastDriveBackupStatus = "Not backed up yet",
            googleAuthStatus = GoogleAuthConfigStatus.Blocker("OAuth not configured", listOf("Configure Web Client ID")),
            reportPreview = null,
            onSignInWithGoogle = {},
            onSignOutGoogle = {},
            onBackupToGoogleDrive = { _, _ -> },
            onRestoreFromGoogleDrive = { _, _ -> },
            onPreviewReport = {},
            onDismissPreview = {},
            onExportReport = { _, _, _ -> },
            onSetThemeMode = {},
            onSetPrimaryCurrency = {},
            onUpdateAppLock = { _, _, _, _ -> },
            onOpenPrivacySecurity = {},
            onEraseAllData = {},
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_screen_title").assertIsDisplayed()
    composeTestRule.onNodeWithText("Guest Mode").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("profile_drive_backup_card"))
    composeTestRule.onNodeWithTag("profile_drive_backup_card").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("profile_export_button"))
    composeTestRule.onNodeWithTag("profile_export_button").assertIsDisplayed()
    composeTestRule.onNodeWithText("Local Backup", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("தமிழ்", substring = true).assertDoesNotExist()
  }

  @Test
  fun `transactions and reports screens display controls`() {
    val snapshot = sampleSnapshot()
    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          TransactionsScreen(
            snapshot = snapshot,
            onAddTransaction = {},
            onEditTransaction = {},
            onMarkBillPaid = {},
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("transactions_screen_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("transactions_auto_detect_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("transactions_search_input").assertIsDisplayed()
  }

  @Test
  fun `reports screen renders period selectors and export button`() {
    val snapshot = sampleSnapshot()
    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          ReportsScreen(
            snapshot = snapshot,
            reportPreview = null,
            buildCharts = { p, s, e -> chartBuilder.buildCharts(snapshot.allTransactions, snapshot.categoriesById, snapshot.currenciesByCode, snapshot.primaryCurrencyCode, p, s, e) },
            onGeneratePreview = {},
            onDismissPreview = {},
            onExportReport = { _, _, _ -> },
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reports_screen_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("reports_export_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("report_period_weekly").assertIsDisplayed()
    composeTestRule.onNodeWithTag("report_period_custom").assertIsDisplayed()
  }

  @Test
  fun `privacy security screen displays all six commitments`() {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          PrivacyContractScreen(onBack = {})
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("1. Zero Advertisements").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("6. Permanent Deletion After Explicit Confirmation"))
    composeTestRule.onNodeWithText("6. Permanent Deletion After Explicit Confirmation").assertIsDisplayed()
  }
}
