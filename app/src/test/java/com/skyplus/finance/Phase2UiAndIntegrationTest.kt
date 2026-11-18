package com.skyplus.finance

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.skyplus.finance.data.database.DatabaseInitializer
import com.skyplus.finance.data.database.Iso4217CurrencyCatalog
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.repository.OfflineFinanceRepository
import com.skyplus.finance.designsystem.SkyPlusBrandTheme
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.calculator.SafeToSpendCalculator
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceEngine
import com.skyplus.finance.domain.intelligence.FinancialHealthScoreEngine
import com.skyplus.finance.domain.intelligence.FinancialHealthStatus
import com.skyplus.finance.domain.intelligence.ForecastAndRecommendationEngine
import com.skyplus.finance.domain.intelligence.ForecastStatus
import com.skyplus.finance.domain.intelligence.SpendingIntelligenceEngine
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.domain.usecase.ObserveDashboardSnapshotUseCase
import com.skyplus.finance.domain.usecase.PortfolioChartBuilder
import com.skyplus.finance.domain.usecase.PortfolioIntelligenceBundle
import com.skyplus.finance.localization.AppStringsProvider
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.budgets.BudgetsTab
import com.skyplus.finance.presentation.home.HomeScreen
import com.skyplus.finance.presentation.loans.LoansTab
import com.skyplus.finance.presentation.reports.ReportsScreen
import com.skyplus.finance.presentation.savings.SavingsTab
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2UiAndIntegrationTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: SkyPlusDatabase
  private lateinit var repository: OfflineFinanceRepository
  private lateinit var observeDashboardSnapshotUseCase: ObserveDashboardSnapshotUseCase

  private val loanCalc = LoanEmiCalculator()
  private val budgetCalc = BudgetAndSavingsCalculator()
  private val safeCalc = SafeToSpendCalculator()
  private val healthEngine = FinancialHealthScoreEngine(budgetCalc)
  private val spendingEngine = SpendingIntelligenceEngine(budgetCalc)
  private val forecastEngine = ForecastAndRecommendationEngine()
  private val debtEngine = DebtPayoffIntelligenceEngine(loanCalc)
  private val chartBuilder = PortfolioChartBuilder()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, SkyPlusDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val initializer = DatabaseInitializer(db)
    repository = OfflineFinanceRepository(db, initializer, loanCalc)
    observeDashboardSnapshotUseCase = ObserveDashboardSnapshotUseCase(
      repository = repository,
      safeToSpendCalculator = safeCalc,
      loanEmiCalculator = loanCalc,
      budgetAndSavingsCalculator = budgetCalc,
      financialHealthScoreEngine = healthEngine,
      spendingIntelligenceEngine = spendingEngine,
      forecastAndRecommendationEngine = forecastEngine,
      debtPayoffIntelligenceEngine = debtEngine,
    )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `observeDashboardSnapshot populates all four intelligence engines offline without mutating loan records`() = runTest {
    repository.ensureInitialized()
    val today = LocalDate.of(2026, 11, 15)
    val categories = repository.observeCategories().first()
    val foodCatId = categories.first { it.type == CategoryType.EXPENSE }.id
    val salaryCatId = categories.first { it.type == CategoryType.INCOME }.id

    repository.updateSettings {
      it.copy(
        primaryCurrencyCode = "INR",
        monthlyEssentialExpenses = BigDecimal("20000.00"),
      )
    }.getOrThrow()

    // Insert 4 months of realistic income and recurring/category expenses
    for (monthsAgo in 0L..3L) {
      val monthDate = today.minusMonths(monthsAgo).withDayOfMonth(5)
      repository.addTransaction(
        TransactionEntity(
          accountId = 0L,
          categoryId = salaryCatId,
          type = TransactionType.INCOME,
          amount = BigDecimal("80000.00"),
          currencyCode = "INR",
          description = "Monthly Salary",
          transactionDateEpochDay = monthDate.toEpochDay(),
          isPaid = true,
          deduplicationKey = "sal-$monthsAgo",
          createdAtEpochMillis = 100L + monthsAgo,
          updatedAtEpochMillis = 100L + monthsAgo,
        ),
      ).getOrThrow()

      val expenseAmt = if (monthsAgo == 0L) BigDecimal("18000.00") else BigDecimal("8000.00")
      repository.addTransaction(
        TransactionEntity(
          accountId = 0L,
          categoryId = foodCatId,
          type = TransactionType.EXPENSE,
          amount = expenseAmt,
          currencyCode = "INR",
          description = "Supermart Groceries",
          transactionDateEpochDay = today.minusMonths(monthsAgo).withDayOfMonth(8).toEpochDay(),
          isPaid = true,
          isRecurring = true,
          recurrenceIntervalDays = 30,
          deduplicationKey = "exp-$monthsAgo",
          createdAtEpochMillis = 200L + monthsAgo,
          updatedAtEpochMillis = 200L + monthsAgo,
        ),
      ).getOrThrow()
    }

    // Add unpaid bill due in 3 days for commitment alert
    repository.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = foodCatId,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("2500.00"),
        currencyCode = "INR",
        description = "Internet Fiber Bill",
        transactionDateEpochDay = today.plusDays(3).toEpochDay(),
        isUpcomingBill = true,
        isPaid = false,
        deduplicationKey = "upcoming-internet",
        createdAtEpochMillis = 300L,
        updatedAtEpochMillis = 300L,
      ),
    ).getOrThrow()

    // Add active budget at > 80% utilization
    val ym = YearMonth.from(today).toString()
    repository.upsertBudget(
      BudgetEntity(
        categoryId = foodCatId,
        yearMonth = ym,
        limitAmount = BigDecimal("20000.00"),
        currencyCode = "INR",
        createdAtEpochMillis = 400L,
        updatedAtEpochMillis = 400L,
      ),
    ).getOrThrow()

    // Add two loans with distinct interest rates
    val loan1Id = repository.addLoan(
      LoanEntity(
        loanName = "Credit Line",
        lender = "HDFC",
        originalPrincipal = BigDecimal("60000.00"),
        outstandingPrincipal = BigDecimal("40000.00"),
        annualInterestRatePercent = BigDecimal("18.0"),
        emiAmount = BigDecimal("4000.00"),
        currencyCode = "INR",
        paymentDueDayOfMonth = 18,
        nextDueDateEpochDay = today.plusDays(3).toEpochDay(),
        remainingTenureMonths = 12,
        startDateEpochDay = today.minusMonths(6).toEpochDay(),
        status = LoanStatus.ACTIVE,
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 500L,
      ),
    ).getOrThrow()

    repository.addLoan(
      LoanEntity(
        loanName = "Car Loan",
        lender = "SBI",
        originalPrincipal = BigDecimal("200000.00"),
        outstandingPrincipal = BigDecimal("150000.00"),
        annualInterestRatePercent = BigDecimal("9.0"),
        emiAmount = BigDecimal("5000.00"),
        currencyCode = "INR",
        paymentDueDayOfMonth = 25,
        nextDueDateEpochDay = today.plusDays(10).toEpochDay(),
        remainingTenureMonths = 36,
        startDateEpochDay = today.minusMonths(6).toEpochDay(),
        status = LoanStatus.ACTIVE,
        createdAtEpochMillis = 501L,
        updatedAtEpochMillis = 501L,
      ),
    ).getOrThrow()

    repository.upsertSavingsGoal(
      SavingsGoalEntity(
        goalName = "Emergency Reserve",
        goalCategory = SavingsGoalType.EMERGENCY_FUND,
        targetAmount = BigDecimal("120000.00"),
        currentSavedAmount = BigDecimal("30000.00"),
        currencyCode = "INR",
        targetDateEpochDay = today.plusMonths(12).toEpochDay(),
        isEmergencyFund = true,
        createdAtEpochMillis = 600L,
        updatedAtEpochMillis = 600L,
      ),
    ).getOrThrow()

    val snapshot = observeDashboardSnapshotUseCase { today }.first()
    val intelligence = snapshot.intelligence
    assertNotNull(intelligence)
    intelligence!!

    // Health Score Engine populated
    assertEquals(FinancialHealthStatus.COMPUTED, intelligence.healthReport.status)
    assertTrue((intelligence.healthReport.overallScore ?: -1) in 0..100)

    // Spending Engine alerts & comparisons populated
    assertTrue(intelligence.spendingReport.alerts.isNotEmpty())
    assertTrue(intelligence.spendingReport.categoryComparisons.isNotEmpty())

    // Forecast & Recommendation Engine populated
    assertEquals(ForecastStatus.READY, intelligence.forecastAndRecommendations.cashflowForecast.status)
    assertEquals(3, intelligence.forecastAndRecommendations.cashflowForecast.nextThreeMonths.size)
    assertTrue(intelligence.forecastAndRecommendations.budgetRecommendations.isNotEmpty())
    assertTrue(intelligence.forecastAndRecommendations.goalRecommendations.isNotEmpty())

    // Debt Payoff Intelligence populated
    assertEquals(2, intelligence.debtPayoffReport.activeLoanCount)

    // Simulating Extra EMI must NEVER mutate actual Room loan record
    val storedLoanBefore = db.loanDao().getLoanById(loan1Id)!!
    val simResult = debtEngine.simulateExtraEmiPayment(
      loan = storedLoanBefore,
      extraMonthlyPayment = BigDecimal("2500.00"),
      decimalPrecision = 2,
      today = today,
    )
    assertTrue(simResult.earlyRepaymentEstimate.interestSaved > BigDecimal.ZERO)
    val storedLoanAfter = db.loanDao().getLoanById(loan1Id)!!
    assertEquals(0, storedLoanBefore.outstandingPrincipal.compareTo(storedLoanAfter.outstandingPrincipal))
    assertEquals(storedLoanBefore.remainingTenureMonths, storedLoanAfter.remainingTenureMonths)
    assertEquals(0, db.loanDao().getAllEmiPaymentsOnce().size)
  }

  @Test
  fun `home screen displays health score and alerts in light and dark themes and handles insufficient data`() {
    val insufficientSnapshot = buildRichSnapshot(
      today = LocalDate.of(2026, 11, 15),
      includeHistory = false,
      themeMode = AppThemeMode.LIGHT,
    )

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme(themeMode = AppThemeMode.LIGHT) {
          HomeScreen(
            snapshot = insufficientSnapshot,
            buildCharts = { p, s, e ->
              chartBuilder.buildCharts(
                insufficientSnapshot.allTransactions,
                insufficientSnapshot.categoriesById,
                insufficientSnapshot.currenciesByCode,
                insufficientSnapshot.primaryCurrencyCode,
                p,
                s,
                e,
              )
            },
            onQuickAddTransaction = {},
            onEditTransaction = {},
            onMarkBillPaid = {},
            onNavigateToTransactions = {},
            onNavigateToBudgets = {},
            onNavigateToLoans = {},
            onNavigateToSavings = {},
            onNavigateToReports = {},
            onUpdateSafeToSpendConfig = { _, _, _, _ -> },
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("net_worth_hero_card").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("financial_health_score_card"))
    composeTestRule.onNodeWithTag("financial_health_score_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Insufficient Data").assertIsDisplayed()
    composeTestRule.onNodeWithText("Need Data").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("smart_alerts_card"))
    composeTestRule.onNodeWithTag("smart_alerts_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("All Clear").assertIsDisplayed()
  }

  @Test
  fun `home screen dark theme renders computed health score active alerts and preserved performance charts`() {
    val richDarkSnapshot = buildRichSnapshot(
      today = LocalDate.of(2026, 11, 15),
      includeHistory = true,
      themeMode = AppThemeMode.DARK,
    )

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme(themeMode = AppThemeMode.DARK) {
          HomeScreen(
            snapshot = richDarkSnapshot,
            buildCharts = { p, s, e ->
              chartBuilder.buildCharts(
                richDarkSnapshot.allTransactions,
                richDarkSnapshot.categoriesById,
                richDarkSnapshot.currenciesByCode,
                richDarkSnapshot.primaryCurrencyCode,
                p,
                s,
                e,
              )
            },
            onQuickAddTransaction = {},
            onEditTransaction = {},
            onMarkBillPaid = {},
            onNavigateToTransactions = {},
            onNavigateToBudgets = {},
            onNavigateToLoans = {},
            onNavigateToSavings = {},
            onNavigateToReports = {},
            onUpdateSafeToSpendConfig = { _, _, _, _ -> },
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("financial_health_score_card"))
    composeTestRule.onNodeWithTag("financial_health_score_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Full Analytics").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("smart_alerts_card"))
    composeTestRule.onNodeWithTag("smart_alerts_card").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("financial_performance_card"))
    composeTestRule.onNodeWithTag("financial_performance_card").assertIsDisplayed()
  }

  @Test
  fun `reports screen renders interactive forecast chart spending patterns and detailed health breakdown`() {
    val snapshot = buildRichSnapshot(
      today = LocalDate.of(2026, 11, 15),
      includeHistory = true,
      themeMode = AppThemeMode.LIGHT,
    )

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme(themeMode = AppThemeMode.LIGHT) {
          ReportsScreen(
            snapshot = snapshot,
            reportPreview = null,
            buildCharts = { p, s, e ->
              chartBuilder.buildCharts(
                snapshot.allTransactions,
                snapshot.categoriesById,
                snapshot.currenciesByCode,
                snapshot.primaryCurrencyCode,
                p,
                s,
                e,
              )
            },
            onGeneratePreview = {},
            onDismissPreview = {},
            onExportReport = { _, _, _ -> },
          )
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("cashflow_forecast_card"))
    composeTestRule.onNodeWithTag("cashflow_forecast_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Income & Expense Forecast").assertIsDisplayed()
    composeTestRule.onNodeWithText("This Mo Est. Net").performScrollTo().assertIsDisplayed()

    // Tap the next month interactive forecast bar selector (index 1)
    composeTestRule.onNodeWithTag("forecast_point_1").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    val nextMonthLabel = snapshot.intelligence!!.forecastAndRecommendations.cashflowForecast.nextThreeMonths[0].monthLabel
    composeTestRule.onNodeWithText("$nextMonthLabel Est. Net").performScrollTo().assertIsDisplayed()

    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("spending_patterns_card"))
    composeTestRule.onNodeWithTag("spending_patterns_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("This Month vs Last Month").performScrollTo().assertIsDisplayed()

    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("financial_health_score_card"))
    composeTestRule.onNodeWithTag("financial_health_score_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Savings Rate").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `budgets tab requires confirmation before applying budget recommendation and savings tab renders advisor`() {
    val snapshot = buildRichSnapshot(
      today = LocalDate.of(2026, 11, 15),
      includeHistory = true,
      themeMode = AppThemeMode.LIGHT,
    )
    var appliedCount = 0
    var appliedLimit = ""

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          BudgetsTab(
            snapshot = snapshot,
            allBudgetHistory = snapshot.budgetStatuses,
            onUpsertBudget = { _, _, limit, _, _, _ ->
              appliedCount++
              appliedLimit = limit
            },
            onDeleteBudget = {},
          )
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("smart_budget_recommendations_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("apply_budget_rec_1").performClick()
    composeTestRule.waitForIdle()

    // Dialog must appear and upsert MUST NOT execute before clicking Confirm
    composeTestRule.onNodeWithText("Confirm Budget Suggestion").assertIsDisplayed()
    assertEquals(0, appliedCount)

    composeTestRule.onNodeWithTag("confirm_apply_budget_rec_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, appliedCount)
    assertTrue(appliedLimit.isNotBlank())
  }

  @Test
  fun `savings tab renders emergency reserve targets and goal contribution advisor`() {
    val snapshot = buildRichSnapshot(
      today = LocalDate.of(2026, 11, 15),
      includeHistory = true,
      themeMode = AppThemeMode.LIGHT,
    )

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          SavingsTab(
            snapshot = snapshot,
            onCreateGoal = { _, _, _, _, _, _, _ -> },
            onContribute = { _, _, _ -> },
            onDeleteGoal = {},
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("savings_advisor_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("3-Month Reserve").assertIsDisplayed()
    composeTestRule.onNodeWithText("6-Month Reserve").assertIsDisplayed()
    composeTestRule.onNodeWithText("Recommended Monthly Goal Pace").assertIsDisplayed()
  }

  @Test
  fun `loans tab renders snowball vs avalanche comparison and live extra emi simulator dialog`() {
    val snapshot = buildRichSnapshot(
      today = LocalDate.of(2026, 11, 15),
      includeHistory = true,
      themeMode = AppThemeMode.LIGHT,
    )
    val firstLoan = snapshot.loans.first()

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          LoansTab(
            snapshot = snapshot,
            onAddLoan = { _, _, _, _, _, _, _, _, _ -> },
            onDeleteLoan = {},
            onRecordEmi = { _, _, _, _ -> },
            onEstimateEarlyRepayment = { loan, extra ->
              loanCalc.estimateEarlyRepayment(loan, extra.toBigDecimalOrNull() ?: BigDecimal.ZERO, 2)
            },
            onSimulateExtraEmi = { loan, extra ->
              val amt = extra.trim().replace(",", "").toBigDecimalOrNull() ?: BigDecimal("-1")
              debtEngine.simulateExtraEmiPayment(loan, amt, 2)
            },
            onCompareDebtStrategy = { extra ->
              val amt = extra.trim().replace(",", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
              debtEngine.compareStrategies(snapshot.loans, "INR", 2, amt)
            },
          )
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("debt_payoff_intelligence_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Snowball vs Avalanche").assertIsDisplayed()

    // Update portfolio extra budget simulator and expand pros/cons timeline
    composeTestRule.onNodeWithTag("portfolio_extra_budget_input").performScrollTo().performTextReplacement("3000")
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Strategy Pros, Cons & Payoff Timeline").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Snowball Advantages").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Avalanche Advantages").performScrollTo().assertIsDisplayed()

    // Scroll down to loan card and launch Extra EMI Simulator Dialog
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("calc_emi_button_${firstLoan.id}"))
    composeTestRule.onNodeWithTag("calc_emi_button_${firstLoan.id}").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("extra_emi_simulator_input").assertIsDisplayed()
    val initialText = composeTestRule.onNodeWithTag("extra_emi_interest_saved")
      .fetchSemanticsNode().config.toString()

    composeTestRule.onNodeWithTag("extra_emi_simulator_input").performTextReplacement("5000")
    composeTestRule.waitForIdle()
    val updatedText = composeTestRule.onNodeWithTag("extra_emi_interest_saved")
      .fetchSemanticsNode().config.toString()

    assertNotEquals(initialText, updatedText)
    composeTestRule.onNodeWithText("Read-only simulation", substring = true).performScrollTo().assertIsDisplayed()
  }

  private fun buildRichSnapshot(
    today: LocalDate,
    includeHistory: Boolean,
    themeMode: AppThemeMode,
  ): DashboardSnapshot {
    val inr = Iso4217CurrencyCatalog.getAllOfficialCurrencies().first { it.code == "INR" }
    val categoriesMap = mapOf(
      1L to CategoryEntity(1L, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
      2L to CategoryEntity(2L, "transport", "Transport", "Transport", CategoryType.EXPENSE, "directions_car", 0xFF00BFA6),
      10L to CategoryEntity(10L, "salary", "Salary", "Salary", CategoryType.INCOME, "payments", 0xFF00BFA6),
    )

    val txs = if (!includeHistory) {
      emptyList()
    } else {
      buildList {
        for (m in 0L..3L) {
          val salDate = today.minusMonths(m).withDayOfMonth(5)
          add(
            TransactionEntity(
              id = 100L + m,
              accountId = 0L,
              categoryId = 10L,
              type = TransactionType.INCOME,
              amount = BigDecimal("75000.00"),
              currencyCode = "INR",
              description = "Monthly Salary",
              transactionDateEpochDay = salDate.toEpochDay(),
              isPaid = true,
              createdAtEpochMillis = 1L,
              updatedAtEpochMillis = 1L,
            ),
          )
          val foodAmt = if (m == 0L) BigDecimal("14500.00") else BigDecimal("7000.00")
          add(
            TransactionEntity(
              id = 200L + m,
              accountId = 0L,
              categoryId = 1L,
              type = TransactionType.EXPENSE,
              amount = foodAmt,
              currencyCode = "INR",
              description = "FreshMart Groceries",
              transactionDateEpochDay = today.minusMonths(m).withDayOfMonth(8).toEpochDay(),
              isPaid = true,
              isRecurring = true,
              recurrenceIntervalDays = 30,
              createdAtEpochMillis = 2L,
              updatedAtEpochMillis = 2L,
            ),
          )
          // Saturday transaction for Weekday vs Weekend analysis
          add(
            TransactionEntity(
              id = 300L + m,
              accountId = 0L,
              categoryId = 2L,
              type = TransactionType.EXPENSE,
              amount = BigDecimal("2200.00"),
              currencyCode = "INR",
              description = "Weekend Metro & Fuel",
              transactionDateEpochDay = today.minusDays(1L + m * 7L).toEpochDay(),
              isPaid = true,
              createdAtEpochMillis = 3L,
              updatedAtEpochMillis = 3L,
            ),
          )
        }
        // Upcoming unpaid bill within 7 days
        add(
          TransactionEntity(
            id = 401L,
            accountId = 0L,
            categoryId = 2L,
            type = TransactionType.EXPENSE,
            amount = BigDecimal("1800.00"),
            currencyCode = "INR",
            description = "Electric Utility Bill",
            transactionDateEpochDay = today.plusDays(2).toEpochDay(),
            isUpcomingBill = true,
            isPaid = false,
            createdAtEpochMillis = 4L,
            updatedAtEpochMillis = 4L,
          ),
        )
      }
    }

    val loans = if (!includeHistory) {
      emptyList()
    } else {
      listOf(
        LoanEntity(
          id = 11L,
          loanName = "Personal Loan",
          lender = "ICICI",
          originalPrincipal = BigDecimal("80000.00"),
          outstandingPrincipal = BigDecimal("45000.00"),
          annualInterestRatePercent = BigDecimal("16.5"),
          emiAmount = BigDecimal("4200.00"),
          currencyCode = "INR",
          paymentDueDayOfMonth = 18,
          nextDueDateEpochDay = today.plusDays(3).toEpochDay(),
          remainingTenureMonths = 12,
          startDateEpochDay = today.minusMonths(8).toEpochDay(),
          status = LoanStatus.ACTIVE,
          createdAtEpochMillis = 1L,
          updatedAtEpochMillis = 1L,
        ),
        LoanEntity(
          id = 12L,
          loanName = "Bike Loan",
          lender = "HDFC",
          originalPrincipal = BigDecimal("120000.00"),
          outstandingPrincipal = BigDecimal("85000.00"),
          annualInterestRatePercent = BigDecimal("9.5"),
          emiAmount = BigDecimal("3500.00"),
          currencyCode = "INR",
          paymentDueDayOfMonth = 24,
          nextDueDateEpochDay = today.plusDays(9).toEpochDay(),
          remainingTenureMonths = 28,
          startDateEpochDay = today.minusMonths(4).toEpochDay(),
          status = LoanStatus.ACTIVE,
          createdAtEpochMillis = 2L,
          updatedAtEpochMillis = 2L,
        ),
      )
    }

    val goals = if (!includeHistory) {
      emptyList()
    } else {
      listOf(
        SavingsGoalEntity(
          id = 21L,
          goalName = "Emergency Shield",
          goalCategory = SavingsGoalType.EMERGENCY_FUND,
          targetAmount = BigDecimal("150000.00"),
          currentSavedAmount = BigDecimal("45000.00"),
          currencyCode = "INR",
          targetDateEpochDay = today.plusMonths(10).toEpochDay(),
          isEmergencyFund = true,
          createdAtEpochMillis = 1L,
          updatedAtEpochMillis = 1L,
        ),
      )
    }

    val budgets = if (!includeHistory) {
      emptyList()
    } else {
      listOf(
        BudgetEntity(
          id = 31L,
          categoryId = 2L,
          yearMonth = YearMonth.from(today).toString(),
          limitAmount = BigDecimal("2500.00"),
          currencyCode = "INR",
          createdAtEpochMillis = 1L,
          updatedAtEpochMillis = 1L,
        ),
      )
    }

    val settings = AppSettingsEntity(
      primaryCurrencyCode = "INR",
      themeMode = themeMode.name,
      monthlyEssentialExpenses = BigDecimal("25000.00"),
      monthlySavingsAllocation = BigDecimal("5000.00"),
      updatedAtEpochMillis = 1L,
    )

    val precisionMap = mapOf("INR" to 2)
    val healthReport = healthEngine.evaluate(
      transactions = txs,
      budgets = budgets,
      categoriesById = categoriesMap,
      loans = loans,
      savingsGoals = goals,
      settings = settings,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    val spendingReport = spendingEngine.analyze(
      transactions = txs,
      budgets = budgets,
      categoriesById = categoriesMap,
      loans = loans,
      emiPayments = emptyList(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    val forecastBundle = forecastEngine.analyze(
      transactions = txs,
      budgets = budgets,
      categoriesById = categoriesMap,
      loans = loans,
      emiPayments = emptyList(),
      savingsGoals = goals,
      settings = settings,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    val debtReport = debtEngine.analyze(
      loans = loans,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      realizedMonthlyIncome = healthReport.metrics.realizedIncome.takeIf { it > BigDecimal.ZERO },
      today = today,
    )

    val safe = safeCalc.calculate(
      SafeToSpendInput(
        availableBalance = BigDecimal("60000.00"),
        confirmedRemainingIncome = BigDecimal.ZERO,
        unpaidUpcomingBills = if (includeHistory) BigDecimal("1800.00") else BigDecimal.ZERO,
        plannedEmiPayments = if (includeHistory) BigDecimal("7700.00") else BigDecimal.ZERO,
        savingsAllocation = BigDecimal("5000.00"),
        reservedAmount = BigDecimal.ZERO,
        remainingDaysInMonth = 16,
        currencyCode = "INR",
        currencyDecimalPrecision = 2,
      ),
    )

    return DashboardSnapshot(
      primaryCurrency = inr,
      primaryCurrencyCode = "INR",
      themeMode = themeMode,
      linkedGoogleEmail = null,
      settings = settings,
      totalAssets = BigDecimal("105000.00"),
      totalLiabilities = loans.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.outstandingPrincipal) },
      totalNetWorth = BigDecimal("105000.00").subtract(loans.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.outstandingPrincipal) }),
      monthlyIncome = if (includeHistory) BigDecimal("75000.00") else BigDecimal.ZERO,
      monthlyExpenses = if (includeHistory) BigDecimal("16700.00") else BigDecimal.ZERO,
      monthlyNetSavings = if (includeHistory) BigDecimal("58300.00") else BigDecimal.ZERO,
      safeToSpend = safe,
      activeLoanCount = loans.size,
      totalOutstandingDebt = loans.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.outstandingPrincipal) },
      totalMonthlyEmi = loans.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.emiAmount) },
      nextEmiDueEpochDay = loans.minOfOrNull { it.nextDueDateEpochDay },
      estimatedDebtFreeEpochDay = null,
      emergencyFund = budgetCalc.computeEmergencyFund(goals, settings.monthlyEssentialExpenses, "INR", 2),
      recentTransactions = txs.take(6),
      allTransactions = txs,
      categoriesById = categoriesMap,
      currenciesByCode = mapOf("INR" to inr),
      allCurrencies = listOf(inr),
      budgetStatuses = budgetCalc.computeBudgetStatuses(budgets, categoriesMap, txs, precisionMap),
      loans = loans,
      emiPayments = emptyList(),
      savingsGoals = goals,
      currencyBreakdowns = emptyList(),
      snowballPlan = loanCalc.calculateDebtStrategyPlan(loans, DebtPayoffStrategy.SNOWBALL, decimalPrecision = 2, today = today),
      avalanchePlan = loanCalc.calculateDebtStrategyPlan(loans, DebtPayoffStrategy.AVALANCHE, decimalPrecision = 2, today = today),
      intelligence = PortfolioIntelligenceBundle(
        healthReport = healthReport,
        spendingReport = spendingReport,
        forecastAndRecommendations = forecastBundle,
        debtPayoffReport = debtReport,
      ),
    )
  }
}
