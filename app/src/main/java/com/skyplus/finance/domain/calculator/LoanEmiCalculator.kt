package com.skyplus.finance.domain.calculator

import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.DebtStrategyPlan
import com.skyplus.finance.domain.model.EarlyRepaymentEstimate
import com.skyplus.finance.domain.model.EmiSplitResult
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject

class LoanEmiCalculator @Inject constructor() {

  companion object {
    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private const val MAX_SIMULATION_MONTHS = 600
  }

  fun calculateMonthlyEmi(
    principal: BigDecimal,
    annualInterestRatePercent: BigDecimal,
    tenureMonths: Int,
    decimalPrecision: Int = 2,
  ): BigDecimal {
    if (principal <= BigDecimal.ZERO || tenureMonths <= 0) {
      return BigDecimal.ZERO.setScale(decimalPrecision.coerceIn(0, 3), RoundingMode.HALF_UP)
    }
    if (annualInterestRatePercent <= BigDecimal.ZERO) {
      return DecimalUtils.divideExact(principal, BigDecimal(tenureMonths), decimalPrecision.coerceIn(0, 3))
    }

    val monthlyRate = annualInterestRatePercent
      .divide(BigDecimal("1200"), MC)
    val onePlusR = BigDecimal.ONE.add(monthlyRate, MC)
    val factor = onePlusR.pow(tenureMonths, MC)
    val numerator = principal.multiply(monthlyRate, MC).multiply(factor, MC)
    val denominator = factor.subtract(BigDecimal.ONE, MC)
    if (denominator.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO.setScale(decimalPrecision.coerceIn(0, 3), RoundingMode.HALF_UP)
    }
    return numerator.divide(denominator, decimalPrecision.coerceIn(0, 3), RoundingMode.HALF_UP)
  }

  fun calculatePaymentSplit(
    outstandingPrincipal: BigDecimal,
    annualInterestRatePercent: BigDecimal,
    totalPaymentAmount: BigDecimal,
    decimalPrecision: Int = 2,
  ): EmiSplitResult {
    val scale = decimalPrecision.coerceIn(0, 3)
    if (outstandingPrincipal <= BigDecimal.ZERO) {
      val zero = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
      return EmiSplitResult(zero, zero, zero)
    }

    val monthlyRate = if (annualInterestRatePercent > BigDecimal.ZERO) {
      annualInterestRatePercent.divide(BigDecimal("1200"), MC)
    } else {
      BigDecimal.ZERO
    }
    val rawInterest = outstandingPrincipal.multiply(monthlyRate, MC)
      .setScale(scale, RoundingMode.HALF_UP)
    val cappedInterest = rawInterest.min(totalPaymentAmount)
    val principalPaid = totalPaymentAmount.subtract(cappedInterest)
      .coerceAtLeast(BigDecimal.ZERO)
      .min(outstandingPrincipal)
      .setScale(scale, RoundingMode.HALF_UP)
    val remaining = outstandingPrincipal.subtract(principalPaid)
      .coerceAtLeast(BigDecimal.ZERO)
      .setScale(scale, RoundingMode.HALF_UP)

    return EmiSplitResult(
      interestComponent = cappedInterest,
      principalComponent = principalPaid,
      remainingBalanceAfter = remaining,
    )
  }

  fun estimateEarlyRepayment(
    loan: LoanEntity,
    extraMonthlyPayment: BigDecimal,
    decimalPrecision: Int = 2,
    today: LocalDate = LocalDate.now(),
  ): EarlyRepaymentEstimate {
    val scale = decimalPrecision.coerceIn(0, 3)
    val baseline = simulateSingleLoan(
      outstanding = loan.outstandingPrincipal,
      annualRate = loan.annualInterestRatePercent,
      monthlyPayment = loan.emiAmount,
      scale = scale,
    )
    val accelerated = simulateSingleLoan(
      outstanding = loan.outstandingPrincipal,
      annualRate = loan.annualInterestRatePercent,
      monthlyPayment = loan.emiAmount.add(extraMonthlyPayment.coerceAtLeast(BigDecimal.ZERO)),
      scale = scale,
    )

    val monthsSaved = (baseline.first - accelerated.first).coerceAtLeast(0)
    val interestSaved = baseline.second.subtract(accelerated.second).coerceAtLeast(BigDecimal.ZERO)
    val payoffDate = today.plusMonths(accelerated.first.toLong())

    return EarlyRepaymentEstimate(
      baselineMonthsRemaining = baseline.first,
      acceleratedMonthsRemaining = accelerated.first,
      monthsSaved = monthsSaved,
      baselineTotalInterest = baseline.second,
      acceleratedTotalInterest = accelerated.second,
      interestSaved = interestSaved,
      estimatedPayoffEpochDay = payoffDate.toEpochDay(),
      isEstimated = true,
    )
  }

