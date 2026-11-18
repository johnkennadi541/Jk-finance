package com.skyplus.finance

import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.intelligence.FinancialHealthScoreEngine
import com.skyplus.finance.domain.intelligence.FinancialHealthStatus
import com.skyplus.finance.domain.intelligence.HealthPillarType
import com.skyplus.finance.domain.intelligence.HealthScoreBand
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialHealthScoreEngineTest {

  private val engine = FinancialHealthScoreEngine(BudgetAndSavingsCalculator())
  private val today = LocalDate.of(2026, 11, 15)
  private val precisionMap = mapOf("INR" to 2, "KWD" to 3, "JPY" to 0)
  private val categories = mapOf(
    1L to CategoryEntity(1, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
    3L to CategoryEntity(3, "emi_and_loans", "EMI & Loans", "EMI", CategoryType.EXPENSE, "account_balance", 0xFF2878D0),
    10L to CategoryEntity(10, "salary", "Salary", "Salary", CategoryType.INCOME, "payments", 0xFF00BFA6),
  )

  private fun settings(essentials: String = "30000.00"): AppSettingsEntity = AppSettingsEntity(
    id = 1,
    primaryCurrencyCode = "INR",
    monthlyEssentialExpenses = BigDecimal(essentials),
    updatedAtEpochMillis = 1L,
  )

  @Test
  fun `returns insufficient data when transaction history is empty or below threshold`() {
    val emptyReport = engine.evaluate(
      transactions = emptyList(),
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      savingsGoals = emptyList(),
      settings = settings(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    assertEquals(FinancialHealthStatus.INSUFFICIENT_DATA, emptyReport.status)
    assertNull(emptyReport.overallScore)
    assertNull(emptyReport.band)
    assertEquals("Insufficient Data", emptyReport.summaryHeadline)
    assertTrue(emptyReport.priorityImprovements.isNotEmpty())

    // 2 transactions only (< 3 minimum) must still report INSUFFICIENT_DATA
    val twoTxReport = engine.evaluate(
      transactions = listOf(
        TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("50000"), "INR", transactionDateEpochDay = today.toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
        TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("5000"), "INR", transactionDateEpochDay = today.toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      ),
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      savingsGoals = emptyList(),
      settings = settings(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    assertEquals(FinancialHealthStatus.INSUFFICIENT_DATA, twoTxReport.status)
    assertNull(twoTxReport.overallScore)
  }

  @Test
  fun `future income and unpaid bills are never counted as realized cashflow`() {
    val tomorrow = today.plusDays(1).toEpochDay()
    val report = engine.evaluate(
      transactions = listOf(
        // Realized expenses
        TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("2000"), "INR", isPaid = true, transactionDateEpochDay = today.minusDays(3).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
        TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("3000"), "INR", isPaid = true, transactionDateEpochDay = today.minusDays(2).toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
        TransactionEntity(3, 1, 1, TransactionType.EXPENSE, BigDecimal("1000"), "INR", isPaid = true, transactionDateEpochDay = today.minusDays(1).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
        // Future income (must NOT be counted)
        TransactionEntity(4, 1, 10, TransactionType.INCOME, BigDecimal("100000"), "INR", isPaid = true, transactionDateEpochDay = tomorrow, createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
        // Unpaid upcoming income/expense (must NOT be counted)
        TransactionEntity(5, 1, 10, TransactionType.INCOME, BigDecimal("50000"), "INR", isPaid = false, isUpcomingBill = true, transactionDateEpochDay = today.toEpochDay(), createdAtEpochMillis = 5, updatedAtEpochMillis = 5),
      ),
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      savingsGoals = emptyList(),
      settings = settings(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(FinancialHealthStatus.COMPUTED, report.status)
    assertEquals(BigDecimal("0.00"), report.metrics.realizedIncome)
    assertEquals(BigDecimal("6000.00"), report.metrics.realizedExpenses)
    val cashflow = report.pillars.first { it.pillar == HealthPillarType.CASHFLOW_SAVINGS }
    assertEquals(0, cashflow.score)
  }

  @Test
  fun `debt free user receives full debt pillar score without false penalty`() {
    val d = today.toEpochDay()
    val txs = listOf(
      TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("100000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("20000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 1, TransactionType.EXPENSE, BigDecimal("15000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
    )
    val goals = listOf(
      SavingsGoalEntity(1, "Emergency", SavingsGoalType.EMERGENCY_FUND, BigDecimal("200000"), BigDecimal("180000"), "INR", isEmergencyFund = true, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )
    val budgets = listOf(
      BudgetEntity(1, 1, "2026-11", BigDecimal("45000"), "INR", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val report = engine.evaluate(
      transactions = txs,
      budgets = budgets,
      categoriesById = categories,
      loans = emptyList(),
      savingsGoals = goals,
      settings = settings("30000"),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(FinancialHealthStatus.COMPUTED, report.status)
    assertEquals(100, report.overallScore)
    assertEquals(HealthScoreBand.EXCELLENT, report.band)

    val debtPillar = report.pillars.first { it.pillar == HealthPillarType.DEBT_BURDEN }
    assertEquals(25, debtPillar.score)
    assertTrue(debtPillar.reason.contains("No active loan", ignoreCase = true))
  }

  @Test
  fun `duplicate transactions and duplicate loans are never double counted`() {
    val d = today.toEpochDay()
    val txs = listOf(
      TransactionEntity(10, 1, 10, TransactionType.INCOME, BigDecimal("80000"), "INR", deduplicationKey = "sal-nov", transactionDateEpochDay = d, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      // Duplicate by same id AND duplicate by same deduplicationKey
      TransactionEntity(10, 1, 10, TransactionType.INCOME, BigDecimal("80000"), "INR", deduplicationKey = "sal-nov", transactionDateEpochDay = d, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(0, 1, 10, TransactionType.INCOME, BigDecimal("80000"), "INR", deduplicationKey = "sal-nov", transactionDateEpochDay = d, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(11, 1, 1, TransactionType.EXPENSE, BigDecimal("12000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(12, 1, 3, TransactionType.EXPENSE, BigDecimal("8000"), "INR", linkedLoanId = 5L, transactionDateEpochDay = d, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
    )
    val loans = listOf(
      LoanEntity(5L, "Car Loan", "HDFC", BigDecimal("300000"), BigDecimal("200000"), BigDecimal("10"), BigDecimal("8000"), "INR", 5, d, 24, d - 100, LoanStatus.ACTIVE, 1, 1),
      // Duplicate loan entry with same id
      LoanEntity(5L, "Car Loan", "HDFC", BigDecimal("300000"), BigDecimal("200000"), BigDecimal("10"), BigDecimal("8000"), "INR", 5, d, 24, d - 100, LoanStatus.ACTIVE, 1, 1),
    )

    val report = engine.evaluate(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = loans,
      savingsGoals = emptyList(),
      settings = settings("20000"),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(FinancialHealthStatus.COMPUTED, report.status)
    assertEquals(BigDecimal("80000.00"), report.metrics.realizedIncome)
    assertEquals(BigDecimal("20000.00"), report.metrics.realizedExpenses)
    // Monthly EMI must be 8000.00, NOT 16000.00 (no double counting across duplicate loan or linked transaction)
    assertEquals(BigDecimal("8000.00"), report.metrics.monthlyEmiCommitment)
    assertEquals(BigDecimal("10.00"), report.metrics.emiToIncomePercent)
  }

  @Test
  fun `handles zero income, exceeded budgets, heavy debt, and preserves score within 0 to 100`() {
    val d = today.toEpochDay()
    val txs = listOf(
      TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("15000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("10000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 1, TransactionType.EXPENSE, BigDecimal("5000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
    )
    val loans = listOf(
      LoanEntity(1, "Personal Loan", "Bank", BigDecimal("200000"), BigDecimal("180000"), BigDecimal("16"), BigDecimal("12000"), "INR", 10, d, 18, d - 60, LoanStatus.ACTIVE, 1, 1),
    )
    val budgets = listOf(
      BudgetEntity(1, 1, "2026-11", BigDecimal("10000"), "INR", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val report = engine.evaluate(
      transactions = txs,
      budgets = budgets,
      categoriesById = categories,
      loans = loans,
      savingsGoals = emptyList(),
      settings = settings("25000"),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(FinancialHealthStatus.COMPUTED, report.status)
    assertNotNull(report.overallScore)
    assertTrue(report.overallScore!! in 0..100)
    assertEquals(0, report.overallScore)
    assertEquals(HealthScoreBand.CRITICAL, report.band)
    assertTrue(report.pillars.all { it.reason.isNotBlank() && it.improvementTip.isNotBlank() })
  }

  @Test
  fun `supports three decimal currency KWD and uses trailing 30 days on early month before salary`() {
    val earlyToday = LocalDate.of(2026, 11, 2)
    val octSalaryDay = LocalDate.of(2026, 10, 25).toEpochDay()
    val novExpenseDay1 = LocalDate.of(2026, 11, 1).toEpochDay()
    val novExpenseDay2 = LocalDate.of(2026, 11, 2).toEpochDay()

    val txs = listOf(
      TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("1000.000"), "KWD", transactionDateEpochDay = octSalaryDay, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("150.250"), "KWD", transactionDateEpochDay = novExpenseDay1, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 1, TransactionType.EXPENSE, BigDecimal("49.750"), "KWD", transactionDateEpochDay = novExpenseDay2, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
    )
    val kwdSettings = AppSettingsEntity(
      id = 1,
      primaryCurrencyCode = "KWD",
      monthlyEssentialExpenses = BigDecimal("400.000"),
      updatedAtEpochMillis = 1L,
    )
    val goals = listOf(
      SavingsGoalEntity(1, "KWD Buffer", SavingsGoalType.EMERGENCY_FUND, BigDecimal("2400.000"), BigDecimal("1200.000"), "KWD", isEmergencyFund = true, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val report = engine.evaluate(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      savingsGoals = goals,
      settings = kwdSettings,
      primaryCurrencyCode = "KWD",
      currencyPrecisionMap = precisionMap,
      today = earlyToday,
    )

    assertEquals(FinancialHealthStatus.COMPUTED, report.status)
    assertEquals("Trailing 30 Days", report.metrics.evaluationWindowLabel)
    assertEquals(BigDecimal("1000.000"), report.metrics.realizedIncome)
    assertEquals(BigDecimal("200.000"), report.metrics.realizedExpenses)
    assertEquals(BigDecimal("80.00"), report.metrics.savingsRatePercent)
    assertEquals(BigDecimal("3.0"), report.metrics.emergencyMonthsCovered)
    assertTrue(report.overallScore!! in 70..100)
  }

  @Test
  fun `savings allocations are never treated as expenses and emi tiers scale accurately`() {
    val d = today.toEpochDay()
    val txs = listOf(
      TransactionEntity(1, 1, 10, TransactionType.INCOME, BigDecimal("100000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("50000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      // Savings allocation must NOT inflate expenses
      TransactionEntity(3, 1, 15, TransactionType.SAVINGS_ALLOCATION, BigDecimal("25000"), "INR", transactionDateEpochDay = d, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
    )
    // 25% EMI-to-income ratio (25,000 / 100,000) -> 18..25 range (~20 pts)
    val loans = listOf(
      LoanEntity(1, "Home Loan", "SBI", BigDecimal("1000000"), BigDecimal("800000"), BigDecimal("8.5"), BigDecimal("25000"), "INR", 5, d, 48, d - 365, LoanStatus.ACTIVE, 1, 1),
    )
    val budgets = listOf(
      BudgetEntity(1, 1, "2026-11", BigDecimal("60000"), "INR", warningThresholdPercent = 80, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val report = engine.evaluate(
      transactions = txs,
      budgets = budgets,
      categoriesById = categories,
      loans = loans,
      savingsGoals = emptyList(),
      settings = settings("40000"),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(FinancialHealthStatus.COMPUTED, report.status)
    assertEquals(BigDecimal("50000.00"), report.metrics.realizedExpenses)
    assertEquals(BigDecimal("50.00"), report.metrics.savingsRatePercent)
    assertEquals(BigDecimal("25.00"), report.metrics.emiToIncomePercent)

    val cashflow = report.pillars.first { it.pillar == HealthPillarType.CASHFLOW_SAVINGS }
    val debt = report.pillars.first { it.pillar == HealthPillarType.DEBT_BURDEN }
    val budget = report.pillars.first { it.pillar == HealthPillarType.BUDGET_DISCIPLINE }
    assertEquals(30, cashflow.score)
    assertTrue(debt.score in 19..22)
    // 50,000 / 60,000 = 83.33% >= 80% warning -> approaching threshold -> 0.70 * 20 = 14 pts
    assertEquals(14, budget.score)
  }

  @Test
  fun `evaluates all 8 holistic dimensions score history and change explanation`() {
    val txs = buildList {
      for (m in 0L..2L) {
        val salDay = today.minusMonths(m).withDayOfMonth(5).toEpochDay()
        val expDay = today.minusMonths(m).withDayOfMonth(12).toEpochDay()
        add(TransactionEntity(100L + m, 1, 10, TransactionType.INCOME, BigDecimal("90000"), "INR", transactionDateEpochDay = salDay, createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
        val expAmt = if (m == 0L) BigDecimal("25000") else BigDecimal("35000")
        add(TransactionEntity(200L + m, 1, 1, TransactionType.EXPENSE, expAmt, "INR", transactionDateEpochDay = expDay, createdAtEpochMillis = 2, updatedAtEpochMillis = 2))
      }
    }
    val loans = listOf(
      LoanEntity(1, "Credit Card Loan", "HDFC", BigDecimal("100000"), BigDecimal("50000"), BigDecimal("18.0"), BigDecimal("5000"), "INR", 10, today.plusDays(5).toEpochDay(), 12, today.minusMonths(4).toEpochDay(), LoanStatus.ACTIVE, 1, 1),
    )
    val goals = listOf(
      SavingsGoalEntity(1, "Emergency", SavingsGoalType.EMERGENCY_FUND, BigDecimal("180000"), BigDecimal("120000"), "INR", isEmergencyFund = true, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )
    val budgets = listOf(
      BudgetEntity(1, 1, "2026-11", BigDecimal("35000"), "INR", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val report = engine.evaluate(
      transactions = txs,
      budgets = budgets,
      categoriesById = categories,
      loans = loans,
      savingsGoals = goals,
      settings = settings("30000"),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(FinancialHealthStatus.COMPUTED, report.status)
    assertEquals(8, report.dimensions.size)
    assertTrue(report.dimensions.all { it.score in 0..100 && it.explanation.isNotBlank() && it.metricSummary.isNotBlank() })
    assertTrue(report.scoreHistory.size >= 2)
    assertNotNull(report.previousScore)
    assertNotNull(report.scoreChangeDelta)
    assertTrue(report.scoreChangeExplanation.isNotBlank())
    assertTrue(report.positiveContributors.isNotEmpty())
    assertTrue(report.practicalActions.any { it.actionTitle.contains("Accelerate", ignoreCase = true) })
  }

  @Test
  fun `income stability does not penalize modest regular income amounts`() {
    fun buildConsistentIncomeTxs(monthlyAmount: String): List<TransactionEntity> = buildList {
      for (m in 0L..2L) {
        add(
          TransactionEntity(
            id = 10L + m,
            accountId = 1,
            categoryId = 10,
            type = TransactionType.INCOME,
            amount = BigDecimal(monthlyAmount),
            currencyCode = "INR",
            transactionDateEpochDay = today.minusMonths(m).withDayOfMonth(5).toEpochDay(),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
          ),
        )
        add(
          TransactionEntity(
            id = 20L + m,
            accountId = 1,
            categoryId = 1,
            type = TransactionType.EXPENSE,
            amount = BigDecimal(monthlyAmount).multiply(BigDecimal("0.60")),
            currencyCode = "INR",
            transactionDateEpochDay = today.minusMonths(m).withDayOfMonth(10).toEpochDay(),
            createdAtEpochMillis = 2,
            updatedAtEpochMillis = 2,
          ),
        )
      }
    }

    val modestReport = engine.evaluate(
      transactions = buildConsistentIncomeTxs("15000.00"),
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      savingsGoals = emptyList(),
      settings = settings("9000.00"),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    val highReport = engine.evaluate(
      transactions = buildConsistentIncomeTxs("250000.00"),
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      savingsGoals = emptyList(),
      settings = settings("150000.00"),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    val modestStability = modestReport.dimensions.first { it.title == "Income Stability" }.score
    val highStability = highReport.dimensions.first { it.title == "Income Stability" }.score
    assertEquals(100, modestStability)
    assertEquals(highStability, modestStability)
    assertEquals(modestReport.overallScore, highReport.overallScore)
  }
}
