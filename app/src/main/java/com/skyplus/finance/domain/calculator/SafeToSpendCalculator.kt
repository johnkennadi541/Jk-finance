package com.skyplus.finance.domain.calculator

import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.model.SafeToSpendResult
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

class SafeToSpendCalculator @Inject constructor() {

  /**
   * Safe-to-Spend Per Day =
   * (Available Balance + Confirmed Remaining Income - Unpaid Upcoming Bills
   *  - Planned EMI Payments - Savings Allocation - Reserved Amount) / Remaining Days
   *
   * Rules enforced:
   * - Includes today in remaining days (>= 1).
   * - Handles negative balances accurately without hiding deficits.
   * - Flags insufficient-funds states when spendable pool <= 0.
   */
  fun calculate(input: SafeToSpendInput): SafeToSpendResult {
    val safeDays = input.remainingDaysInMonth.coerceAtLeast(1)
    val spendablePool = input.availableBalance
      .add(input.confirmedRemainingIncome)
      .subtract(input.unpaidUpcomingBills)
      .subtract(input.plannedEmiPayments)
      .subtract(input.savingsAllocation)
      .subtract(input.reservedAmount)

    val dailyLimit = spendablePool.divide(
      BigDecimal(safeDays),
      input.currencyDecimalPrecision.coerceIn(0, 3),
      RoundingMode.HALF_UP,
    )

    val isInsufficient = spendablePool.compareTo(BigDecimal.ZERO) <= 0
    val tightThreshold = input.availableBalance.multiply(BigDecimal("0.10"), DecimalUtils.FINANCIAL_MATH_CONTEXT)
    val isTight = !isInsufficient &&
      input.availableBalance > BigDecimal.ZERO &&
      spendablePool < tightThreshold

    return SafeToSpendResult(
      dailyLimit = dailyLimit,
      spendablePool = DecimalUtils.scaleToCurrency(spendablePool, input.currencyDecimalPrecision),
      availableBalance = DecimalUtils.scaleToCurrency(input.availableBalance, input.currencyDecimalPrecision),
      confirmedRemainingIncome = DecimalUtils.scaleToCurrency(input.confirmedRemainingIncome, input.currencyDecimalPrecision),
      unpaidUpcomingBills = DecimalUtils.scaleToCurrency(input.unpaidUpcomingBills, input.currencyDecimalPrecision),
      plannedEmiPayments = DecimalUtils.scaleToCurrency(input.plannedEmiPayments, input.currencyDecimalPrecision),
      savingsAllocation = DecimalUtils.scaleToCurrency(input.savingsAllocation, input.currencyDecimalPrecision),
      reservedAmount = DecimalUtils.scaleToCurrency(input.reservedAmount, input.currencyDecimalPrecision),
      remainingDaysInMonth = safeDays,
      isInsufficientFunds = isInsufficient,
      isTightBudget = isTight,
      currencyCode = input.currencyCode,
    )
  }

  fun remainingDaysIncludingToday(today: LocalDate): Int {
    val ym = YearMonth.of(today.year, today.monthValue)
    return (ym.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
  }
}
