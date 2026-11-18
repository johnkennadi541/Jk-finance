package com.skyplus.finance.domain.intelligence

import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.EarlyRepaymentEstimate
import com.skyplus.finance.domain.model.EmiSplitResult
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject

/**
 * Offline, ₹0-cost Loan Repayment & Debt Payoff Intelligence Engine.
 *
 * Capabilities:
 * - Snowball vs Avalanche comparison with per-loan payoff milestones, pros/cons, interest & time savings.
 * - Extra EMI payment simulator (single-loan or portfolio-wide) with instant stateless recalculation.
 * - Exact BigDecimal arithmetic reusing [LoanEmiCalculator] with strict input/edge-case guards.
 * - Never mutates actual [LoanEntity] records or triggers real payments.
 */
class DebtPayoffIntelligenceEngine @Inject constructor(
  private val loanEmiCalculator: LoanEmiCalculator,
) {

  companion object {
    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private const val MAX_SIMULATION_MONTHS = 600

    private const val BANK_PAYOFF_DISCLAIMER =
      "These figures are mathematical estimates based on current balances, entered interest rates, and regular monthly compounding. Actual lender payoff amounts may differ due to daily interest accrual, prepayment or foreclosure fees, overdue charges, processing fees, or floating rate changes."

    private val SNOWBALL_PROS = listOf(
      "Eliminates smaller loan balances first for quick, motivating milestones.",
      "Reduces the number of separate monthly EMI bills earlier in the payoff journey.",
      "Frees up minimum monthly cashflow faster in case of unexpected expenses.",
    )

    private val SNOWBALL_CONS = listOf(
      "May pay more total interest if a larger balance carries a higher interest rate.",
      "Can take slightly longer to become completely debt-free when high-APR loans are large.",
    )

    private val AVALANCHE_PROS = listOf(
      "Mathematically minimizes total remaining interest paid across your active loans.",
      "Targets the costliest high-APR debt first so more of every payment reduces principal.",
      "Often reaches full debt freedom sooner when interest rates differ significantly.",
    )

    private val AVALANCHE_CONS = listOf(
      "Can take longer to close the first account if your highest-rate loan also has a large balance.",
      "Requires steady discipline during early months before visible account closures appear.",
    )
  }

  fun analyze(
    loans: List<LoanEntity>,
    primaryCurrencyCode: String,
    currencyPrecisionMap: Map<String, Int>,
    extraMonthlyBudget: BigDecimal = BigDecimal.ZERO,
    perLoanExtraSimulationAmount: BigDecimal = BigDecimal.ZERO,
    realizedMonthlyIncome: BigDecimal? = null,
    today: LocalDate = LocalDate.now(),
  ): DebtPayoffIntelligenceReport {
    val primaryCode = primaryCurrencyCode.uppercase()
    val scale = (currencyPrecisionMap[primaryCode] ?: 2).coerceIn(0, 3)
    val safeExtraBudget = DecimalUtils.scaleToCurrency(extraMonthlyBudget.coerceAtLeast(BigDecimal.ZERO), scale)
    val safeLoanExtra = DecimalUtils.scaleToCurrency(perLoanExtraSimulationAmount.coerceAtLeast(BigDecimal.ZERO), scale)

    val dedupedLoans = deduplicateLoans(loans)
    val primaryCurrencyLoans = dedupedLoans.filter { it.currencyCode.equals(primaryCode, ignoreCase = true) }
    val excludedOtherCurrencyCount = dedupedLoans.size - primaryCurrencyLoans.size

    val activeSanitizedLoans = primaryCurrencyLoans
      .filter { it.status == LoanStatus.ACTIVE && it.outstandingPrincipal > BigDecimal.ZERO }
      .map { sanitizeLoan(it, scale) }
    val paidOffCount = primaryCurrencyLoans.count {
      it.status != LoanStatus.ACTIVE || it.outstandingPrincipal <= BigDecimal.ZERO
    }

    val totalPrincipal = DecimalUtils.scaleToCurrency(
      activeSanitizedLoans.fold(BigDecimal.ZERO) { acc, loan -> acc.add(loan.outstandingPrincipal) },
      scale,
    )
    val totalMinEmi = DecimalUtils.scaleToCurrency(
      activeSanitizedLoans.fold(BigDecimal.ZERO) { acc, loan -> acc.add(loan.emiAmount) },
      scale,
    )

    val weightedRate = if (totalPrincipal > BigDecimal.ZERO) {
      val weightedNumerator = activeSanitizedLoans.fold(BigDecimal.ZERO) { acc, loan ->
        acc.add(loan.outstandingPrincipal.multiply(loan.annualInterestRatePercent, MC))
      }
      weightedNumerator.divide(totalPrincipal, 2, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    }

    val comparison = compareStrategies(
      loans = activeSanitizedLoans,
      primaryCurrencyCode = primaryCode,
      scale = scale,
      extraMonthlyBudget = safeExtraBudget,
      today = today,
    )

    val perLoanSims = primaryCurrencyLoans.map { loan ->
      simulateExtraEmiPayment(
        loan = loan,
        extraMonthlyPayment = safeLoanExtra,
        decimalPrecision = scale,
        today = today,
      )
    }

    val emiToIncomePercent = if (realizedMonthlyIncome != null && realizedMonthlyIncome > BigDecimal.ZERO && activeSanitizedLoans.isNotEmpty()) {
      totalMinEmi.multiply(BigDecimal("100"), MC)
        .divide(realizedMonthlyIncome, 2, RoundingMode.HALF_UP)
    } else if (realizedMonthlyIncome != null && realizedMonthlyIncome > BigDecimal.ZERO) {
      BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    } else {
      null
    }

    val hasNonAmortizing = comparison.snowball.hasNonAmortizingLoanWarning || comparison.avalanche.hasNonAmortizingLoanWarning
    val (riskLevel, riskSummary) = evaluateDebtRisk(
      activeLoans = activeSanitizedLoans,
      totalPrincipal = totalPrincipal,
      totalMinEmi = totalMinEmi,
      emiToIncomePercent = emiToIncomePercent,
      hasNonAmortizing = hasNonAmortizing,
      primaryCode = primaryCode,
    )

    val assumptions = buildAssumptions(
      primaryCode = primaryCode,
      extraMonthlyBudget = safeExtraBudget,
      excludedOtherCurrencyCount = excludedOtherCurrencyCount,
      hasZeroInterestLoans = activeSanitizedLoans.any { it.annualInterestRatePercent.compareTo(BigDecimal.ZERO) == 0 },
      hasNonAmortizing = hasNonAmortizing,
    )

    return DebtPayoffIntelligenceReport(
      status = if (activeSanitizedLoans.isEmpty()) DebtPortfolioStatus.DEBT_FREE else DebtPortfolioStatus.ACTIVE_DEBT,
      evaluatedCurrencyCode = primaryCode,
      activeLoanCount = activeSanitizedLoans.size,
      paidOffLoanCount = paidOffCount,
      excludedOtherCurrencyLoanCount = excludedOtherCurrencyCount,
      totalOutstandingPrincipal = totalPrincipal,
      totalMinimumMonthlyEmi = totalMinEmi,
      weightedAverageInterestRatePercent = weightedRate,
      emiToMonthlyIncomePercent = emiToIncomePercent,
      debtRiskLevel = riskLevel,
      riskSummary = riskSummary,
      strategyComparison = comparison,
      perLoanSimulations = perLoanSims,
      calculationAssumptions = assumptions,
      bankPayoffDisclaimer = BANK_PAYOFF_DISCLAIMER,
    )
  }

  fun compareStrategies(
    loans: List<LoanEntity>,
    primaryCurrencyCode: String,
    scale: Int = 2,
    extraMonthlyBudget: BigDecimal = BigDecimal.ZERO,
    today: LocalDate = LocalDate.now(),
  ): SnowballVsAvalancheComparison {
    val primaryCode = primaryCurrencyCode.uppercase()
    val safeScale = scale.coerceIn(0, 3)
    val safeExtra = DecimalUtils.scaleToCurrency(extraMonthlyBudget.coerceAtLeast(BigDecimal.ZERO), safeScale)

    val dedupedActive = deduplicateLoans(loans)
      .filter {
        it.currencyCode.equals(primaryCode, ignoreCase = true) &&
          it.status == LoanStatus.ACTIVE &&
          it.outstandingPrincipal > BigDecimal.ZERO
      }
      .map { sanitizeLoan(it, safeScale) }

    if (dedupedActive.isEmpty()) {
      val emptySnowball = buildStrategyBreakdown(
        orderedLoans = emptyList(),
        strategy = DebtPayoffStrategy.SNOWBALL,
        extraMonthlyBudget = safeExtra,
        scale = safeScale,
        today = today,
      )
      val emptyAvalanche = buildStrategyBreakdown(
        orderedLoans = emptyList(),
        strategy = DebtPayoffStrategy.AVALANCHE,
        extraMonthlyBudget = safeExtra,
        scale = safeScale,
        today = today,
      )
      return SnowballVsAvalancheComparison(
        status = DebtPortfolioStatus.DEBT_FREE,
        evaluatedCurrencyCode = primaryCode,
        extraMonthlyBudgetApplied = safeExtra,
        snowball = emptySnowball,
        avalanche = emptyAvalanche,
        recommendedStrategy = DebtPayoffStrategy.AVALANCHE,
        lowerInterestStrategy = DebtPayoffStrategy.AVALANCHE,
        interestSavedByLowerInterestStrategy = DecimalUtils.scaleToCurrency(BigDecimal.ZERO, safeScale),
        fasterStrategy = DebtPayoffStrategy.AVALANCHE,
        monthsSavedByFasterStrategy = 0,
        comparisonSummary = "You have no active $primaryCode loans remaining.",
      )
    }

    // Deterministic tie-breakers:
    // Snowball: smallest outstanding principal first, then higher interest rate, then id
    val snowballOrder = dedupedActive.sortedWith(
      compareBy<LoanEntity> { it.outstandingPrincipal }
        .thenByDescending { it.annualInterestRatePercent }
        .thenBy { it.id },
    )
    // Avalanche: highest annual interest rate first, then smallest outstanding balance, then id
    val avalancheOrder = dedupedActive.sortedWith(
      compareByDescending<LoanEntity> { it.annualInterestRatePercent }
        .thenBy { it.outstandingPrincipal }
        .thenBy { it.id },
    )

    val snowballBreakdown = buildStrategyBreakdown(
      orderedLoans = snowballOrder,
      strategy = DebtPayoffStrategy.SNOWBALL,
      extraMonthlyBudget = safeExtra,
      scale = safeScale,
      today = today,
    )
    val avalancheBreakdown = buildStrategyBreakdown(
      orderedLoans = avalancheOrder,
      strategy = DebtPayoffStrategy.AVALANCHE,
      extraMonthlyBudget = safeExtra,
      scale = safeScale,
      today = today,
    )

    val snowballInterest = snowballBreakdown.totalInterestProjected
    val avalancheInterest = avalancheBreakdown.totalInterestProjected

    val (lowerInterestStrategy, interestSaved) = when {
      avalancheInterest <= snowballInterest ->
        DebtPayoffStrategy.AVALANCHE to DecimalUtils.scaleToCurrency(snowballInterest.subtract(avalancheInterest), safeScale)
      else ->
        DebtPayoffStrategy.SNOWBALL to DecimalUtils.scaleToCurrency(avalancheInterest.subtract(snowballInterest), safeScale)
    }

    val (fasterStrategy, monthsSaved) = when {
      avalancheBreakdown.monthsToDebtFree < snowballBreakdown.monthsToDebtFree ->
        DebtPayoffStrategy.AVALANCHE to (snowballBreakdown.monthsToDebtFree - avalancheBreakdown.monthsToDebtFree)
      snowballBreakdown.monthsToDebtFree < avalancheBreakdown.monthsToDebtFree ->
        DebtPayoffStrategy.SNOWBALL to (avalancheBreakdown.monthsToDebtFree - snowballBreakdown.monthsToDebtFree)
      else -> lowerInterestStrategy to 0
    }

    val recommended = when {
      interestSaved > BigDecimal.ZERO -> lowerInterestStrategy
      monthsSaved > 0 -> fasterStrategy
      else -> DebtPayoffStrategy.SNOWBALL // Equal financial cost -> Snowball earns quicker psychological wins
    }

    val summary = buildComparisonSummary(
      primaryCode = primaryCode,
      snowball = snowballBreakdown,
      avalanche = avalancheBreakdown,
      lowerInterestStrategy = lowerInterestStrategy,
      interestSaved = interestSaved,
      fasterStrategy = fasterStrategy,
      monthsSaved = monthsSaved,
    )

    return SnowballVsAvalancheComparison(
      status = DebtPortfolioStatus.ACTIVE_DEBT,
      evaluatedCurrencyCode = primaryCode,
      extraMonthlyBudgetApplied = safeExtra,
      snowball = snowballBreakdown,
      avalanche = avalancheBreakdown,
      recommendedStrategy = recommended,
      lowerInterestStrategy = lowerInterestStrategy,
      interestSavedByLowerInterestStrategy = interestSaved,
      fasterStrategy = fasterStrategy,
      monthsSavedByFasterStrategy = monthsSaved,
      comparisonSummary = summary,
    )
  }

  /**
   * Stateless Extra EMI Payment Simulator for a single loan.
   * Safe against negative inputs, zero-interest loans, fully paid-off loans, and non-amortizing EMIs.
   * Never modifies [loan] or triggers any repository/database changes.
   */
  fun simulateExtraEmiPayment(
    loan: LoanEntity,
    extraMonthlyPayment: BigDecimal,
    decimalPrecision: Int = 2,
    today: LocalDate = LocalDate.now(),
  ): ExtraEmiSimulationResult {
    val scale = decimalPrecision.coerceIn(0, 3)
    val zero = DecimalUtils.scaleToCurrency(BigDecimal.ZERO, scale)
    val currency = loan.currencyCode.uppercase()

    val isPaidOff = loan.status != LoanStatus.ACTIVE || loan.outstandingPrincipal <= BigDecimal.ZERO
    val safeOutstanding = DecimalUtils.scaleToCurrency(loan.outstandingPrincipal.coerceAtLeast(BigDecimal.ZERO), scale)
    val safeRate = loan.annualInterestRatePercent.coerceAtLeast(BigDecimal.ZERO)
    val safeEmi = DecimalUtils.scaleToCurrency(loan.emiAmount.coerceAtLeast(BigDecimal.ZERO), scale)
    val isNegativeExtraInput = extraMonthlyPayment < BigDecimal.ZERO
    val effectiveExtra = DecimalUtils.scaleToCurrency(extraMonthlyPayment.coerceAtLeast(BigDecimal.ZERO), scale)
    val isZeroInterest = safeRate.compareTo(BigDecimal.ZERO) == 0

    if (isPaidOff) {
      val emptySplit = EmiSplitResult(zero, zero, zero)
      val emptyEst = EarlyRepaymentEstimate(
        baselineMonthsRemaining = 0,
        acceleratedMonthsRemaining = 0,
        monthsSaved = 0,
        baselineTotalInterest = zero,
        acceleratedTotalInterest = zero,
        interestSaved = zero,
        estimatedPayoffEpochDay = today.toEpochDay(),
        isEstimated = true,
      )
      return ExtraEmiSimulationResult(
        loanId = loan.id,
        loanName = loan.loanName,
        currencyCode = currency,
        outstandingPrincipal = zero,
        annualInterestRatePercent = safeRate.setScale(2, RoundingMode.HALF_UP),
        regularMonthlyEmi = safeEmi,
        requestedExtraMonthlyPayment = DecimalUtils.scaleToCurrency(extraMonthlyPayment, scale),
        effectiveExtraMonthlyPayment = zero,
        combinedMonthlyPayment = safeEmi,
        isAlreadyPaidOff = true,
        isZeroInterestLoan = isZeroInterest,
        isValidSimulation = true,
        validationWarning = if (isNegativeExtraInput) "Negative extra payment ignored; loan is already paid off." else null,
        earlyRepaymentEstimate = emptyEst,
        normalPayoffEpochDay = today.toEpochDay(),
        acceleratedPayoffEpochDay = today.toEpochDay(),
        normalTotalPayout = zero,
        acceleratedTotalPayout = zero,
        interestSavedPercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
        firstMonthNormalSplit = emptySplit,
        firstMonthExtraPrincipalPreview = zero,
        comparisonSummary = "${loan.loanName} is already paid off (0 remaining balance).",
        isSimulationOnly = true,
      )
    }

    val sanitizedLoan = loan.copy(
      outstandingPrincipal = safeOutstanding,
      annualInterestRatePercent = safeRate,
      emiAmount = safeEmi,
    )
    val combinedPayment = DecimalUtils.scaleToCurrency(safeEmi.add(effectiveExtra), scale)

    val normalFirstSplit = loanEmiCalculator.calculatePaymentSplit(
      outstandingPrincipal = safeOutstanding,
      annualInterestRatePercent = safeRate,
      totalPaymentAmount = safeEmi,
      decimalPrecision = scale,
    )
    val combinedFirstSplit = loanEmiCalculator.calculatePaymentSplit(
      outstandingPrincipal = safeOutstanding,
      annualInterestRatePercent = safeRate,
      totalPaymentAmount = combinedPayment,
      decimalPrecision = scale,
    )
    val extraPrincipalPreview = DecimalUtils.scaleToCurrency(
      combinedFirstSplit.principalComponent.subtract(normalFirstSplit.principalComponent).coerceAtLeast(BigDecimal.ZERO),
      scale,
    )

    val monthlyInterestFirst = safeOutstanding.multiply(
      if (isZeroInterest) BigDecimal.ZERO else safeRate.divide(BigDecimal("1200"), MC),
      MC,
    ).setScale(scale, RoundingMode.HALF_UP)

    val baselineCoversInterest = safeEmi > monthlyInterestFirst || (isZeroInterest && safeEmi > BigDecimal.ZERO)
    val combinedCoversInterest = combinedPayment > monthlyInterestFirst || (isZeroInterest && combinedPayment > BigDecimal.ZERO)

    val validationWarning = when {
      isNegativeExtraInput -> "Extra monthly payment cannot be negative; simulated with $currency 0 extra."
      !combinedCoversInterest ->
        "Combined monthly payment ($currency ${combinedPayment.toPlainString()}) is too small to reduce principal beyond monthly interest ($currency ${monthlyInterestFirst.toPlainString()})."
      !baselineCoversInterest ->
        "Regular EMI ($currency ${safeEmi.toPlainString()}) does not exceed first-month interest ($currency ${monthlyInterestFirst.toPlainString()}); adding $currency ${effectiveExtra.toPlainString()}/mo enables payoff."
      else -> null
    }

    val estimate = loanEmiCalculator.estimateEarlyRepayment(
      loan = sanitizedLoan,
      extraMonthlyPayment = effectiveExtra,
      decimalPrecision = scale,
      today = today,
    )

    val normalPayoffEpoch = today.plusMonths(estimate.baselineMonthsRemaining.toLong()).toEpochDay()
    val acceleratedPayoffEpoch = estimate.estimatedPayoffEpochDay
    val normalTotalPayout = DecimalUtils.scaleToCurrency(safeOutstanding.add(estimate.baselineTotalInterest), scale)
    val acceleratedTotalPayout = DecimalUtils.scaleToCurrency(safeOutstanding.add(estimate.acceleratedTotalInterest), scale)

    val interestSavedPct = if (estimate.baselineTotalInterest > BigDecimal.ZERO && combinedCoversInterest && baselineCoversInterest) {
      estimate.interestSaved.multiply(BigDecimal("100"), MC)
        .divide(estimate.baselineTotalInterest, 2, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    }

    val summary = when {
      !combinedCoversInterest ->
        "Increase monthly payment above $currency ${monthlyInterestFirst.toPlainString()} to start reducing ${loan.loanName} principal."
      effectiveExtra <= BigDecimal.ZERO ->
        "With regular EMI of $currency ${safeEmi.toPlainString()}/mo, ${loan.loanName} finishes in ${estimate.baselineMonthsRemaining} month(s) with $currency ${estimate.baselineTotalInterest.toPlainString()} estimated interest."
      isZeroInterest ->
        "0% interest loan: adding $currency ${effectiveExtra.toPlainString()}/mo cuts payoff time from ${estimate.baselineMonthsRemaining} to ${estimate.acceleratedMonthsRemaining} month(s) (saves ${estimate.monthsSaved} mo)."
      else ->
        "Adding $currency ${effectiveExtra.toPlainString()}/mo to ${loan.loanName} saves $currency ${estimate.interestSaved.toPlainString()} (${interestSavedPct.toPlainString()}% of remaining interest) and shortens payoff by ${estimate.monthsSaved} month(s)."
    }

    return ExtraEmiSimulationResult(
      loanId = loan.id,
      loanName = loan.loanName,
      currencyCode = currency,
      outstandingPrincipal = safeOutstanding,
      annualInterestRatePercent = safeRate.setScale(2, RoundingMode.HALF_UP),
      regularMonthlyEmi = safeEmi,
      requestedExtraMonthlyPayment = DecimalUtils.scaleToCurrency(extraMonthlyPayment, scale),
      effectiveExtraMonthlyPayment = effectiveExtra,
      combinedMonthlyPayment = combinedPayment,
      isAlreadyPaidOff = false,
      isZeroInterestLoan = isZeroInterest,
      isValidSimulation = combinedCoversInterest && !isNegativeExtraInput,
      validationWarning = validationWarning,
      earlyRepaymentEstimate = estimate,
      normalPayoffEpochDay = normalPayoffEpoch,
      acceleratedPayoffEpochDay = acceleratedPayoffEpoch,
      normalTotalPayout = normalTotalPayout,
      acceleratedTotalPayout = acceleratedTotalPayout,
      interestSavedPercent = interestSavedPct,
      firstMonthNormalSplit = normalFirstSplit,
      firstMonthExtraPrincipalPreview = extraPrincipalPreview,
      comparisonSummary = summary,
      isSimulationOnly = true,
    )
  }

  private fun buildStrategyBreakdown(
    orderedLoans: List<LoanEntity>,
    strategy: DebtPayoffStrategy,
    extraMonthlyBudget: BigDecimal,
    scale: Int,
    today: LocalDate,
  ): StrategyBreakdown {
    val strategyTitle = when (strategy) {
      DebtPayoffStrategy.SNOWBALL -> "Debt Snowball (Smallest Balance First)"
      DebtPayoffStrategy.AVALANCHE -> "Debt Avalanche (Highest Interest First)"
    }
    val pros = if (strategy == DebtPayoffStrategy.SNOWBALL) SNOWBALL_PROS else AVALANCHE_PROS
    val cons = if (strategy == DebtPayoffStrategy.SNOWBALL) SNOWBALL_CONS else AVALANCHE_CONS

    // Reuse LoanEmiCalculator directly for the core strategy plan
    val plan = loanEmiCalculator.calculateDebtStrategyPlan(
      loans = orderedLoans,
      strategy = strategy,
      extraMonthlyBudget = extraMonthlyBudget,
      decimalPrecision = scale,
      today = today,
    )

    val (milestones, hasNonAmortizing) = computePerLoanMilestones(
      orderedLoans = orderedLoans,
      extraMonthlyBudget = extraMonthlyBudget,
      scale = scale,
      today = today,
    )

    val totalPrincipal = DecimalUtils.scaleToCurrency(
      orderedLoans.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.outstandingPrincipal) },
      scale,
    )
    val totalInterest = DecimalUtils.scaleToCurrency(plan.totalInterestProjected, scale)
    val totalPayout = DecimalUtils.scaleToCurrency(totalPrincipal.add(totalInterest), scale)

    return StrategyBreakdown(
      strategy = strategy,
      strategyTitle = strategyTitle,
      plan = plan,
      orderedLoanIds = orderedLoans.map { it.id },
      orderedLoanNames = plan.orderedLoanNames,
      monthsToDebtFree = plan.monthsToDebtFree,
      estimatedDebtFreeEpochDay = plan.estimatedDebtFreeEpochDay,
      totalPrincipalRemaining = totalPrincipal,
      totalInterestProjected = totalInterest,
      totalPayoutProjected = totalPayout,
      loanPayoffTimeline = milestones,
      advantages = pros,
      disadvantages = cons,
      hasNonAmortizingLoanWarning = hasNonAmortizing,
    )
  }

  private fun computePerLoanMilestones(
    orderedLoans: List<LoanEntity>,
    extraMonthlyBudget: BigDecimal,
    scale: Int,
    today: LocalDate,
  ): Pair<List<LoanPayoffMilestone>, Boolean> {
    if (orderedLoans.isEmpty()) return emptyList<LoanPayoffMilestone>() to false

    val n = orderedLoans.size
    val balances = orderedLoans.map { it.outstandingPrincipal }.toMutableList()
    val rates = orderedLoans.map {
      if (it.annualInterestRatePercent > BigDecimal.ZERO) {
        it.annualInterestRatePercent.divide(BigDecimal("1200"), MC)
      } else {
        BigDecimal.ZERO
      }
    }
    val minEmis = orderedLoans.map { it.emiAmount.coerceAtLeast(BigDecimal.ZERO) }
    val interestPerLoan = MutableList(n) { BigDecimal.ZERO }
    val payoffMonthPerLoan = MutableList<Int?>(n) { null }

    var months = 0
    while (balances.any { it > BigDecimal.ZERO } && months < MAX_SIMULATION_MONTHS) {
      months++
      var extraPool = extraMonthlyBudget.coerceAtLeast(BigDecimal.ZERO)

      for (i in 0 until n) {
        if (balances[i] <= BigDecimal.ZERO) {
          extraPool = extraPool.add(minEmis[i])
          continue
        }
        val interest = balances[i].multiply(rates[i], MC).setScale(scale, RoundingMode.HALF_UP)
        interestPerLoan[i] = interestPerLoan[i].add(interest)
        val totalOwed = balances[i].add(interest)
        val payment = minEmis[i].min(totalOwed)
        balances[i] = totalOwed.subtract(payment)
        if (balances[i] <= BigDecimal.ZERO && payoffMonthPerLoan[i] == null) {
          payoffMonthPerLoan[i] = months
        }
      }

      for (i in 0 until n) {
        if (extraPool <= BigDecimal.ZERO) break
        if (balances[i] > BigDecimal.ZERO) {
          val extraApplied = extraPool.min(balances[i])
          balances[i] = balances[i].subtract(extraApplied)
          extraPool = extraPool.subtract(extraApplied)
          if (balances[i] <= BigDecimal.ZERO && payoffMonthPerLoan[i] == null) {
            payoffMonthPerLoan[i] = months
          }
        }
      }
    }

    var anyNotAmortized = false
    val milestones = orderedLoans.mapIndexed { idx, loan ->
      val clearedMonth = payoffMonthPerLoan[idx]
      val amortized = clearedMonth != null
      if (!amortized) anyNotAmortized = true
      val effectiveMonths = clearedMonth ?: MAX_SIMULATION_MONTHS
      LoanPayoffMilestone(
        loanId = loan.id,
        loanName = loan.loanName,
        lender = loan.lender,
        currencyCode = loan.currencyCode.uppercase(),
        outstandingPrincipal = DecimalUtils.scaleToCurrency(loan.outstandingPrincipal, scale),
        annualInterestRatePercent = loan.annualInterestRatePercent.setScale(2, RoundingMode.HALF_UP),
        monthlyEmi = DecimalUtils.scaleToCurrency(loan.emiAmount, scale),
        payoffOrder = idx + 1,
        estimatedMonthsToPayoff = effectiveMonths,
        estimatedPayoffEpochDay = today.plusMonths(effectiveMonths.toLong()).toEpochDay(),
        projectedInterestPaid = DecimalUtils.scaleToCurrency(interestPerLoan[idx], scale),
        amortizesWithinHorizon = amortized,
      )
    }

    return milestones to anyNotAmortized
  }

  private fun buildComparisonSummary(
    primaryCode: String,
    snowball: StrategyBreakdown,
    avalanche: StrategyBreakdown,
    lowerInterestStrategy: DebtPayoffStrategy,
    interestSaved: BigDecimal,
    fasterStrategy: DebtPayoffStrategy,
    monthsSaved: Int,
  ): String {
    if (snowball.hasNonAmortizingLoanWarning || avalanche.hasNonAmortizingLoanWarning) {
      return "At least one loan's EMI does not cover monthly interest at current inputs; increase EMI or extra monthly budget to reach payoff within 600 months."
    }
    if (interestSaved <= BigDecimal.ZERO && monthsSaved == 0) {
      return "Snowball and Avalanche produce the same estimated interest ($primaryCode ${snowball.totalInterestProjected.toPlainString()}) and duration (${snowball.monthsToDebtFree} months); Snowball offers quicker early milestones."
    }

    val lowerLabel = if (lowerInterestStrategy == DebtPayoffStrategy.AVALANCHE) "Avalanche" else "Snowball"
    val higherLabel = if (lowerInterestStrategy == DebtPayoffStrategy.AVALANCHE) "Snowball" else "Avalanche"
    val timeClause = if (monthsSaved > 0) {
      val fasterLabel = if (fasterStrategy == DebtPayoffStrategy.AVALANCHE) "Avalanche" else "Snowball"
      " and $fasterLabel finishes $monthsSaved month(s) sooner"
    } else {
      " with the same ${snowball.monthsToDebtFree}-month payoff duration"
    }
    return "$lowerLabel saves an estimated $primaryCode ${interestSaved.toPlainString()} in total interest compared to $higherLabel$timeClause."
  }

  private fun evaluateDebtRisk(
    activeLoans: List<LoanEntity>,
    totalPrincipal: BigDecimal,
    totalMinEmi: BigDecimal,
    emiToIncomePercent: BigDecimal?,
    hasNonAmortizing: Boolean,
    primaryCode: String,
  ): Pair<DebtRiskLevel, String> {
    if (activeLoans.isEmpty()) {
      return DebtRiskLevel.NONE to "Debt-free in $primaryCode with zero active monthly EMI obligations."
    }
    if (hasNonAmortizing) {
      return DebtRiskLevel.CRITICAL to
        "One or more active loans have an EMI that does not reduce principal beyond monthly interest; prioritize increasing payment or reviewing terms."
    }
    if (emiToIncomePercent == null) {
      return DebtRiskLevel.MODERATE to
        "${activeLoans.size} active loan(s) totaling $primaryCode ${totalPrincipal.toPlainString()} with $primaryCode ${totalMinEmi.toPlainString()}/mo minimum EMI."
    }
    return when {
      emiToIncomePercent <= BigDecimal("15") ->
        DebtRiskLevel.HEALTHY to "EMI burden is ${emiToIncomePercent.toPlainString()}% of monthly income — comfortably manageable."
      emiToIncomePercent <= BigDecimal("30") ->
        DebtRiskLevel.MODERATE to "EMI burden is ${emiToIncomePercent.toPlainString()}% of monthly income — moderate commitment."
      emiToIncomePercent <= BigDecimal("45") ->
        DebtRiskLevel.HIGH to "EMI burden is ${emiToIncomePercent.toPlainString()}% of monthly income — elevated pressure; consider Avalanche extra payments."
      else ->
        DebtRiskLevel.CRITICAL to "EMI burden is ${emiToIncomePercent.toPlainString()}% of monthly income — critical cashflow strain."
    }
  }

  private fun buildAssumptions(
    primaryCode: String,
    extraMonthlyBudget: BigDecimal,
    excludedOtherCurrencyCount: Int,
    hasZeroInterestLoans: Boolean,
    hasNonAmortizing: Boolean,
  ): List<String> {
    val list = mutableListOf(
      "Uses monthly reducing-balance compounding (annual rate / 12) on current outstanding principal in $primaryCode.",
      "Assumes regular EMIs are paid on time each month and freed EMIs from closed loans automatically roll into the next priority loan.",
      "Extra budget applied to portfolio strategy simulation: $primaryCode ${extraMonthlyBudget.toPlainString()}/month (read-only simulation; no loan records altered).",
    )
    if (hasZeroInterestLoans) {
      list.add("Zero-interest loans incur 0.00 interest and amortize purely from principal repayments.")
    }
    if (excludedOtherCurrencyCount > 0) {
      list.add("$excludedOtherCurrencyCount non-$primaryCode loan(s) were excluded so currencies are never mixed in payoff totals.")
    }
    if (hasNonAmortizing) {
      list.add("Simulation is capped at $MAX_SIMULATION_MONTHS months if monthly EMI plus extra budget cannot cover accruing interest.")
    }
    return list
  }

  private fun sanitizeLoan(loan: LoanEntity, scale: Int): LoanEntity = loan.copy(
    outstandingPrincipal = DecimalUtils.scaleToCurrency(loan.outstandingPrincipal.coerceAtLeast(BigDecimal.ZERO), scale),
    annualInterestRatePercent = loan.annualInterestRatePercent.coerceAtLeast(BigDecimal.ZERO),
    emiAmount = DecimalUtils.scaleToCurrency(loan.emiAmount.coerceAtLeast(BigDecimal.ZERO), scale),
  )

  private fun deduplicateLoans(loans: List<LoanEntity>): List<LoanEntity> {
    val seenIds = HashSet<Long>()
    val seenSignatures = HashSet<String>()
    val result = ArrayList<LoanEntity>(loans.size)
    for (loan in loans) {
      if (loan.id > 0L && !seenIds.add(loan.id)) continue
      val sig = "${loan.loanName.trim().lowercase()}|${loan.lender.trim().lowercase()}|${loan.currencyCode.uppercase()}|${loan.startDateEpochDay}"
      if (loan.id == 0L && !seenSignatures.add(sig)) continue
      result.add(loan)
    }
    return result
  }
}
