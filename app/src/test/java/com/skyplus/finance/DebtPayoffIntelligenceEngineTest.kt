package com.skyplus.finance

import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceEngine
import com.skyplus.finance.domain.intelligence.DebtPortfolioStatus
import com.skyplus.finance.domain.intelligence.DebtRiskLevel
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.LoanStatus
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtPayoffIntelligenceEngineTest {

  private val loanEmiCalculator = LoanEmiCalculator()
  private val engine = DebtPayoffIntelligenceEngine(loanEmiCalculator)
  private val today = LocalDate.of(2026, 11, 15)
  private val precisionMap = mapOf("INR" to 2, "KWD" to 3, "USD" to 2)

  @Test
  fun `debt free and paid off loans return clean debt free status with zero interest`() {
    val paidOffLoan = LoanEntity(
      id = 1,
      loanName = "Old Bike Loan",
      lender = "Bank A",
      originalPrincipal = BigDecimal("80000"),
      outstandingPrincipal = BigDecimal("0.00"),
      annualInterestRatePercent = BigDecimal("11.5"),
      emiAmount = BigDecimal("4000"),
      currencyCode = "INR",
      paymentDueDayOfMonth = 5,
      nextDueDateEpochDay = today.plusDays(10).toEpochDay(),
      remainingTenureMonths = 0,
      startDateEpochDay = today.minusMonths(20).toEpochDay(),
      status = LoanStatus.PAID_OFF,
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 1,
    )

    val emptyReport = engine.analyze(
      loans = emptyList(),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    assertEquals(DebtPortfolioStatus.DEBT_FREE, emptyReport.status)
    assertEquals(DebtRiskLevel.NONE, emptyReport.debtRiskLevel)
    assertEquals(0, emptyReport.activeLoanCount)
    assertEquals(BigDecimal("0.00"), emptyReport.strategyComparison.snowball.totalInterestProjected)
    assertEquals(BigDecimal("0.00"), emptyReport.strategyComparison.avalanche.totalInterestProjected)

    val paidReport = engine.analyze(
      loans = listOf(paidOffLoan),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      perLoanExtraSimulationAmount = BigDecimal("1000"),
      today = today,
    )
    assertEquals(DebtPortfolioStatus.DEBT_FREE, paidReport.status)
    assertEquals(0, paidReport.activeLoanCount)
    assertEquals(1, paidReport.paidOffLoanCount)
    assertEquals(1, paidReport.perLoanSimulations.size)
    assertTrue(paidReport.perLoanSimulations.first().isAlreadyPaidOff)
    assertEquals(0, paidReport.perLoanSimulations.first().earlyRepaymentEstimate.monthsSaved)
  }

  @Test
  fun `snowball vs avalanche accurately orders loans computes savings pros cons and milestones`() {
    val smallLowRate = LoanEntity(
      id = 1,
      loanName = "Consumer Loan",
      lender = "FinCorp",
      originalPrincipal = BigDecimal("25000"),
      outstandingPrincipal = BigDecimal("12000"),
      annualInterestRatePercent = BigDecimal("6.00"),
      emiAmount = BigDecimal("1200"),
      currencyCode = "INR",
      paymentDueDayOfMonth = 5,
      nextDueDateEpochDay = today.plusDays(5).toEpochDay(),
      remainingTenureMonths = 11,
      startDateEpochDay = today.minusMonths(10).toEpochDay(),
      status = LoanStatus.ACTIVE,
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 1,
    )
    val largeHighRate = LoanEntity(
      id = 2,
      loanName = "Credit Card Debt",
      lender = "Metro Bank",
      originalPrincipal = BigDecimal("60000"),
      outstandingPrincipal = BigDecimal("48000"),
      annualInterestRatePercent = BigDecimal("24.00"),
      emiAmount = BigDecimal("2500"),
      currencyCode = "INR",
      paymentDueDayOfMonth = 10,
      nextDueDateEpochDay = today.plusDays(12).toEpochDay(),
      remainingTenureMonths = 24,
      startDateEpochDay = today.minusMonths(6).toEpochDay(),
      status = LoanStatus.ACTIVE,
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 1,
    )

    val report = engine.analyze(
      loans = listOf(smallLowRate, largeHighRate),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      extraMonthlyBudget = BigDecimal("1500"),
      realizedMonthlyIncome = BigDecimal("20000"),
      today = today,
    )

    assertEquals(DebtPortfolioStatus.ACTIVE_DEBT, report.status)
    assertEquals(2, report.activeLoanCount)
    assertEquals(BigDecimal("60000.00"), report.totalOutstandingPrincipal)
    assertEquals(BigDecimal("3700.00"), report.totalMinimumMonthlyEmi)
    // EMI/income = 3700 / 20000 = 18.50% -> MODERATE
    assertEquals(BigDecimal("18.50"), report.emiToMonthlyIncomePercent)
    assertEquals(DebtRiskLevel.MODERATE, report.debtRiskLevel)

    val cmp = report.strategyComparison
    assertEquals(listOf("Consumer Loan", "Credit Card Debt"), cmp.snowball.orderedLoanNames)
    assertEquals(listOf("Credit Card Debt", "Consumer Loan"), cmp.avalanche.orderedLoanNames)

    // Avalanche must save strictly more interest when high rate loan has larger balance
    assertEquals(DebtPayoffStrategy.AVALANCHE, cmp.lowerInterestStrategy)
    assertTrue(cmp.interestSavedByLowerInterestStrategy > BigDecimal.ZERO)
    assertEquals(
      cmp.snowball.totalInterestProjected.subtract(cmp.avalanche.totalInterestProjected),
      cmp.interestSavedByLowerInterestStrategy,
    )

    // Totals consistency: principal + interest = payout; milestone interest sum matches strategy total
    assertEquals(
      cmp.avalanche.totalPrincipalRemaining.add(cmp.avalanche.totalInterestProjected),
      cmp.avalanche.totalPayoutProjected,
    )
    val avalancheMilestoneInterest = cmp.avalanche.loanPayoffTimeline.fold(BigDecimal.ZERO) { acc, m ->
      acc.add(m.projectedInterestPaid)
    }
    assertEquals(cmp.avalanche.totalInterestProjected, avalancheMilestoneInterest)

    // Estimated debt-free date aligned with monthsToDebtFree
    assertEquals(
      today.plusMonths(cmp.avalanche.monthsToDebtFree.toLong()).toEpochDay(),
      cmp.avalanche.estimatedDebtFreeEpochDay,
    )

    // Pros, cons, assumptions, disclaimer must be populated
    assertTrue(cmp.snowball.advantages.isNotEmpty())
    assertTrue(cmp.snowball.disadvantages.isNotEmpty())
    assertTrue(cmp.avalanche.advantages.isNotEmpty())
    assertTrue(cmp.avalanche.disadvantages.isNotEmpty())
    assertTrue(report.calculationAssumptions.isNotEmpty())
    assertTrue(report.bankPayoffDisclaimer.contains("estimates", ignoreCase = true))
  }

  @Test
  fun `extra emi simulator recalculates instantly compares normal vs extra and never mutates loan`() {
    val autoLoan = LoanEntity(
      id = 7,
      loanName = "Car Loan",
      lender = "Horizon Bank",
      originalPrincipal = BigDecimal("200000"),
      outstandingPrincipal = BigDecimal("100000"),
      annualInterestRatePercent = BigDecimal("12.00"),
      emiAmount = BigDecimal("8884.88"),
      currencyCode = "INR",
      paymentDueDayOfMonth = 5,
      nextDueDateEpochDay = today.plusDays(3).toEpochDay(),
      remainingTenureMonths = 12,
      startDateEpochDay = today.minusMonths(12).toEpochDay(),
      status = LoanStatus.ACTIVE,
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 1,
    )

    val simZero = engine.simulateExtraEmiPayment(autoLoan, BigDecimal.ZERO, 2, today)
    val sim2k = engine.simulateExtraEmiPayment(autoLoan, BigDecimal("2000"), 2, today)
    val sim5k = engine.simulateExtraEmiPayment(autoLoan, BigDecimal("5000"), 2, today)

    assertTrue(simZero.isValidSimulation)
    assertTrue(sim2k.isValidSimulation)
    assertTrue(sim5k.isValidSimulation)
    assertTrue(simZero.isSimulationOnly)

    assertEquals(BigDecimal.ZERO.setScale(2), simZero.earlyRepaymentEstimate.interestSaved)
    assertEquals(0, simZero.earlyRepaymentEstimate.monthsSaved)

    // Higher extra payment shortens payoff and saves progressively more interest
    assertTrue(sim2k.earlyRepaymentEstimate.monthsSaved > 0)
    assertTrue(sim5k.earlyRepaymentEstimate.monthsSaved >= sim2k.earlyRepaymentEstimate.monthsSaved)
    assertTrue(sim2k.earlyRepaymentEstimate.interestSaved > BigDecimal.ZERO)
    assertTrue(sim5k.earlyRepaymentEstimate.interestSaved > sim2k.earlyRepaymentEstimate.interestSaved)
    assertTrue(sim5k.acceleratedPayoffEpochDay <= sim2k.acceleratedPayoffEpochDay)
    assertTrue(sim2k.acceleratedPayoffEpochDay < sim2k.normalPayoffEpochDay)
    assertTrue(sim2k.normalTotalPayout > sim2k.acceleratedTotalPayout)
    assertEquals(BigDecimal("2000.00"), sim2k.firstMonthExtraPrincipalPreview)

    // Original entity must remain completely untouched
    assertEquals(BigDecimal("100000"), autoLoan.outstandingPrincipal)
    assertEquals(BigDecimal("8884.88"), autoLoan.emiAmount)
  }

  @Test
  fun `handles zero interest loans deduplication and multi currency exclusion accurately`() {
    val zeroInterestLoan = LoanEntity(
      id = 10,
      loanName = "Family 0% Loan",
      lender = "Family",
      originalPrincipal = BigDecimal("24000"),
      outstandingPrincipal = BigDecimal("12000"),
      annualInterestRatePercent = BigDecimal.ZERO,
      emiAmount = BigDecimal("1000"),
      currencyCode = "INR",
      paymentDueDayOfMonth = 1,
      nextDueDateEpochDay = today.plusDays(7).toEpochDay(),
      remainingTenureMonths = 12,
      startDateEpochDay = today.minusMonths(12).toEpochDay(),
      status = LoanStatus.ACTIVE,
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 1,
    )
    // Duplicate of id=10 and a USD loan that must not be mixed into INR totals
    val duplicateZeroInterest = zeroInterestLoan.copy()
    val usdLoan = zeroInterestLoan.copy(id = 11, loanName = "US Card", currencyCode = "USD", outstandingPrincipal = BigDecimal("5000"))

    val report = engine.analyze(
      loans = listOf(zeroInterestLoan, duplicateZeroInterest, usdLoan),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    // Never double-counts duplicate loan and never mixes USD into INR
    assertEquals(1, report.activeLoanCount)
    assertEquals(1, report.excludedOtherCurrencyLoanCount)
    assertEquals(BigDecimal("12000.00"), report.totalOutstandingPrincipal)
    assertEquals(BigDecimal("0.00"), report.strategyComparison.snowball.totalInterestProjected)
    assertEquals(12, report.strategyComparison.snowball.monthsToDebtFree)

    val sim = engine.simulateExtraEmiPayment(zeroInterestLoan, BigDecimal("1000"), 2, today)
    assertTrue(sim.isZeroInterestLoan)
    assertTrue(sim.isValidSimulation)
    assertNull(sim.validationWarning)
    assertEquals(12, sim.earlyRepaymentEstimate.baselineMonthsRemaining)
    assertEquals(6, sim.earlyRepaymentEstimate.acceleratedMonthsRemaining)
    assertEquals(6, sim.earlyRepaymentEstimate.monthsSaved)
    assertEquals(BigDecimal("0.00"), sim.earlyRepaymentEstimate.interestSaved)
  }

  @Test
  fun `flags negative inputs and non amortizing emis safely without invalid payoff claims`() {
    val underpayingLoan = LoanEntity(
      id = 20,
      loanName = "Underpaying Debt",
      lender = "Bank Z",
      originalPrincipal = BigDecimal("100000"),
      outstandingPrincipal = BigDecimal("100000"),
      annualInterestRatePercent = BigDecimal("24.00"), // monthly interest = 2000.00
      emiAmount = BigDecimal("1500.00"), // less than monthly interest!
      currencyCode = "INR",
      paymentDueDayOfMonth = 15,
      nextDueDateEpochDay = today.plusDays(10).toEpochDay(),
      remainingTenureMonths = 60,
      startDateEpochDay = today.minusMonths(2).toEpochDay(),
      status = LoanStatus.ACTIVE,
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 1,
    )

    val negativeExtraSim = engine.simulateExtraEmiPayment(underpayingLoan, BigDecimal("-500"), 2, today)
    assertFalse(negativeExtraSim.isValidSimulation)
    assertNotNull(negativeExtraSim.validationWarning)
    assertEquals(BigDecimal("0.00"), negativeExtraSim.effectiveExtraMonthlyPayment)

    val zeroExtraUnderpay = engine.simulateExtraEmiPayment(underpayingLoan, BigDecimal.ZERO, 2, today)
    assertFalse(zeroExtraUnderpay.isValidSimulation)
    assertTrue(zeroExtraUnderpay.validationWarning!!.contains("too small", ignoreCase = true))

    val sufficientExtra = engine.simulateExtraEmiPayment(underpayingLoan, BigDecimal("2500"), 2, today)
    assertTrue(sufficientExtra.isValidSimulation)
    assertTrue(sufficientExtra.earlyRepaymentEstimate.acceleratedMonthsRemaining < 600)

    val report = engine.analyze(
      loans = listOf(underpayingLoan),
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    assertTrue(report.strategyComparison.snowball.hasNonAmortizingLoanWarning)
    assertEquals(DebtRiskLevel.CRITICAL, report.debtRiskLevel)
  }
}
