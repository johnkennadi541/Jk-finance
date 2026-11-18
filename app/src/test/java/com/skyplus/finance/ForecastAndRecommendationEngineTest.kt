package com.skyplus.finance

import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.intelligence.BudgetRecommendationAction
import com.skyplus.finance.domain.intelligence.ForecastAndRecommendationEngine
import com.skyplus.finance.domain.intelligence.ForecastConfidence
import com.skyplus.finance.domain.intelligence.ForecastStatus
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastAndRecommendationEngineTest {

  private val engine = ForecastAndRecommendationEngine()
  private val today = LocalDate.of(2026, 11, 15)
  private val precisionMap = mapOf("INR" to 2, "KWD" to 3)
  private val categories = mapOf(
    1L to CategoryEntity(1, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
    2L to CategoryEntity(2, "utilities", "Utilities", "Utilities", CategoryType.EXPENSE, "bolt", 0xFF00BFA6),
    10L to CategoryEntity(10, "salary", "Salary", "Salary", CategoryType.INCOME, "payments", 0xFF00BFA6),
    15L to CategoryEntity(15, "savings", "Savings", "Savings", CategoryType.SAVINGS, "savings", 0xFF00BFA6),
  )

  @Test
  fun `returns insufficient data when transaction history is insufficient`() {
    val result = engine.analyze(
      transactions = emptyList(),
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = emptyList(),
      settings = null,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(ForecastStatus.INSUFFICIENT_DATA, result.cashflowForecast.status)
    assertNull(result.cashflowForecast.endOfMonthForecast)
    assertTrue(result.cashflowForecast.nextThreeMonths.isEmpty())
  }

  @Test
  fun `projects end of month and next 3 months without counting future income as received or double counting paid bills and emis`() {
    val txs = listOf(
      // 3 steady prior months: Aug, Sep, Oct
      TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("100000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 8, 1).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("30000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 8, 10).toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 10, TransactionType.INCOME, BigDecimal("100000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 9, 1).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      TransactionEntity(4, 1, 1, TransactionType.EXPENSE, BigDecimal("30000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay(), createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
      TransactionEntity(5, 1, 10, TransactionType.INCOME, BigDecimal("100000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 1).toEpochDay(), createdAtEpochMillis = 5, updatedAtEpochMillis = 5),
      TransactionEntity(6, 1, 1, TransactionType.EXPENSE, BigDecimal("30000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(), createdAtEpochMillis = 6, updatedAtEpochMillis = 6),

      // Current month (Nov): realized income 100k, realized expense 15k, future expected income 25k on Nov 25 (NOT yet received!)
      TransactionEntity(7, 1, 10, TransactionType.INCOME, BigDecimal("100000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(), createdAtEpochMillis = 7, updatedAtEpochMillis = 7),
      TransactionEntity(8, 1, 1, TransactionType.EXPENSE, BigDecimal("15000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay(), createdAtEpochMillis = 8, updatedAtEpochMillis = 8),
      TransactionEntity(9, 1, 10, TransactionType.INCOME, BigDecimal("25000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 11, 25).toEpochDay(), createdAtEpochMillis = 9, updatedAtEpochMillis = 9),

      // Paid bill (Nov 20) & Unpaid bill (Nov 22)
      TransactionEntity(10, 1, 2, TransactionType.EXPENSE, BigDecimal("2000"), "INR", description = "Internet", isUpcomingBill = true, isPaid = true, transactionDateEpochDay = LocalDate.of(2026, 11, 10).toEpochDay(), createdAtEpochMillis = 10, updatedAtEpochMillis = 10),
      TransactionEntity(11, 1, 2, TransactionType.EXPENSE, BigDecimal("3000"), "INR", description = "Power Bill", isUpcomingBill = true, isPaid = false, transactionDateEpochDay = LocalDate.of(2026, 11, 22).toEpochDay(), createdAtEpochMillis = 11, updatedAtEpochMillis = 11),
    )

    // Two loans: Loan 1 already paid EMI in Nov; Loan 2 unpaid EMI due Nov 20 with 2 months left (drops off in month 3!)
    val loans = listOf(
      LoanEntity(1, "PaidCycleLoan", "BankA", BigDecimal("50000"), BigDecimal("20000"), BigDecimal("10"), BigDecimal("5000"), "INR", 10, LocalDate.of(2026, 11, 20).toEpochDay(), 4, LocalDate.of(2026, 6, 1).toEpochDay(), LoanStatus.ACTIVE, 1, 1),
      LoanEntity(2, "ShortLoan", "BankB", BigDecimal("20000"), BigDecimal("10000"), BigDecimal("10"), BigDecimal("4000"), "INR", 20, LocalDate.of(2026, 11, 20).toEpochDay(), 2, LocalDate.of(2026, 6, 1).toEpochDay(), LoanStatus.ACTIVE, 1, 1),
    )
    val emiPayments = listOf(
      EmiPaymentEntity(1, loanId = 1L, paymentDateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay(), totalPaidAmount = BigDecimal("5000"), principalComponent = BigDecimal("4800"), interestComponent = BigDecimal("200"), remainingBalanceAfter = BigDecimal("20000"), currencyCode = "INR", createdAtEpochMillis = 1),
    )

    val result = engine.analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = loans,
      emiPayments = emiPayments,
      savingsGoals = emptyList(),
      settings = null,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    val forecast = result.cashflowForecast
    assertEquals(ForecastStatus.READY, forecast.status)
    assertEquals(ForecastConfidence.HIGH, forecast.confidence)
    assertFalse(forecast.hasIrregularOrSeasonalIncome)
    assertTrue(forecast.estimateDisclaimer.contains("not guaranteed", ignoreCase = true))

    val eom = forecast.endOfMonthForecast!!
    // Future 25,000 income must NOT appear in realizedIncomeToDate!
    assertEquals(BigDecimal("100000.00"), eom.realizedIncomeToDate)
    assertEquals(BigDecimal("25000.00"), eom.projectedRemainingIncome)

    // Scheduled unpaid commitments remaining = 3,000 (Power Bill) + 4,000 (ShortLoan EMI) = 7,000 (Paid Internet 2k and PaidCycleLoan 5k excluded!)
    assertEquals(BigDecimal("7000.00"), eom.scheduledUnpaidCommitmentsRemaining)

    // Next 3 months forecast
    assertEquals(3, forecast.nextThreeMonths.size)
    // Month 1 & 2 include 5000 + 4000 = 9000 EMIs; Month 3 drops ShortLoan (tenure=2) -> only 5000 EMI
    assertEquals(BigDecimal("9000.00"), forecast.nextThreeMonths[0].includesScheduledEmiAmount)
    assertEquals(BigDecimal("9000.00"), forecast.nextThreeMonths[1].includesScheduledEmiAmount)
    assertEquals(BigDecimal("5000.00"), forecast.nextThreeMonths[2].includesScheduledEmiAmount)
  }

  @Test
  fun `flags irregular seasonal income and lowers confidence`() {
    val txs = listOf(
      TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("20000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 8, 5).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 10, TransactionType.INCOME, BigDecimal("120000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 9, 5).toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 10, TransactionType.INCOME, BigDecimal("30000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 5).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      TransactionEntity(4, 1, 1, TransactionType.EXPENSE, BigDecimal("25000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(), createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
    )

    val result = engine.analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = emptyList(),
      settings = null,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(ForecastStatus.READY, result.cashflowForecast.status)
    assertTrue(result.cashflowForecast.hasIrregularOrSeasonalIncome)
    assertEquals(ForecastConfidence.MEDIUM, result.cashflowForecast.confidence)
    assertTrue(result.cashflowForecast.incomePatternNote.contains("Irregular", ignoreCase = true))
  }

  @Test
  fun `generates smart budget recommendations from 3 month actuals without mutating existing user budgets`() {
    val txs = listOf(
      // Food avg = 10,000/month across Aug-Oct (current limit 6,000 -> INCREASE_LIMIT to 10,500)
      TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("10000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 8, 10).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("10000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 1, TransactionType.EXPENSE, BigDecimal("10000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      // Utilities avg = 2,000/month across Aug-Oct (no budget set -> CREATE_BUDGET 2,100)
      TransactionEntity(4, 1, 2, TransactionType.EXPENSE, BigDecimal("2000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 9, 12).toEpochDay(), createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
      TransactionEntity(5, 1, 2, TransactionType.EXPENSE, BigDecimal("2000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 12).toEpochDay(), createdAtEpochMillis = 5, updatedAtEpochMillis = 5),
    )
    val userBudgets = listOf(
      BudgetEntity(1, 1, "2026-11", BigDecimal("6000.00"), "INR", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val result = engine.analyze(
      transactions = txs,
      budgets = userBudgets,
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = emptyList(),
      settings = null,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    // Original user budget object untouched
    assertEquals(BigDecimal("6000.00"), userBudgets.first().limitAmount)

    val foodRec = result.budgetRecommendations.first { it.categoryId == 1L }
    assertEquals(BudgetRecommendationAction.INCREASE_LIMIT, foodRec.action)
    assertEquals(BigDecimal("10000.00"), foodRec.threeMonthAverageSpend)
    assertEquals(BigDecimal("10500.00"), foodRec.suggestedBudgetLimit)
    assertTrue(foodRec.requiresUserConfirmation)

    val utilRec = result.budgetRecommendations.first { it.categoryId == 2L }
    assertEquals(BudgetRecommendationAction.CREATE_BUDGET, utilRec.action)
    assertNull(utilRec.currentBudgetLimit)
    assertEquals(BigDecimal("2100.00"), utilRec.suggestedBudgetLimit)
    assertTrue(utilRec.requiresUserConfirmation)
  }

  @Test
  fun `computes 3 and 6 month emergency fund targets and avoids double counting already allocated savings`() {
    val txs = listOf(
      TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("90000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 1).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("30000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 10, 5).toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 10, TransactionType.INCOME, BigDecimal("90000"), "INR", transactionDateEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      // Already allocated 5,000 to emergency fund (id=1) this month
      TransactionEntity(4, 1, 15, TransactionType.SAVINGS_ALLOCATION, BigDecimal("5000"), "INR", linkedGoalId = 1L, transactionDateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay(), createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
    )
    val goals = listOf(
      // currentSavedAmount already reflects the 5,000 deposit (30,000 total saved)
      SavingsGoalEntity(1, "Emergency Cushion", SavingsGoalType.EMERGENCY_FUND, BigDecimal("180000"), BigDecimal("30000"), "INR", isEmergencyFund = true, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      SavingsGoalEntity(2, "Laptop", SavingsGoalType.PERSONAL_SAVINGS, BigDecimal("60000"), BigDecimal("20000"), "INR", targetDateEpochDay = LocalDate.of(2027, 3, 15).toEpochDay(), isEmergencyFund = false, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )
    val settings = AppSettingsEntity(
      id = 1,
      primaryCurrencyCode = "INR",
      monthlyEssentialExpenses = BigDecimal("30000.00"),
      updatedAtEpochMillis = 1L,
    )

    val result = engine.analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = goals,
      settings = settings,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    val ef = result.emergencyFundRecommendation
    assertFalse(ef.isBaselineEstimatedFromSpending)
    assertEquals(BigDecimal("90000.00"), ef.threeMonthTarget)
    assertEquals(BigDecimal("180000.00"), ef.sixMonthTarget)
    // Must stay 30,000.00 (NOT 35,000.00 — never double counts linked SAVINGS_ALLOCATION!)
    assertEquals(BigDecimal("30000.00"), ef.currentEmergencySaved)
    assertEquals(BigDecimal("60000.00"), ef.remainingForThreeMonthTarget)
    assertEquals(BigDecimal("150000.00"), ef.remainingForSixMonthTarget)
    assertEquals(BigDecimal("5000.00"), ef.alreadyAllocatedThisMonth)
    assertTrue(ef.recommendedMonthlySavings > BigDecimal.ZERO)
    assertEquals(
      ef.recommendedMonthlySavings.subtract(BigDecimal("5000.00")).coerceAtLeast(BigDecimal.ZERO),
      ef.remainingRecommendedThisMonth,
    )
    assertNotNull(ef.estimatedMonthsToThreeMonthTarget)

    val laptopRec = result.goalRecommendations.first { it.goalId == 2L }
    assertEquals(BigDecimal("40000.00"), laptopRec.remainingTargetAmount)
    assertEquals(BigDecimal("10000.00"), laptopRec.suggestedMonthlyContribution) // 40,000 / 4 months
  }
}
