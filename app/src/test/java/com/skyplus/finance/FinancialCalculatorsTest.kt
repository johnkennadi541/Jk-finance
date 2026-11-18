package com.skyplus.finance

import com.skyplus.finance.data.database.DefaultCategoryCatalog
import com.skyplus.finance.data.database.Iso4217CurrencyCatalog
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.security.BackupCryptoManager
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.calculator.SafeToSpendCalculator
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.report.ReportBuilderUseCase
import com.skyplus.finance.domain.report.ReportDetailLevel
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.domain.report.ReportRequest
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.AppStringsProvider
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialCalculatorsTest {

  private val safeToSpendCalculator = SafeToSpendCalculator()
  private val loanEmiCalculator = LoanEmiCalculator()
  private val budgetAndSavingsCalculator = BudgetAndSavingsCalculator()

  @Test
  fun `safe to spend exact formula and remaining days including today`() {
    val today = LocalDate.of(2026, 11, 21)
    val remainingDays = safeToSpendCalculator.remainingDaysIncludingToday(today)
    assertEquals(10, remainingDays)

    val result = safeToSpendCalculator.calculate(
      SafeToSpendInput(
        availableBalance = BigDecimal("2500.00"),
        confirmedRemainingIncome = BigDecimal("500.00"),
        unpaidUpcomingBills = BigDecimal("400.00"),
        plannedEmiPayments = BigDecimal("300.00"),
        savingsAllocation = BigDecimal("200.00"),
        reservedAmount = BigDecimal("100.00"),
        remainingDaysInMonth = remainingDays,
        currencyCode = "INR",
        currencyDecimalPrecision = 2,
      ),
    )

    assertEquals(BigDecimal("2000.00"), result.spendablePool)
    assertEquals(BigDecimal("200.00"), result.dailyLimit)
    assertFalse(result.isInsufficientFunds)
  }

  @Test
  fun `safe to spend handles negative balances and insufficient funds`() {
    val result = safeToSpendCalculator.calculate(
      SafeToSpendInput(
        availableBalance = BigDecimal("-150.000"),
        confirmedRemainingIncome = BigDecimal("50.000"),
        unpaidUpcomingBills = BigDecimal("100.000"),
        plannedEmiPayments = BigDecimal.ZERO,
        savingsAllocation = BigDecimal.ZERO,
        reservedAmount = BigDecimal.ZERO,
        remainingDaysInMonth = 5,
        currencyCode = "KWD",
        currencyDecimalPrecision = 3,
      ),
    )
    assertTrue(result.isInsufficientFunds)
    assertEquals(BigDecimal("-200.000"), result.spendablePool)
    assertEquals(BigDecimal("-40.000"), result.dailyLimit)
  }

  @Test
  fun `exact decimal precision and multi currency formatting`() {
    val sum = DecimalUtils.fromStringOrZero("0.1").add(DecimalUtils.fromStringOrZero("0.2"))
    assertEquals(0, sum.compareTo(BigDecimal("0.3")))

    assertEquals("د.ك 12.500", CurrencyFormatter.format(BigDecimal("12.5"), "KWD", "د.ك", 3))
    assertEquals("¥ 1,500", CurrencyFormatter.format(BigDecimal("1500"), "JPY", "¥", 0))
    assertEquals("₹ 1,250.75", CurrencyFormatter.format(BigDecimal("1250.75"), "INR", "₹", 2))
  }

  @Test
  fun `iso 4217 catalog supports global currencies and locale suggestion`() {
    val all = Iso4217CurrencyCatalog.getAllOfficialCurrencies()
    assertTrue(all.size > 100)
    val kwd = all.first { it.code == "KWD" }
    val jpy = all.first { it.code == "JPY" }
    val inr = all.first { it.code == "INR" }
    assertEquals(3, kwd.decimalPrecision)
    assertEquals(0, jpy.decimalPrecision)
    assertEquals(2, inr.decimalPrecision)

    assertEquals("KWD", Iso4217CurrencyCatalog.suggestDefaultCurrencyCode(Locale.Builder().setLanguage("en").setRegion("KW").build()))
    assertEquals("INR", Iso4217CurrencyCatalog.suggestDefaultCurrencyCode(Locale.Builder().setLanguage("en").setRegion("IN").build()))
  }

  @Test
  fun `multi currency transactions are isolated per currency`() {
    val today = LocalDate.of(2026, 11, 14).toEpochDay()
    val txs = listOf(
      TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("1000.00"), "INR", transactionDateEpochDay = today, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("250.00"), "INR", transactionDateEpochDay = today, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 10, TransactionType.INCOME, BigDecimal("500.000"), "KWD", transactionDateEpochDay = today, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      TransactionEntity(4, 1, 15, TransactionType.SAVINGS_ALLOCATION, BigDecimal("100.000"), "KWD", transactionDateEpochDay = today, createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
    )

    val breakdowns = budgetAndSavingsCalculator.computeCurrencyBreakdowns(
      transactions = txs,
      yearMonth = YearMonth.of(2026, 11),
      currencyPrecisionMap = mapOf("INR" to 2, "KWD" to 3),
    )

    assertEquals(2, breakdowns.size)
    val inr = breakdowns.first { it.currencyCode == "INR" }
    val kwd = breakdowns.first { it.currencyCode == "KWD" }
    assertEquals(BigDecimal("750.00"), inr.netBalance)
    assertEquals(BigDecimal("400.000"), kwd.netBalance)
  }

  @Test
  fun `loan emi calculation interest split early repayment and snowball vs avalanche`() {
    val emi = loanEmiCalculator.calculateMonthlyEmi(
      principal = BigDecimal("100000"),
      annualInterestRatePercent = BigDecimal("12"),
      tenureMonths = 12,
      decimalPrecision = 2,
    )
    assertEquals(BigDecimal("8884.88"), emi)

    val split = loanEmiCalculator.calculatePaymentSplit(
      outstandingPrincipal = BigDecimal("100000"),
      annualInterestRatePercent = BigDecimal("12"),
      totalPaymentAmount = emi,
      decimalPrecision = 2,
    )
    assertEquals(BigDecimal("1000.00"), split.interestComponent)
    assertEquals(BigDecimal("7884.88"), split.principalComponent)
    assertEquals(BigDecimal("92115.12"), split.remainingBalanceAfter)

    val today = LocalDate.of(2026, 11, 14)
    val smallLowRateLoan = LoanEntity(
      id = 1, loanName = "Phone", lender = "Bank A",
      originalPrincipal = BigDecimal("15000"), outstandingPrincipal = BigDecimal("10000"),
      annualInterestRatePercent = BigDecimal("6"), emiAmount = BigDecimal("1000"),
      currencyCode = "INR", paymentDueDayOfMonth = 5, nextDueDateEpochDay = today.toEpochDay(),
      remainingTenureMonths = 11, startDateEpochDay = today.toEpochDay(), status = LoanStatus.ACTIVE,
      createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )
    val largeHighRateLoan = LoanEntity(
      id = 2, loanName = "Card Loan", lender = "Bank B",
      originalPrincipal = BigDecimal("50000"), outstandingPrincipal = BigDecimal("40000"),
      annualInterestRatePercent = BigDecimal("24"), emiAmount = BigDecimal("2500"),
      currencyCode = "INR", paymentDueDayOfMonth = 10, nextDueDateEpochDay = today.toEpochDay(),
      remainingTenureMonths = 20, startDateEpochDay = today.toEpochDay(), status = LoanStatus.ACTIVE,
      createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

    val early = loanEmiCalculator.estimateEarlyRepayment(largeHighRateLoan, BigDecimal("1500"), 2, today)
    assertTrue(early.monthsSaved > 0)
    assertTrue(early.interestSaved > BigDecimal.ZERO)

    val snowball = loanEmiCalculator.calculateDebtStrategyPlan(
      listOf(smallLowRateLoan, largeHighRateLoan),
      DebtPayoffStrategy.SNOWBALL,
      extraMonthlyBudget = BigDecimal("1000"),
      today = today,
    )
    val avalanche = loanEmiCalculator.calculateDebtStrategyPlan(
      listOf(smallLowRateLoan, largeHighRateLoan),
      DebtPayoffStrategy.AVALANCHE,
      extraMonthlyBudget = BigDecimal("1000"),
      today = today,
    )
    assertEquals(listOf("Phone", "Card Loan"), snowball.orderedLoanNames)
    assertEquals(listOf("Card Loan", "Phone"), avalanche.orderedLoanNames)
    assertTrue(avalanche.totalInterestProjected <= snowball.totalInterestProjected)
  }

  @Test
  fun `budget thresholds rollover and savings exclusion`() {
    val today = LocalDate.of(2026, 11, 14).toEpochDay()
    val foodCat = CategoryEntity(1, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFFF59E0B)
    val transportCat = CategoryEntity(4, "transport", "Transport", "Transport", CategoryType.EXPENSE, "directions_car", 0xFF06B6D4)
    val medicalCat = CategoryEntity(5, "medical", "Medical", "Medical", CategoryType.EXPENSE, "medical_services", 0xFFF43F5E)

    val budgets = listOf(
      BudgetEntity(1, 1, "2026-11", BigDecimal("1000"), "INR", rolloverEnabled = true, rolloverAmount = BigDecimal("200"), warningThresholdPercent = 80, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      BudgetEntity(2, 4, "2026-11", BigDecimal("1000"), "INR", rolloverEnabled = false, rolloverAmount = BigDecimal.ZERO, warningThresholdPercent = 80, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      BudgetEntity(3, 5, "2026-11", BigDecimal("500"), "INR", rolloverEnabled = false, rolloverAmount = BigDecimal.ZERO, warningThresholdPercent = 80, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val txs = listOf(
      TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("600"), "INR", transactionDateEpochDay = today, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.SAVINGS_ALLOCATION, BigDecimal("1000"), "INR", transactionDateEpochDay = today, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(3, 1, 4, TransactionType.EXPENSE, BigDecimal("850"), "INR", transactionDateEpochDay = today, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(4, 1, 5, TransactionType.EXPENSE, BigDecimal("650"), "INR", transactionDateEpochDay = today, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val statuses = budgetAndSavingsCalculator.computeBudgetStatuses(
      budgets = budgets,
      categories = mapOf(1L to foodCat, 4L to transportCat, 5L to medicalCat),
      transactions = txs,
      currencyPrecisionMap = mapOf("INR" to 2),
    )

    assertEquals(BudgetHealthStatus.NORMAL, statuses[0].status)
    assertEquals(BigDecimal("1200.00"), statuses[0].effectiveLimit)
    assertEquals(BigDecimal("600.00"), statuses[0].remainingAmount)

    assertEquals(BudgetHealthStatus.APPROACHING_LIMIT, statuses[1].status)
    assertEquals(BudgetHealthStatus.EXCEEDED, statuses[2].status)
    assertEquals(BigDecimal("-150.00"), statuses[2].remainingAmount)
  }

  @Test
  fun `emergency fund coverage calculation`() {
    val goals = listOf(
      SavingsGoalEntity(1, "Emergency Reserve", SavingsGoalType.EMERGENCY_FUND, BigDecimal("60000"), BigDecimal("35000"), "INR", isEmergencyFund = true, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      SavingsGoalEntity(2, "Vacation", SavingsGoalType.TRAVEL, BigDecimal("20000"), BigDecimal("10000"), "INR", isEmergencyFund = false, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )
    val summary = budgetAndSavingsCalculator.computeEmergencyFund(
      goals = goals,
      monthlyEssentialExpenses = BigDecimal("10000"),
      primaryCurrencyCode = "INR",
      primaryPrecision = 2,
    )
    assertEquals(BigDecimal("35000.00"), summary.currentEmergencySavings)
    assertEquals(BigDecimal("3.5"), summary.monthsCovered)
  }

  @Test
  fun `english brand copy excludes removed technical labels`() {
    val en = AppStringsProvider.english
    assertEquals("Portfolio Overview", en.dashboardTitle)
    assertEquals("Guest Mode", en.guestModeLabel)
    assertFalse(en.dashboardTitle.contains("How This Month Will End", ignoreCase = true))
    assertFalse(en.dashboardSubtitle.contains("Local Encrypted Ledger", ignoreCase = true))
    assertFalse(en.guestModeSubtitle.contains("Offline First", ignoreCase = true))
    assertTrue(DefaultCategoryCatalog.defaultCategories().all { it.displayNameEn.isNotBlank() })
  }

  @Test
  fun `report builder computes opening closing net savings across custom date ranges`() {
    val builder = ReportBuilderUseCase(budgetAndSavingsCalculator)
    val inr = Iso4217CurrencyCatalog.getAllOfficialCurrencies().first { it.code == "INR" }
    val nov1 = LocalDate.of(2026, 11, 1).toEpochDay()
    val nov10 = LocalDate.of(2026, 11, 10).toEpochDay()
    val nov15 = LocalDate.of(2026, 11, 15).toEpochDay()

    val txs = listOf(
      TransactionEntity(1, 0, 10, TransactionType.INCOME, BigDecimal("5000.00"), "INR", transactionDateEpochDay = nov1, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 0, 10, TransactionType.INCOME, BigDecimal("10000.00"), "INR", transactionDateEpochDay = nov10, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 0, 1, TransactionType.EXPENSE, BigDecimal("2500.00"), "INR", transactionDateEpochDay = nov15, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
    )

    val safe = safeToSpendCalculator.calculate(
      SafeToSpendInput(BigDecimal("12500.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 15, "INR", 2),
    )
    val snapshot = DashboardSnapshot(
      primaryCurrency = inr,
      primaryCurrencyCode = "INR",
      themeMode = AppThemeMode.LIGHT,
      linkedGoogleEmail = null,
      settings = null,
      totalAssets = BigDecimal("12500.00"),
      totalLiabilities = BigDecimal.ZERO,
      totalNetWorth = BigDecimal("12500.00"),
      monthlyIncome = BigDecimal("15000.00"),
      monthlyExpenses = BigDecimal("2500.00"),
      monthlyNetSavings = BigDecimal("12500.00"),
      safeToSpend = safe,
      activeLoanCount = 0,
      totalOutstandingDebt = BigDecimal.ZERO,
      totalMonthlyEmi = BigDecimal.ZERO,
      nextEmiDueEpochDay = null,
      estimatedDebtFreeEpochDay = null,
      emergencyFund = budgetAndSavingsCalculator.computeEmergencyFund(emptyList(), BigDecimal.ZERO, "INR", 2),
      recentTransactions = txs,
      allTransactions = txs,
      categoriesById = mapOf(
        1L to CategoryEntity(1, "food", "Food & Dining", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
        10L to CategoryEntity(10, "salary", "Salary", "Salary", CategoryType.INCOME, "payments", 0xFF00BFA6),
      ),
      currenciesByCode = mapOf("INR" to inr),
      allCurrencies = listOf(inr),
      budgetStatuses = emptyList(),
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = emptyList(),
      currencyBreakdowns = emptyList(),
      snowballPlan = loanEmiCalculator.calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.SNOWBALL),
      avalanchePlan = loanEmiCalculator.calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.AVALANCHE),
    )

    val report = builder.build(
      snapshot = snapshot,
      request = ReportRequest(
        periodType = ReportPeriodType.CUSTOM,
        startEpochDay = nov10,
        endEpochDay = nov15,
        displayCurrencyCode = "INR",
        detailLevel = ReportDetailLevel.DETAILED,
      ),
      today = LocalDate.of(2026, 11, 15),
    )

    assertEquals("₹ 5,000.00", report.openingBalanceFormatted)
    assertEquals("₹ 10,000.00", report.totalIncomeFormatted)
    assertEquals("₹ 2,500.00", report.totalExpensesFormatted)
    assertEquals("₹ 7,500.00", report.netSavingsFormatted)
    assertEquals("₹ 12,500.00", report.closingBalanceFormatted)
    assertEquals(2, report.transactionRows.size)
  }

  @Test
  fun `encrypted backup aes 256 gcm round trip and tamper resistance`() {
    val crypto = BackupCryptoManager()
    val secretJson = """{"transactions":[{"amount":"1250.750","currency":"KWD"}]}"""
    val passphrase = "SkyPlusStrongPassphrase2026!".toCharArray()

    val encrypted = crypto.encryptBackup(secretJson, passphrase, recordCount = 1)
    assertFalse(encrypted.contains("1250.750"))

    val decrypted = crypto.decryptBackup(encrypted, "SkyPlusStrongPassphrase2026!".toCharArray())
    assertEquals(secretJson, decrypted)

    var failedWithWrongPass = false
    try {
      crypto.decryptBackup(encrypted, "WrongPassphrase999".toCharArray())
    } catch (_: AEADBadTagException) {
      failedWithWrongPass = true
    }
    assertTrue(failedWithWrongPass)

    val tampered = encrypted.replace("\"recordCount\":1", "\"recordCount\":99")
    var failedWithTamper = false
    try {
      crypto.decryptBackup(tampered, "SkyPlusStrongPassphrase2026!".toCharArray())
    } catch (_: AEADBadTagException) {
      failedWithTamper = true
    }
    assertTrue(failedWithTamper)
  }
}