  fun calculateDebtStrategyPlan(
    loans: List<LoanEntity>,
    strategy: DebtPayoffStrategy,
    extraMonthlyBudget: BigDecimal = BigDecimal.ZERO,
    decimalPrecision: Int = 2,
    today: LocalDate = LocalDate.now(),
  ): DebtStrategyPlan {
    val scale = decimalPrecision.coerceIn(0, 3)
    val activeLoans = loans.filter { it.status == LoanStatus.ACTIVE && it.outstandingPrincipal > BigDecimal.ZERO }
    if (activeLoans.isEmpty()) {
      return DebtStrategyPlan(
        strategy = strategy,
        orderedLoanNames = emptyList(),
        monthsToDebtFree = 0,
        estimatedDebtFreeEpochDay = today.toEpochDay(),
        totalInterestProjected = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP),
        isEstimated = true,
      )
    }

    val ordered = when (strategy) {
      DebtPayoffStrategy.SNOWBALL -> activeLoans.sortedBy { it.outstandingPrincipal }
      DebtPayoffStrategy.AVALANCHE -> activeLoans.sortedByDescending { it.annualInterestRatePercent }
    }

    val balances = ordered.map { it.outstandingPrincipal }.toMutableList()
    val rates = ordered.map {
      if (it.annualInterestRatePercent > BigDecimal.ZERO) {
        it.annualInterestRatePercent.divide(BigDecimal("1200"), MC)
      } else {
        BigDecimal.ZERO
      }
    }
    val minEmis = ordered.map { it.emiAmount.coerceAtLeast(BigDecimal.ZERO) }

    var months = 0
    var totalInterest = BigDecimal.ZERO

    while (balances.any { it > BigDecimal.ZERO } && months < MAX_SIMULATION_MONTHS) {
      months++
      var extraPool = extraMonthlyBudget.coerceAtLeast(BigDecimal.ZERO)

      // Apply interest and minimum EMI; freed EMIs from paid-off loans roll into extraPool
      for (i in balances.indices) {
        if (balances[i] <= BigDecimal.ZERO) {
          extraPool = extraPool.add(minEmis[i])
          continue
        }
        val interest = balances[i].multiply(rates[i], MC).setScale(scale, RoundingMode.HALF_UP)
        totalInterest = totalInterest.add(interest)
        val totalOwed = balances[i].add(interest)
        val payment = minEmis[i].min(totalOwed)
        balances[i] = totalOwed.subtract(payment)
      }

      // Apply snowball/avalanche extra pool strictly in priority order
      for (i in balances.indices) {
        if (extraPool <= BigDecimal.ZERO) break
        if (balances[i] > BigDecimal.ZERO) {
          val extraApplied = extraPool.min(balances[i])
          balances[i] = balances[i].subtract(extraApplied)
          extraPool = extraPool.subtract(extraApplied)
        }
      }
    }

    return DebtStrategyPlan(
      strategy = strategy,
      orderedLoanNames = ordered.map { it.loanName },
      monthsToDebtFree = months,
      estimatedDebtFreeEpochDay = today.plusMonths(months.toLong()).toEpochDay(),
      totalInterestProjected = DecimalUtils.scaleToCurrency(totalInterest, scale),
      isEstimated = true,
    )
  }

  private fun simulateSingleLoan(
    outstanding: BigDecimal,
    annualRate: BigDecimal,
    monthlyPayment: BigDecimal,
    scale: Int,
  ): Pair<Int, BigDecimal> {
    if (outstanding <= BigDecimal.ZERO) return 0 to BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
    if (monthlyPayment <= BigDecimal.ZERO) return MAX_SIMULATION_MONTHS to BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)

    val r = if (annualRate > BigDecimal.ZERO) annualRate.divide(BigDecimal("1200"), MC) else BigDecimal.ZERO
    var balance = outstanding
    var months = 0
    var interestSum = BigDecimal.ZERO

    while (balance > BigDecimal.ZERO && months < MAX_SIMULATION_MONTHS) {
      months++
      val interest = balance.multiply(r, MC).setScale(scale, RoundingMode.HALF_UP)
      interestSum = interestSum.add(interest)
      val owed = balance.add(interest)
      val paid = monthlyPayment.min(owed)
      val nextBalance = owed.subtract(paid)
      if (nextBalance >= balance && r > BigDecimal.ZERO) {
        // Payment does not cover interest; cap at max horizon
        return MAX_SIMULATION_MONTHS to DecimalUtils.scaleToCurrency(interestSum, scale)
      }
      balance = nextBalance
    }
    return months to DecimalUtils.scaleToCurrency(interestSum, scale)
  }
}
