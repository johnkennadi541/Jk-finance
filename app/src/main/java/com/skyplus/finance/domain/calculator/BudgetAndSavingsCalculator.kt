package com.skyplus.finance.domain.calculator

import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.model.BudgetStatusItem
import com.skyplus.finance.domain.model.CurrencyBreakdownSummary
import com.skyplus.finance.domain.model.EmergencyFundSummary
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

class BudgetAndSavingsCalculator @Inject constructor() {

  fun computeBudgetStatuses(
    budgets: List<BudgetEntity>,
    categories: Map<Long, CategoryEntity>,
    transactions: List<TransactionEntity>,
    currencyPrecisionMap: Map<String, Int>,
  ): List<BudgetStatusItem> {
    return budgets.map { budget ->
      val ym = runCatching { YearMonth.parse(budget.yearMonth) }.getOrNull()
      val startDay = ym?.atDay(1)?.toEpochDay() ?: Long.MIN_VALUE
      val endDay = ym?.atEndOfMonth()?.toEpochDay() ?: Long.MAX_VALUE

      val spent = transactions
        .asSequence()
        .filter { tx ->
          tx.type == TransactionType.EXPENSE &&
            tx.isPaid &&
            tx.categoryId == budget.categoryId &&
            tx.currencyCode.equals(budget.currencyCode, ignoreCase = true) &&
            tx.transactionDateEpochDay in startDay..endDay
        }
        .fold(BigDecimal.ZERO) { acc, tx -> acc.add(tx.amount) }

      val precision = currencyPrecisionMap[budget.currencyCode] ?: 2
      val effectiveLimit = budget.limitAmount.add(
        if (budget.rolloverEnabled) budget.rolloverAmount else BigDecimal.ZERO,
      )
      val remaining = effectiveLimit.subtract(spent)
      val utilization = DecimalUtils.percentage(spent, effectiveLimit)
      val status = when {
        effectiveLimit <= BigDecimal.ZERO && spent > BigDecimal.ZERO -> BudgetHealthStatus.EXCEEDED
        spent > effectiveLimit -> BudgetHealthStatus.EXCEEDED
        utilization >= BigDecimal(budget.warningThresholdPercent) -> BudgetHealthStatus.APPROACHING_LIMIT
        else -> BudgetHealthStatus.NORMAL
      }

      val category = categories[budget.categoryId]
      BudgetStatusItem(
        budgetId = budget.id,
        categoryId = budget.categoryId,
        categoryNameEn = category?.displayNameEn ?: "Category",
        categoryNameTa = category?.displayNameTa ?: "வகை",
        iconKey = category?.iconKey ?: "category",
        colorHex = category?.colorHex ?: 0xFF10B981,
        yearMonth = budget.yearMonth,
        currencyCode = budget.currencyCode,
        baseLimit = DecimalUtils.scaleToCurrency(budget.limitAmount, precision),
        rolloverAmount = DecimalUtils.scaleToCurrency(budget.rolloverAmount, precision),
        rolloverEnabled = budget.rolloverEnabled,
        effectiveLimit = DecimalUtils.scaleToCurrency(effectiveLimit, precision),
        actualSpent = DecimalUtils.scaleToCurrency(spent, precision),
        remainingAmount = DecimalUtils.scaleToCurrency(remaining, precision),
        utilizationPercent = utilization,
        status = status,
      )
    }
  }

  fun computeEmergencyFund(
    goals: List<SavingsGoalEntity>,
    monthlyEssentialExpenses: BigDecimal,
    primaryCurrencyCode: String,
    primaryPrecision: Int,
  ): EmergencyFundSummary {
    val emergencyGoals = goals.filter {
      it.isEmergencyFund && it.currencyCode.equals(primaryCurrencyCode, ignoreCase = true)
    }
    val currentSaved = emergencyGoals.fold(BigDecimal.ZERO) { acc, g -> acc.add(g.currentSavedAmount) }
    val targetSaved = emergencyGoals.fold(BigDecimal.ZERO) { acc, g -> acc.add(g.targetAmount) }
    val monthsCovered = if (monthlyEssentialExpenses > BigDecimal.ZERO) {
      currentSaved.divide(monthlyEssentialExpenses, 1, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
    }
    val progress = DecimalUtils.percentage(currentSaved, targetSaved)

    return EmergencyFundSummary(
      currentEmergencySavings = DecimalUtils.scaleToCurrency(currentSaved, primaryPrecision),
      targetEmergencySavings = DecimalUtils.scaleToCurrency(targetSaved, primaryPrecision),
      monthlyEssentialExpenses = DecimalUtils.scaleToCurrency(monthlyEssentialExpenses, primaryPrecision),
      monthsCovered = monthsCovered,
      progressPercent = progress,
      currencyCode = primaryCurrencyCode,
    )
  }

  /**
   * Computes currency-isolated totals so multi-currency records are never silently summed
   * as if they shared the same unit.
   */
  fun computeCurrencyBreakdowns(
    transactions: List<TransactionEntity>,
    yearMonth: YearMonth? = null,
    currencyPrecisionMap: Map<String, Int> = emptyMap(),
  ): List<CurrencyBreakdownSummary> {
    val startDay = yearMonth?.atDay(1)?.toEpochDay() ?: Long.MIN_VALUE
    val endDay = yearMonth?.atEndOfMonth()?.toEpochDay() ?: Long.MAX_VALUE

    return transactions
      .filter { it.isPaid && it.transactionDateEpochDay in startDay..endDay }
      .groupBy { it.currencyCode.uppercase() }
      .map { (code, list) ->
        val precision = currencyPrecisionMap[code] ?: 2
        val income = list.filter { it.type == TransactionType.INCOME }
          .fold(BigDecimal.ZERO) { acc, t -> acc.add(t.amount) }
        val expense = list.filter { it.type == TransactionType.EXPENSE }
          .fold(BigDecimal.ZERO) { acc, t -> acc.add(t.amount) }
        val savings = list.filter { it.type == TransactionType.SAVINGS_ALLOCATION }
          .fold(BigDecimal.ZERO) { acc, t -> acc.add(t.amount) }
        val net = income.subtract(expense).subtract(savings)

        CurrencyBreakdownSummary(
          currencyCode = code,
          incomeTotal = DecimalUtils.scaleToCurrency(income, precision),
          expenseTotal = DecimalUtils.scaleToCurrency(expense, precision),
          savingsTotal = DecimalUtils.scaleToCurrency(savings, precision),
          netBalance = DecimalUtils.scaleToCurrency(net, precision),
        )
      }
      .sortedBy { it.currencyCode }
  }
}
