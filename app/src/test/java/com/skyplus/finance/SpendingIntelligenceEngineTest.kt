package com.skyplus.finance

import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.intelligence.CategorySpendTrend
import com.skyplus.finance.domain.intelligence.RecurringCadence
import com.skyplus.finance.domain.intelligence.SmartAlertType
import com.skyplus.finance.domain.intelligence.SpendingDayPreference
import com.skyplus.finance.domain.intelligence.SpendingIntelligenceEngine
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendingIntelligenceEngineTest {

  private val engine = SpendingIntelligenceEngine(BudgetAndSavingsCalculator())
  private val today = LocalDate.of(2026, 11, 15) // Sunday
  private val precisionMap = mapOf("INR" to 2, "KWD" to 3)
  private val categories = mapOf(
    1L to CategoryEntity(1, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
    2L to CategoryEntity(2, "utilities", "Utilities", "Utilities", CategoryType.EXPENSE, "bolt", 0xFF00BFA6),
    3L to CategoryEntity(3, "emi_and_loans", "EMI & Loans", "EMI", CategoryType.EXPENSE, "account_balance", 0xFF2878D0),
    15L to CategoryEntity(15, "savings", "Savings", "Savings", CategoryType.SAVINGS, "savings", 0xFF00BFA6),
  )

  @Test
  fun `computes last month vs this month category comparison excluding savings and future items`() {
    val oct10 = LocalDate.of(2026, 10, 10).toEpochDay()
    val nov05 = LocalDate.of(2026, 11, 5).toEpochDay()
    val nov20Future = LocalDate.of(2026, 11, 20).toEpochDay()

    val txs = listOf(
      TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("4000.00"), "INR", description = "Groceries", transactionDateEpochDay = oct10, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("6000.00"), "INR", description = "Groceries", transactionDateEpochDay = nov05, createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      // Future expense must NOT appear in realized current month comparison
      TransactionEntity(3, 1, 1, TransactionType.EXPENSE, BigDecimal("5000.00"), "INR", description = "Future dinner", transactionDateEpochDay = nov20Future, createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      // Savings allocation must NOT be treated as expense
      TransactionEntity(4, 1, 15, TransactionType.SAVINGS_ALLOCATION, BigDecimal("10000.00"), "INR", description = "SIP", transactionDateEpochDay = nov05, createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
    )

    val report = engine.analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(BigDecimal("6000.00"), report.currentMonthTotalSpent)
    assertEquals(BigDecimal("4000.00"), report.previousMonthTotalSpent)
    assertEquals(BigDecimal("2000.00"), report.monthOverMonthDelta)
    assertEquals(BigDecimal("50.00"), report.monthOverMonthPercent)

    val foodComp = report.categoryComparisons.first { it.categoryId == 1L }
    assertEquals(CategorySpendTrend.INCREASED, foodComp.trend)
    assertEquals(BigDecimal("2000.00"), foodComp.deltaAmount)
    assertEquals(BigDecimal("50.00"), foodComp.deltaPercent)
  }

  @Test
  fun `detects weekday vs weekend spending and recurring monthly expenses`() {
    val sat1 = LocalDate.of(2026, 11, 7) // Saturday
    val sun1 = LocalDate.of(2026, 11, 8) // Sunday
    val mon1 = LocalDate.of(2026, 11, 9) // Monday
    assertEquals(DayOfWeek.SATURDAY, sat1.dayOfWeek)
    assertEquals(DayOfWeek.SUNDAY, sun1.dayOfWeek)

    val txs = listOf(
      TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("2500.00"), "INR", description = "Weekend Dining", transactionDateEpochDay = sat1.toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("2500.00"), "INR", description = "Weekend Shopping", transactionDateEpochDay = sun1.toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 2, TransactionType.EXPENSE, BigDecimal("1000.00"), "INR", description = "Internet Fiber", transactionDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      TransactionEntity(4, 1, 2, TransactionType.EXPENSE, BigDecimal("1000.00"), "INR", description = "Internet Fiber", transactionDateEpochDay = LocalDate.of(2026, 10, 9).toEpochDay(), createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
      TransactionEntity(5, 1, 2, TransactionType.EXPENSE, BigDecimal("1000.00"), "INR", description = "Internet Fiber", transactionDateEpochDay = mon1.toEpochDay(), createdAtEpochMillis = 5, updatedAtEpochMillis = 5),
    )

    val report = engine.analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(SpendingDayPreference.WEEKEND_HEAVY, report.weekdayWeekendAnalysis.preference)
    assertEquals(BigDecimal("5000.00"), report.weekdayWeekendAnalysis.weekendTotal)
    assertEquals(BigDecimal("3000.00"), report.weekdayWeekendAnalysis.weekdayTotal)

    val internet = report.recurringExpenses.firstOrNull { it.title == "Internet Fiber" }
    assertTrue(internet != null)
    assertEquals(RecurringCadence.MONTHLY, internet!!.cadence)
    assertEquals(3, internet.occurrenceCount)
    assertEquals(BigDecimal("1000.00"), internet.averageAmount)
  }

  @Test
  fun `does not fire unusual spending alerts when historical baseline is insufficient`() {
    val txs = listOf(
      // Only 1 prior month + 2 current expenses: insufficient baseline!
      TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("500.00"), "INR", description = "Snack", transactionDateEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("5000.00"), "INR", description = "Big Dinner", transactionDateEpochDay = today.toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
    )

    val report = engine.analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertFalse(report.hasSufficientAnomalyBaseline)
    assertTrue(
      report.alerts.none {
        it.type == SmartAlertType.UNUSUAL_CATEGORY_SPIKE ||
          it.type == SmartAlertType.UNUSUAL_SINGLE_TRANSACTION
      },
    )
  }

  @Test
  fun `detects unusual category spike and outlier transaction when sufficient history exists`() {
    val txs = listOf(
      // 3 historical months averaging ~1000/month across 6 prior transactions
      TransactionEntity(1, 1, 1, TransactionType.EXPENSE, BigDecimal("500.00"), "INR", description = "Lunch", transactionDateEpochDay = LocalDate.of(2026, 8, 5).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 1, 1, TransactionType.EXPENSE, BigDecimal("500.00"), "INR", description = "Dinner", transactionDateEpochDay = LocalDate.of(2026, 8, 20).toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 1, 1, TransactionType.EXPENSE, BigDecimal("550.00"), "INR", description = "Lunch", transactionDateEpochDay = LocalDate.of(2026, 9, 5).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
      TransactionEntity(4, 1, 1, TransactionType.EXPENSE, BigDecimal("450.00"), "INR", description = "Dinner", transactionDateEpochDay = LocalDate.of(2026, 9, 20).toEpochDay(), createdAtEpochMillis = 4, updatedAtEpochMillis = 4),
      TransactionEntity(5, 1, 1, TransactionType.EXPENSE, BigDecimal("500.00"), "INR", description = "Lunch", transactionDateEpochDay = LocalDate.of(2026, 10, 5).toEpochDay(), createdAtEpochMillis = 5, updatedAtEpochMillis = 5),
      TransactionEntity(6, 1, 1, TransactionType.EXPENSE, BigDecimal("500.00"), "INR", description = "Dinner", transactionDateEpochDay = LocalDate.of(2026, 10, 20).toEpochDay(), createdAtEpochMillis = 6, updatedAtEpochMillis = 6),
      // Current month large outlier (3500 vs ~500 tx avg and ~1000 monthly avg)
      TransactionEntity(7, 1, 1, TransactionType.EXPENSE, BigDecimal("3500.00"), "INR", description = "Banquet", transactionDateEpochDay = today.minusDays(1).toEpochDay(), createdAtEpochMillis = 7, updatedAtEpochMillis = 7),
    )

    val report = engine.analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = emptyList(),
      emiPayments = emptyList(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertTrue(report.hasSufficientAnomalyBaseline)
    assertTrue(report.alerts.any { it.type == SmartAlertType.UNUSUAL_CATEGORY_SPIKE })
    assertTrue(report.alerts.any { it.type == SmartAlertType.UNUSUAL_SINGLE_TRANSACTION })
  }

  @Test
  fun `upcoming alerts exclude paid bills paid emis and deduplicate alerts including budgets 80 and 100`() {
    val dueIn3Days = today.plusDays(3).toEpochDay()
    val dueIn12Days = today.plusDays(12).toEpochDay()

    val unpaidDueBill = TransactionEntity(10, 1, 2, TransactionType.EXPENSE, BigDecimal("1800.00"), "INR", description = "Power Bill", transactionDateEpochDay = dueIn3Days, isUpcomingBill = true, isPaid = false, deduplicationKey = "bill-power", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
    val paidBill = TransactionEntity(11, 1, 2, TransactionType.EXPENSE, BigDecimal("900.00"), "INR", description = "Water Bill", transactionDateEpochDay = dueIn3Days, isUpcomingBill = true, isPaid = true, createdAtEpochMillis = 2, updatedAtEpochMillis = 2)
    val farBill = TransactionEntity(12, 1, 2, TransactionType.EXPENSE, BigDecimal("2500.00"), "INR", description = "Far Bill", transactionDateEpochDay = dueIn12Days, isUpcomingBill = true, isPaid = false, createdAtEpochMillis = 3, updatedAtEpochMillis = 3)
    val foodExpense = TransactionEntity(13, 1, 1, TransactionType.EXPENSE, BigDecimal("850.00"), "INR", description = "Lunch", transactionDateEpochDay = today.toEpochDay(), isPaid = true, createdAtEpochMillis = 4, updatedAtEpochMillis = 4)
    val utilityExpense = TransactionEntity(14, 1, 2, TransactionType.EXPENSE, BigDecimal("1200.00"), "INR", description = "Fiber", transactionDateEpochDay = today.toEpochDay(), isPaid = true, createdAtEpochMillis = 5, updatedAtEpochMillis = 5)

    val loanUnpaidDue = LoanEntity(1, "Bike Loan", "HDFC", BigDecimal("50000"), BigDecimal("30000"), BigDecimal("10"), BigDecimal("3200.00"), "INR", 18, dueIn3Days, 10, today.minusDays(60).toEpochDay(), LoanStatus.ACTIVE, 1, 1)
    val loanAlreadyPaidThisCycle = LoanEntity(2, "Phone EMI", "ICICI", BigDecimal("20000"), BigDecimal("10000"), BigDecimal("12"), BigDecimal("1800.00"), "INR", 18, dueIn3Days, 6, today.minusDays(60).toEpochDay(), LoanStatus.ACTIVE, 1, 1)
    val paidEmiRecord = EmiPaymentEntity(1, loanId = 2L, paymentDateEpochDay = today.minusDays(1).toEpochDay(), totalPaidAmount = BigDecimal("1800.00"), principalComponent = BigDecimal("1700.00"), interestComponent = BigDecimal("100.00"), remainingBalanceAfter = BigDecimal("10000.00"), currencyCode = "INR", createdAtEpochMillis = 1)

    val budgets = listOf(
      // Food: 850 / 1000 = 85% -> BUDGET_APPROACHING_80
      BudgetEntity(1, 1, "2026-11", BigDecimal("1000.00"), "INR", warningThresholdPercent = 80, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      // Utilities: 1200 / 1000 = 120% -> BUDGET_EXCEEDED_100
      BudgetEntity(2, 2, "2026-11", BigDecimal("1000.00"), "INR", warningThresholdPercent = 80, createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
    )

    val report = engine.analyze(
      transactions = listOf(unpaidDueBill, unpaidDueBill, paidBill, farBill, foodExpense, utilityExpense),
      budgets = budgets,
      categoriesById = categories,
      loans = listOf(loanUnpaidDue, loanUnpaidDue, loanAlreadyPaidThisCycle),
      emiPayments = listOf(paidEmiRecord),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    // Only 1 bill alert (unpaidDueBill), never paidBill or farBill or duplicate unpaidDueBill
    val billAlerts = report.alerts.filter { it.type == SmartAlertType.UPCOMING_BILL_DUE }
    assertEquals(1, billAlerts.size)
    assertTrue(billAlerts.first().title.contains("Power Bill"))

    // Only 1 EMI alert (Bike Loan), never already-paid Phone EMI or duplicate Bike Loan
    val emiAlerts = report.alerts.filter { it.type == SmartAlertType.UPCOMING_EMI_DUE }
    assertEquals(1, emiAlerts.size)
    assertTrue(emiAlerts.first().title.contains("Bike Loan"))

    // Budget 80% warning and Budget 100% exceeded alerts both present, no duplicates
    assertEquals(1, report.alerts.count { it.type == SmartAlertType.BUDGET_APPROACHING_80 })
    assertEquals(1, report.alerts.count { it.type == SmartAlertType.BUDGET_EXCEEDED_100 })
    assertEquals(report.alerts.size, report.alerts.map { it.id }.distinct().size)
  }
}
