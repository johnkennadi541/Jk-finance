package com.skyplus.finance.domain.intelligence

import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.model.BudgetStatusItem
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Deterministic, offline-first Financial Health Score engine (0..100).
 *
 * Evaluates 8 holistic personal financial wellness dimensions grounded in financial well-being
 * principles while preserving the 4-pillar 0..100 score summary:
 * 1. Cash Flow Health
 * 2. Savings Rate
 * 3. Emergency Fund
 * 4. Debt & EMI Management
 * 5. Interest Burden
 * 6. Spending Discipline
 * 7. Net Worth Trend
 * 8. Income Stability
 *
 * Guarantees:
 * - Returns INSUFFICIENT_DATA when transaction history is too small instead of guessing.
 * - Never counts unpaid bills or future-dated income/expenses as realized cashflow.
 * - Deduplicates transactions and loans so no record or EMI is double-counted.
 * - Awards full Debt & Interest scores to debt-free users without false penalties.
 * - Never penalizes users simply because their income amount is low (ratios & consistency only).
 * - Uses exact BigDecimal arithmetic across all monetary ratios and percentages.
 */
class FinancialHealthScoreEngine @Inject constructor(
  private val budgetAndSavingsCalculator: BudgetAndSavingsCalculator,
) {

  companion object {
    private const val MIN_REALIZED_TRANSACTIONS = 3
    private val HUNDRED = BigDecimal("100")
    private val TWELVE = BigDecimal("12")
    private val MONTH_LABEL_FMT = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)
  }

  fun evaluate(
    transactions: List<TransactionEntity>,
    budgets: List<BudgetEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    loans: List<LoanEntity>,
    savingsGoals: List<SavingsGoalEntity>,
    settings: AppSettingsEntity?,
    primaryCurrencyCode: String,
    currencyPrecisionMap: Map<String, Int>,
    today: LocalDate = LocalDate.now(),
  ): FinancialHealthReport {
    return evaluateInternal(
      transactions = transactions,
      budgets = budgets,
      categoriesById = categoriesById,
      loans = loans,
      savingsGoals = savingsGoals,
      settings = settings,
      primaryCurrencyCode = primaryCurrencyCode,
      currencyPrecisionMap = currencyPrecisionMap,
      today = today,
      computeHistory = true,
    )
  }

  private fun evaluateInternal(
    transactions: List<TransactionEntity>,
    budgets: List<BudgetEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    loans: List<LoanEntity>,
    savingsGoals: List<SavingsGoalEntity>,
    settings: AppSettingsEntity?,
    primaryCurrencyCode: String,
    currencyPrecisionMap: Map<String, Int>,
    today: LocalDate,
    computeHistory: Boolean,
  ): FinancialHealthReport {
    val primaryCode = primaryCurrencyCode.uppercase()
    val precision = currencyPrecisionMap[primaryCode] ?: 2
    val todayEpoch = today.toEpochDay()
    val currentMonth = YearMonth.of(today.year, today.monthValue)
    val monthStartEpoch = currentMonth.atDay(1).toEpochDay()
    val trailing30StartEpoch = today.minusDays(29).toEpochDay()

    // 1. Deduplicate and filter strictly to paid, non-future transactions in primary currency
    val dedupedTransactions = deduplicateTransactions(transactions)
    val realizedPrimaryTxs = dedupedTransactions.filter { tx ->
      tx.isPaid &&
        tx.transactionDateEpochDay <= todayEpoch &&
        resolvePrimaryAmountOrNull(tx, primaryCode) != null
    }

    val recent90StartEpoch = today.minusDays(89).toEpochDay()
    val recentRealizedCount = realizedPrimaryTxs.count {
      it.transactionDateEpochDay in recent90StartEpoch..todayEpoch
    }

    val deduplicatedLoans = deduplicateLoans(loans)
    val activePrimaryLoans = deduplicatedLoans.filter {
      it.status == LoanStatus.ACTIVE &&
        it.outstandingPrincipal > BigDecimal.ZERO &&
        it.currencyCode.equals(primaryCode, ignoreCase = true)
    }

    val deduplicatedGoals = deduplicateGoals(savingsGoals)
    val activeEmergencyGoals = deduplicatedGoals.filter {
      it.isEmergencyFund && it.currencyCode.equals(primaryCode, ignoreCase = true)
    }
    val emergencySaved = DecimalUtils.scaleToCurrency(
      activeEmergencyGoals.fold(BigDecimal.ZERO) { acc, g -> acc.add(g.currentSavedAmount.coerceAtLeast(BigDecimal.ZERO)) },
      precision,
    )

    val monthTxs = realizedPrimaryTxs.filter { it.transactionDateEpochDay in monthStartEpoch..todayEpoch }
    val monthIncome = sumTypeInPrimary(monthTxs, TransactionType.INCOME, primaryCode)
    val monthExpenses = sumTypeInPrimary(monthTxs, TransactionType.EXPENSE, primaryCode)
    val monthSavingsAlloc = sumTypeInPrimary(monthTxs, TransactionType.SAVINGS_ALLOCATION, primaryCode)

    val trailingTxs = realizedPrimaryTxs.filter { it.transactionDateEpochDay in trailing30StartEpoch..todayEpoch }
    val trailingIncome = sumTypeInPrimary(trailingTxs, TransactionType.INCOME, primaryCode)
    val trailingExpenses = sumTypeInPrimary(trailingTxs, TransactionType.EXPENSE, primaryCode)
    val trailingSavingsAlloc = sumTypeInPrimary(trailingTxs, TransactionType.SAVINGS_ALLOCATION, primaryCode)

    val useMonthWindow = monthIncome > BigDecimal.ZERO || trailingIncome <= BigDecimal.ZERO
    val windowLabel = if (useMonthWindow) "Current Month to Date" else "Trailing 30 Days"
    val windowIncome = DecimalUtils.scaleToCurrency(if (useMonthWindow) monthIncome else trailingIncome, precision)
    val windowExpenses = DecimalUtils.scaleToCurrency(if (useMonthWindow) monthExpenses else trailingExpenses, precision)
    val windowSavingsAlloc = DecimalUtils.scaleToCurrency(if (useMonthWindow) monthSavingsAlloc else trailingSavingsAlloc, precision)
    val netSurplus = DecimalUtils.scaleToCurrency(windowIncome.subtract(windowExpenses), precision)

    val monthlyEmiCommitment = DecimalUtils.scaleToCurrency(
      activePrimaryLoans.fold(BigDecimal.ZERO) { acc, loan ->
        acc.add(loan.emiAmount.coerceAtLeast(BigDecimal.ZERO))
      },
      precision,
    )

    val totalOutstandingDebt = activePrimaryLoans.fold(BigDecimal.ZERO) { acc, l ->
      acc.add(l.outstandingPrincipal.coerceAtLeast(BigDecimal.ZERO))
    }
    val weightedInterestApr = if (totalOutstandingDebt > BigDecimal.ZERO) {
      val weightedSum = activePrimaryLoans.fold(BigDecimal.ZERO) { acc, loan ->
        acc.add(loan.outstandingPrincipal.multiply(loan.annualInterestRatePercent.coerceAtLeast(BigDecimal.ZERO)))
      }
      weightedSum.divide(totalOutstandingDebt, 2, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    }
    val monthlyInterestCost = DecimalUtils.scaleToCurrency(
      activePrimaryLoans.fold(BigDecimal.ZERO) { acc, loan ->
        val monthlyRate = loan.annualInterestRatePercent
          .coerceAtLeast(BigDecimal.ZERO)
          .divide(HUNDRED, DecimalUtils.FINANCIAL_MATH_CONTEXT)
          .divide(TWELVE, DecimalUtils.FINANCIAL_MATH_CONTEXT)
        acc.add(loan.outstandingPrincipal.multiply(monthlyRate, DecimalUtils.FINANCIAL_MATH_CONTEXT))
      },
      precision,
    )

    val currentMonthBudgets = deduplicateBudgets(budgets).filter {
      it.yearMonth == currentMonth.toString() && it.currencyCode.equals(primaryCode, ignoreCase = true)
    }
    val budgetStatuses = budgetAndSavingsCalculator.computeBudgetStatuses(
      budgets = currentMonthBudgets,
      categories = categoriesById,
      transactions = dedupedTransactions.filter { it.isPaid && it.transactionDateEpochDay <= todayEpoch },
      currencyPrecisionMap = currencyPrecisionMap,
    )

    val hasMeaningfulCashflow = windowIncome > BigDecimal.ZERO || windowExpenses > BigDecimal.ZERO
    if (recentRealizedCount < MIN_REALIZED_TRANSACTIONS || !hasMeaningfulCashflow) {
      val zeroScaled = DecimalUtils.scaleToCurrency(BigDecimal.ZERO, precision)
      return FinancialHealthReport(
        status = FinancialHealthStatus.INSUFFICIENT_DATA,
        overallScore = null,
        band = null,
        summaryHeadline = "Insufficient Data",
        primaryExplanation = "At least $MIN_REALIZED_TRANSACTIONS paid transactions (including realized income or expenses up to today) in $primaryCode are required to compute a reliable Financial Health Score.",
        pillars = emptyList(),
        topReasons = listOf(
          "Only $recentRealizedCount paid $primaryCode transaction(s) recorded in the last 90 days.",
          "Future-dated entries and unpaid upcoming bills are intentionally excluded from realized health calculations.",
        ),
        priorityImprovements = listOf(
          "Record your realized income and daily expenses in $primaryCode.",
          "Add at least $MIN_REALIZED_TRANSACTIONS paid transactions to unlock your 0-100 score.",
        ),
        metrics = FinancialHealthMetrics(
          evaluatedCurrencyCode = primaryCode,
          evaluationWindowLabel = windowLabel,
          realizedTransactionCount = recentRealizedCount,
          realizedIncome = windowIncome,
          realizedExpenses = windowExpenses,
          netCashSurplus = netSurplus,
          savingsRatePercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
          monthlyEmiCommitment = monthlyEmiCommitment,
          emiToIncomePercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
          emergencyFundSaved = emergencySaved,
          emergencyMonthsCovered = zeroScaled.setScale(1, RoundingMode.HALF_UP),
          activeBudgetCount = budgetStatuses.size,
          exceededBudgetCount = budgetStatuses.count { it.status == BudgetHealthStatus.EXCEEDED },
          weightedInterestAprPercent = weightedInterestApr,
          monthlyInterestCost = monthlyInterestCost,
        ),
        scoreChangeExplanation = "Add at least $MIN_REALIZED_TRANSACTIONS realized transactions to unlock multi-dimensional financial wellness tracking.",
      )
    }

    // Core Ratios
    val savingsRatePercent = if (windowIncome > BigDecimal.ZERO) {
      netSurplus.multiply(HUNDRED, DecimalUtils.FINANCIAL_MATH_CONTEXT)
        .divide(windowIncome, 2, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    }
    val emiToIncomePercent = if (windowIncome > BigDecimal.ZERO && monthlyEmiCommitment > BigDecimal.ZERO) {
      monthlyEmiCommitment.multiply(HUNDRED, DecimalUtils.FINANCIAL_MATH_CONTEXT)
        .divide(windowIncome, 2, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    }

    val configuredEssential = settings?.monthlyEssentialExpenses ?: BigDecimal.ZERO
    val (essentialBaseline, usingEstimatedEssentials) = if (configuredEssential > BigDecimal.ZERO) {
      configuredEssential to false
    } else {
      windowExpenses to true
    }
    val monthsCovered = if (essentialBaseline > BigDecimal.ZERO) {
      emergencySaved.divide(essentialBaseline, 1, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
    }

    // Net Worth Snapshot vs Previous Month End
    val priorMonthEnd = currentMonth.minusMonths(1).atEndOfMonth()
    val netWorthNow = computeRealizedNetWorthAsOf(realizedPrimaryTxs, deduplicatedGoals, activePrimaryLoans, primaryCode, todayEpoch, precision)
    val hasPriorMonthHistory = realizedPrimaryTxs.any { it.transactionDateEpochDay <= priorMonthEnd.toEpochDay() }
    val netWorthPrior = if (hasPriorMonthHistory) {
      computeRealizedNetWorthAsOf(realizedPrimaryTxs, deduplicatedGoals, activePrimaryLoans, primaryCode, priorMonthEnd.toEpochDay(), precision)
    } else {
      netWorthNow
    }
    val netWorthDelta = DecimalUtils.scaleToCurrency(netWorthNow.subtract(netWorthPrior), precision)

    // Income Stability over trailing 3 calendar months (consistency only, never penalizes low income size)
    val last3Months = listOf(currentMonth.minusMonths(2), currentMonth.minusMonths(1), currentMonth)
    val monthlyIncomes = last3Months.map { ym ->
      val start = ym.atDay(1).toEpochDay()
      val end = minOf(ym.atEndOfMonth().toEpochDay(), todayEpoch)
      if (start > end) BigDecimal.ZERO
      else sumTypeInPrimary(
        realizedPrimaryTxs.filter { it.transactionDateEpochDay in start..end },
        TransactionType.INCOME,
        primaryCode,
      )
    }
    val activeIncomeMonthsLast3 = monthlyIncomes.count { it > BigDecimal.ZERO }

    // 4 Core Pillars (retaining 0..100 model)
    val cashflowPillar = scoreCashflowPillar(windowIncome, windowExpenses, savingsRatePercent, windowLabel)
    val debtPillar = scoreDebtPillar(activePrimaryLoans.size, monthlyEmiCommitment, windowIncome, emiToIncomePercent)
    val emergencyPillar = scoreEmergencyPillar(emergencySaved, essentialBaseline, monthsCovered, usingEstimatedEssentials)
    val budgetPillar = scoreBudgetPillar(budgetStatuses, windowIncome, windowExpenses)

    val pillars = listOf(cashflowPillar, debtPillar, emergencyPillar, budgetPillar)
    val totalScore = pillars.sumOf { it.score }.coerceIn(0, 100)
    val band = when {
      totalScore >= 80 -> HealthScoreBand.EXCELLENT
      totalScore >= 60 -> HealthScoreBand.GOOD
      totalScore >= 40 -> HealthScoreBand.FAIR
      else -> HealthScoreBand.CRITICAL
    }

    // All 8 Dimensions (0..100 each)
    val dimensions = evaluateEightDimensions(
      income = windowIncome,
      expenses = windowExpenses,
      netSurplus = netSurplus,
      savingsRatePercent = savingsRatePercent,
      windowSavingsAlloc = windowSavingsAlloc,
      windowLabel = windowLabel,
      emergencySaved = emergencySaved,
      essentialBaseline = essentialBaseline,
      monthsCovered = monthsCovered,
      usingEstimatedEssentials = usingEstimatedEssentials,
      activeLoans = activePrimaryLoans,
      monthlyEmiCommitment = monthlyEmiCommitment,
      emiToIncomePercent = emiToIncomePercent,
      weightedInterestApr = weightedInterestApr,
      monthlyInterestCost = monthlyInterestCost,
      budgetStatuses = budgetStatuses,
      hasPriorMonthHistory = hasPriorMonthHistory,
      netWorthNow = netWorthNow,
      netWorthPrior = netWorthPrior,
      netWorthDelta = netWorthDelta,
      monthlyIncomes = monthlyIncomes,
      activeIncomeMonthsLast3 = activeIncomeMonthsLast3,
      primaryCode = primaryCode,
    )

    // Positive & Negative Contributors
    val positiveContributors = dimensions
      .filter { it.status == DimensionImpactStatus.STRENGTH || (it.status == DimensionImpactStatus.BALANCED && it.score >= 70) }
      .sortedByDescending { it.score }
      .take(4)
      .map { dim ->
        HealthContributor(
          title = dim.title,
          impactSummary = "${dim.score}/100 • ${dim.metricSummary}",
          explanation = dim.explanation,
          isPositive = true,
        )
      }

    val negativeContributors = dimensions
      .filter { it.status == DimensionImpactStatus.NEEDS_ATTENTION }
      .sortedBy { it.score }
      .take(4)
      .map { dim ->
        HealthContributor(
          title = dim.title,
          impactSummary = "${dim.score}/100 • ${dim.metricSummary}",
          explanation = dim.explanation,
          isPositive = false,
        )
      }

    // Score History & Delta Explanation (across up to 3 previous months + current month)
    val historyPoints = if (computeHistory) {
      buildScoreHistory(
        realizedPrimaryTxs = realizedPrimaryTxs,
        budgets = budgets,
        categoriesById = categoriesById,
        loans = deduplicatedLoans,
        goals = deduplicatedGoals,
        settings = settings,
        primaryCode = primaryCode,
        precisionMap = currencyPrecisionMap,
        currentMonth = currentMonth,
        currentScore = totalScore,
        currentSavingsRate = savingsRatePercent,
        currentNetWorth = netWorthNow,
        today = today,
      )
    } else {
      emptyList()
    }

    val previousPoint = historyPoints.dropLast(1).lastOrNull()
    val scoreChangeDelta = previousPoint?.let { totalScore - it.score }
    val scoreChangeExplanation = buildScoreChangeExplanation(
      currentScore = totalScore,
      previousPoint = previousPoint,
      delta = scoreChangeDelta,
      positiveContributors = positiveContributors,
      negativeContributors = negativeContributors,
    )

    val practicalActions = buildPracticalActions(
      dimensions = dimensions,
      income = windowIncome,
      expenses = windowExpenses,
      netSurplus = netSurplus,
      essentialBaseline = essentialBaseline,
      emergencySaved = emergencySaved,
      activeLoans = activePrimaryLoans,
      budgetStatuses = budgetStatuses,
      primaryCode = primaryCode,
      precision = precision,
    )

    val topReasons = pillars
      .sortedByDescending { it.score.toDouble() / it.maxScore.toDouble() }
      .take(2)
      .map { "${it.title} (${it.score}/${it.maxScore}): ${it.reason}" }

    val priorityImprovements = practicalActions
      .take(3)
      .map { "${it.dimensionTitle}: ${it.actionDetail}" }
      .ifEmpty {
        pillars
          .filter { it.score < it.maxScore }
          .sortedBy { it.score.toDouble() / it.maxScore.toDouble() }
          .take(3)
          .map { "${it.title}: ${it.improvementTip}" }
      }
      .ifEmpty {
        listOf("Maintain your current savings surplus, debt-free/low-EMI profile, emergency reserve, and budget discipline.")
      }

    return FinancialHealthReport(
      status = FinancialHealthStatus.COMPUTED,
      overallScore = totalScore,
      band = band,
      summaryHeadline = "${band.label} ($totalScore/100)",
      primaryExplanation = "Evaluated across $recentRealizedCount realized $primaryCode transactions and 8 financial wellness dimensions using $windowLabel data.",
      pillars = pillars,
      topReasons = topReasons,
      priorityImprovements = priorityImprovements,
      metrics = FinancialHealthMetrics(
        evaluatedCurrencyCode = primaryCode,
        evaluationWindowLabel = windowLabel,
        realizedTransactionCount = recentRealizedCount,
        realizedIncome = windowIncome,
        realizedExpenses = windowExpenses,
        netCashSurplus = netSurplus,
        savingsRatePercent = savingsRatePercent,
        monthlyEmiCommitment = monthlyEmiCommitment,
        emiToIncomePercent = emiToIncomePercent,
        emergencyFundSaved = emergencySaved,
        emergencyMonthsCovered = monthsCovered,
        activeBudgetCount = budgetStatuses.size,
        exceededBudgetCount = budgetStatuses.count { it.status == BudgetHealthStatus.EXCEEDED },
        weightedInterestAprPercent = weightedInterestApr,
        monthlyInterestCost = monthlyInterestCost,
        netWorthDelta = netWorthDelta,
        activeIncomeMonthsLast3 = activeIncomeMonthsLast3,
      ),
      dimensions = dimensions,
      positiveContributors = positiveContributors,
      negativeContributors = negativeContributors,
      scoreHistory = historyPoints,
      previousScore = previousPoint?.score,
      scoreChangeDelta = scoreChangeDelta,
      scoreChangeExplanation = scoreChangeExplanation,
      practicalActions = practicalActions,
    )
  }

  private fun evaluateEightDimensions(
    income: BigDecimal,
    expenses: BigDecimal,
    netSurplus: BigDecimal,
    savingsRatePercent: BigDecimal,
    windowSavingsAlloc: BigDecimal,
    windowLabel: String,
    emergencySaved: BigDecimal,
    essentialBaseline: BigDecimal,
    monthsCovered: BigDecimal,
    usingEstimatedEssentials: Boolean,
    activeLoans: List<LoanEntity>,
    monthlyEmiCommitment: BigDecimal,
    emiToIncomePercent: BigDecimal,
    weightedInterestApr: BigDecimal,
    monthlyInterestCost: BigDecimal,
    budgetStatuses: List<BudgetStatusItem>,
    hasPriorMonthHistory: Boolean,
    netWorthNow: BigDecimal,
    netWorthPrior: BigDecimal,
    netWorthDelta: BigDecimal,
    monthlyIncomes: List<BigDecimal>,
    activeIncomeMonthsLast3: Int,
    primaryCode: String,
  ): List<HealthDimensionScore> {
    // 1. Cash Flow Health
    val cashflowScore = when {
      income <= BigDecimal.ZERO && expenses <= BigDecimal.ZERO -> 50
      income <= BigDecimal.ZERO -> 0
      netSurplus <= BigDecimal.ZERO -> 10
      else -> linearScale(savingsRatePercent, BigDecimal.ZERO, BigDecimal("25"), 45, 100)
    }
    val cashflowDim = HealthDimensionScore(
      dimension = HealthDimensionType.CASH_FLOW_HEALTH,
      title = HealthDimensionType.CASH_FLOW_HEALTH.title,
      score = cashflowScore,
      status = statusForScore(cashflowScore),
      metricSummary = if (income > BigDecimal.ZERO) "Surplus: ${netSurplus.stripTrailingZeros().toPlainString()} $primaryCode" else "No income in $windowLabel",
      explanation = when {
        income <= BigDecimal.ZERO && expenses > BigDecimal.ZERO -> "Expenses were recorded without realized income in $windowLabel."
        netSurplus > BigDecimal.ZERO -> "Positive net cashflow retained after realized expenses in $windowLabel."
        else -> "Outflows matched or exceeded realized income in $windowLabel."
      },
    )

    // 2. Savings Rate (strictly ratio-based — never penalizes modest income size)
    val effectiveSavingsRatio = if (income > BigDecimal.ZERO) {
      val totalSaved = maxOf(netSurplus, windowSavingsAlloc)
      totalSaved.multiply(HUNDRED, DecimalUtils.FINANCIAL_MATH_CONTEXT).divide(income, 2, RoundingMode.HALF_UP)
    } else {
      BigDecimal.ZERO
    }
    val savingsScore = when {
      income <= BigDecimal.ZERO -> if (windowSavingsAlloc > BigDecimal.ZERO) 60 else 0
      effectiveSavingsRatio <= BigDecimal.ZERO -> 0
      effectiveSavingsRatio < BigDecimal("20") -> linearScale(effectiveSavingsRatio, BigDecimal.ZERO, BigDecimal("20"), 0, 78)
      effectiveSavingsRatio < BigDecimal("30") -> linearScale(effectiveSavingsRatio, BigDecimal("20"), BigDecimal("30"), 78, 100)
      else -> 100
    }
    val savingsDim = HealthDimensionScore(
      dimension = HealthDimensionType.SAVINGS_RATE,
      title = HealthDimensionType.SAVINGS_RATE.title,
      score = savingsScore,
      status = statusForScore(savingsScore),
      metricSummary = "${effectiveSavingsRatio.setScale(1, RoundingMode.HALF_UP)}% saved",
      explanation = when {
        effectiveSavingsRatio >= BigDecimal("20") -> "Strong savings rate relative to your income (target: 20%+)."
        effectiveSavingsRatio > BigDecimal.ZERO -> "Positive savings rate; building toward 20% strengthens long-term resilience."
        else -> "No net savings retained in $windowLabel."
      },
    )

    // 3. Emergency Fund
    val emergencyScore = when {
      emergencySaved <= BigDecimal.ZERO -> 0
      essentialBaseline <= BigDecimal.ZERO -> 60
      monthsCovered >= BigDecimal("6.0") -> 100
      monthsCovered >= BigDecimal("3.0") -> linearScale(monthsCovered, BigDecimal("3.0"), BigDecimal("6.0"), 72, 100)
      monthsCovered >= BigDecimal("1.0") -> linearScale(monthsCovered, BigDecimal.ONE, BigDecimal("3.0"), 32, 72)
      else -> linearScale(monthsCovered, BigDecimal.ZERO, BigDecimal.ONE, 0, 32)
    }
    val baselineNote = if (usingEstimatedEssentials) " (est.)" else ""
    val emergencyDim = HealthDimensionScore(
      dimension = HealthDimensionType.EMERGENCY_FUND,
      title = HealthDimensionType.EMERGENCY_FUND.title,
      score = emergencyScore,
      status = statusForScore(emergencyScore),
      metricSummary = "${monthsCovered.toPlainString()} mo$baselineNote",
      explanation = when {
        emergencySaved <= BigDecimal.ZERO -> "No emergency fund reserve recorded yet."
        monthsCovered >= BigDecimal("6.0") -> "Full 6+ month emergency cushion protects against income or medical shocks."
        monthsCovered >= BigDecimal("3.0") -> "Solid 3+ month reserve; full goal is 6 months."
        else -> "Emergency reserve is under 3 months of essential expenses."
      },
    )

    // 4. Debt & EMI Management (100 for debt-free!)
    val debtScore = when {
      activeLoans.isEmpty() || monthlyEmiCommitment <= BigDecimal.ZERO -> 100
      income <= BigDecimal.ZERO -> 0
      emiToIncomePercent <= BigDecimal("15") -> 100
      emiToIncomePercent <= BigDecimal("30") -> linearScale(emiToIncomePercent, BigDecimal("15"), BigDecimal("30"), 100, 72)
      emiToIncomePercent <= BigDecimal("45") -> linearScale(emiToIncomePercent, BigDecimal("30"), BigDecimal("45"), 72, 32)
      emiToIncomePercent <= BigDecimal("60") -> linearScale(emiToIncomePercent, BigDecimal("45"), BigDecimal("60"), 32, 8)
      else -> 0
    }
    val debtDim = HealthDimensionScore(
      dimension = HealthDimensionType.DEBT_AND_EMI,
      title = HealthDimensionType.DEBT_AND_EMI.title,
      score = debtScore,
      status = statusForScore(debtScore),
      metricSummary = if (activeLoans.isEmpty()) "Debt-free" else "EMI ${emiToIncomePercent.toPlainString()}% of income",
      explanation = if (activeLoans.isEmpty()) {
        "Debt-free with zero monthly EMI commitments."
      } else if (emiToIncomePercent <= BigDecimal("30")) {
        "Monthly EMIs remain within a manageable share of realized income."
      } else {
        "High EMI-to-income ratio restricts monthly cashflow flexibility."
      },
    )

    // 5. Interest Burden (100 for debt-free!)
    val interestScore = when {
      activeLoans.isEmpty() -> 100
      weightedInterestApr <= BigDecimal("7") -> 95
      weightedInterestApr <= BigDecimal("12") -> linearScale(weightedInterestApr, BigDecimal("7"), BigDecimal("12"), 95, 72)
      weightedInterestApr <= BigDecimal("18") -> linearScale(weightedInterestApr, BigDecimal("12"), BigDecimal("18"), 72, 38)
      else -> linearScale(weightedInterestApr, BigDecimal("18"), BigDecimal("36"), 38, 10)
    }
    val interestDim = HealthDimensionScore(
      dimension = HealthDimensionType.INTEREST_BURDEN,
      title = HealthDimensionType.INTEREST_BURDEN.title,
      score = interestScore,
      status = statusForScore(interestScore),
      metricSummary = if (activeLoans.isEmpty()) "0.00% APR" else "${weightedInterestApr.toPlainString()}% APR (${monthlyInterestCost.toPlainString()}/mo)",
      explanation = when {
        activeLoans.isEmpty() -> "No interest cost dragging down monthly wealth growth."
        weightedInterestApr <= BigDecimal("10") -> "Weighted interest rate is moderate relative to consumer debt."
        else -> "High weighted APR (${weightedInterestApr.toPlainString()}%) increases total borrowing cost."
      },
    )

    // 6. Spending Discipline
    val disciplineScore = if (budgetStatuses.isEmpty()) {
      if (income > BigDecimal.ZERO && expenses <= income) 55 else 20
    } else {
      val pillar = scoreBudgetPillar(budgetStatuses, income, expenses)
      (pillar.score * 100) / pillar.maxScore
    }
    val disciplineDim = HealthDimensionScore(
      dimension = HealthDimensionType.SPENDING_DISCIPLINE,
      title = HealthDimensionType.SPENDING_DISCIPLINE.title,
      score = disciplineScore,
      status = statusForScore(disciplineScore),
      metricSummary = if (budgetStatuses.isEmpty()) "No active budgets" else "${budgetStatuses.count { it.status != BudgetHealthStatus.EXCEEDED }}/${budgetStatuses.size} on track",
      explanation = when {
        budgetStatuses.isEmpty() -> "Set monthly category budgets to tighten spending guardrails."
        budgetStatuses.all { it.status == BudgetHealthStatus.NORMAL } -> "All category budgets remained safely below warning limits."
        budgetStatuses.any { it.status == BudgetHealthStatus.EXCEEDED } -> "One or more monthly budgets exceeded their cap."
        else -> "Spending is within caps, though some categories are nearing thresholds."
      },
    )

    // 7. Net Worth Trend (baseline-forming if <2 months history, never penalizes new users)
    val (nwScore, nwStatus, nwSummary, nwExpl) = if (!hasPriorMonthHistory) {
      Quad(70, DimensionImpactStatus.BASELINE_FORMING, "Baseline forming", "Need prior-month transactions to measure multi-month net worth change.")
    } else {
      val baseAbs = netWorthPrior.abs().coerceAtLeast(BigDecimal.ONE)
      val changePct = netWorthDelta.multiply(HUNDRED, DecimalUtils.FINANCIAL_MATH_CONTEXT).divide(baseAbs, 1, RoundingMode.HALF_UP)
      val score = when {
        netWorthDelta > BigDecimal.ZERO -> linearScale(changePct.coerceAtMost(BigDecimal("15")), BigDecimal.ZERO, BigDecimal("15"), 78, 100)
        netWorthDelta.compareTo(BigDecimal.ZERO) == 0 -> 70
        else -> linearScale(changePct.abs().coerceAtMost(BigDecimal("15")), BigDecimal.ZERO, BigDecimal("15"), 68, 20)
      }
      val sign = if (netWorthDelta >= BigDecimal.ZERO) "+" else ""
      Quad(
        score,
        statusForScore(score),
        "$sign${netWorthDelta.toPlainString()} $primaryCode vs last mo",
        if (netWorthDelta >= BigDecimal.ZERO) {
          "Net worth improved or held steady compared with the end of last month."
        } else {
          "Net worth declined vs the end of last month due to outflows or debt."
        },
      )
    }
    val netWorthDim = HealthDimensionScore(
      dimension = HealthDimensionType.NET_WORTH_TREND,
      title = HealthDimensionType.NET_WORTH_TREND.title,
      score = nwScore,
      status = nwStatus,
      metricSummary = nwSummary,
      explanation = nwExpl,
    )

    // 8. Income Stability (regularity & low volatility across 3 months; NEVER penalizes low income size)
    val nonZeroIncomes = monthlyIncomes.filter { it > BigDecimal.ZERO }
    val (incScore, incStatus, incSummary, incExpl) = when {
      activeIncomeMonthsLast3 == 0 -> Quad(
        0,
        DimensionImpactStatus.NEEDS_ATTENTION,
        "0/3 months with income",
        "No realized income transactions logged in the last 3 months.",
      )
      activeIncomeMonthsLast3 == 1 && !hasPriorMonthHistory -> Quad(
        75,
        DimensionImpactStatus.BASELINE_FORMING,
        "1/3 months (baseline)",
        "Current month income recorded; multi-month consistency unlocks as history grows.",
      )
      else -> {
        val avg = nonZeroIncomes.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
          .divide(BigDecimal(nonZeroIncomes.size), 4, RoundingMode.HALF_UP)
          .toDouble()
        val variance = if (nonZeroIncomes.size >= 2 && avg > 0.0) {
          val meanSq = nonZeroIncomes.sumOf {
            val diff = it.toDouble() - avg
            diff * diff
          } / nonZeroIncomes.size.toDouble()
          (sqrt(meanSq) / avg).coerceIn(0.0, 1.0)
        } else {
          0.0
        }
        val baseCoverage = when (activeIncomeMonthsLast3) {
          3 -> 100
          2 -> 78
          else -> 52
        }
        val stabilityPenalty = (variance * 30.0).toInt().coerceIn(0, 25)
        val finalIncScore = (baseCoverage - stabilityPenalty).coerceIn(20, 100)
        Quad(
          finalIncScore,
          statusForScore(finalIncScore),
          "$activeIncomeMonthsLast3/3 active months",
          "Evaluated strictly by monthly income regularity across trailing 3 months (independent of income size).",
        )
      }
    }
    val incomeStabilityDim = HealthDimensionScore(
      dimension = HealthDimensionType.INCOME_STABILITY,
      title = HealthDimensionType.INCOME_STABILITY.title,
      score = incScore,
      status = incStatus,
      metricSummary = incSummary,
      explanation = incExpl,
    )

    return listOf(
      cashflowDim,
      savingsDim,
      emergencyDim,
      debtDim,
      interestDim,
      disciplineDim,
      netWorthDim,
      incomeStabilityDim,
    )
  }

  private fun buildScoreHistory(
    realizedPrimaryTxs: List<TransactionEntity>,
    budgets: List<BudgetEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    loans: List<LoanEntity>,
    goals: List<SavingsGoalEntity>,
    settings: AppSettingsEntity?,
    primaryCode: String,
    precisionMap: Map<String, Int>,
    currentMonth: YearMonth,
    currentScore: Int,
    currentSavingsRate: BigDecimal,
    currentNetWorth: BigDecimal,
    today: LocalDate,
  ): List<HealthScoreHistoryPoint> {
    val points = mutableListOf<HealthScoreHistoryPoint>()
    val precision = precisionMap[primaryCode] ?: 2
    for (offset in 3L downTo 1L) {
      val histMonth = currentMonth.minusMonths(offset)
      val endDay = histMonth.atEndOfMonth()
      val txsAsOf = realizedPrimaryTxs.filter { it.transactionDateEpochDay <= endDay.toEpochDay() }
      if (txsAsOf.size < MIN_REALIZED_TRANSACTIONS) continue

      val histReport = evaluateInternal(
        transactions = txsAsOf,
        budgets = budgets,
        categoriesById = categoriesById,
        loans = loans,
        savingsGoals = goals,
        settings = settings,
        primaryCurrencyCode = primaryCode,
        currencyPrecisionMap = precisionMap,
        today = endDay,
        computeHistory = false,
      )
      val score = histReport.overallScore ?: continue
      val nw = computeRealizedNetWorthAsOf(txsAsOf, goals, loans, primaryCode, endDay.toEpochDay(), precision)
      points.add(
        HealthScoreHistoryPoint(
          monthLabel = histMonth.format(MONTH_LABEL_FMT),
          yearMonth = histMonth.toString(),
          score = score,
          savingsRatePercent = histReport.metrics.savingsRatePercent,
          netWorth = nw,
        ),
      )
    }

    points.add(
      HealthScoreHistoryPoint(
        monthLabel = currentMonth.format(MONTH_LABEL_FMT),
        yearMonth = currentMonth.toString(),
        score = currentScore,
        savingsRatePercent = currentSavingsRate,
        netWorth = currentNetWorth,
      ),
    )
    return points
  }

  private fun buildScoreChangeExplanation(
    currentScore: Int,
    previousPoint: HealthScoreHistoryPoint?,
    delta: Int?,
    positiveContributors: List<HealthContributor>,
    negativeContributors: List<HealthContributor>,
  ): String {
    if (previousPoint == null || delta == null) {
      return "First evaluated month ($currentScore/100). Future months will track your score trend and drivers automatically."
    }
    return when {
      delta > 0 -> {
        val driver = positiveContributors.firstOrNull()?.title ?: "overall financial balance"
        "Up +$delta pts since ${previousPoint.monthLabel} (${previousPoint.score} → $currentScore), led by $driver."
      }
      delta < 0 -> {
        val drag = negativeContributors.firstOrNull()?.title ?: "higher outflows"
        "Down $delta pts since ${previousPoint.monthLabel} (${previousPoint.score} → $currentScore), mainly due to $drag."
      }
      else -> {
        "Unchanged at $currentScore/100 since ${previousPoint.monthLabel}."
      }
    }
  }

  private fun buildPracticalActions(
    dimensions: List<HealthDimensionScore>,
    income: BigDecimal,
    expenses: BigDecimal,
    netSurplus: BigDecimal,
    essentialBaseline: BigDecimal,
    emergencySaved: BigDecimal,
    activeLoans: List<LoanEntity>,
    budgetStatuses: List<BudgetStatusItem>,
    primaryCode: String,
    precision: Int,
  ): List<HealthActionItem> {
    val actions = mutableListOf<HealthActionItem>()

    val exceeded = budgetStatuses.filter { it.status == BudgetHealthStatus.EXCEEDED }
    if (exceeded.isNotEmpty()) {
      val top = exceeded.maxByOrNull { it.utilizationPercent }!!
      val overrun = DecimalUtils.scaleToCurrency(top.actualSpent.subtract(top.effectiveLimit).coerceAtLeast(BigDecimal.ZERO), precision)
      actions.add(
        HealthActionItem(
          dimensionTitle = HealthDimensionType.SPENDING_DISCIPLINE.title,
          actionTitle = "Cap ${top.categoryNameEn} Spending",
          actionDetail = "Trim ${top.categoryNameEn} by ${overrun.toPlainString()} $primaryCode to return within your monthly budget.",
        ),
      )
    } else if (budgetStatuses.isEmpty()) {
      actions.add(
        HealthActionItem(
          dimensionTitle = HealthDimensionType.SPENDING_DISCIPLINE.title,
          actionTitle = "Set Monthly Category Budgets",
          actionDetail = "Create 2–3 core category budgets (e.g., Food, Bills) in Plan → Budgets for clearer spending guardrails.",
        ),
      )
    }

    val highestAprLoan = activeLoans.maxByOrNull { it.annualInterestRatePercent }
    if (highestAprLoan != null && highestAprLoan.annualInterestRatePercent >= BigDecimal("12")) {
      actions.add(
        HealthActionItem(
          dimensionTitle = HealthDimensionType.INTEREST_BURDEN.title,
          actionTitle = "Accelerate ${highestAprLoan.loanName}",
          actionDetail = "Prioritize extra principal prepayments on ${highestAprLoan.loanName} (${highestAprLoan.annualInterestRatePercent.toPlainString()}% APR) using Avalanche payoff.",
        ),
      )
    }

    if (essentialBaseline > BigDecimal.ZERO) {
      val target3Mo = DecimalUtils.scaleToCurrency(essentialBaseline.multiply(BigDecimal("3")), precision)
      if (emergencySaved < target3Mo) {
        val gap = DecimalUtils.scaleToCurrency(target3Mo.subtract(emergencySaved), precision)
        actions.add(
          HealthActionItem(
            dimensionTitle = HealthDimensionType.EMERGENCY_FUND.title,
            actionTitle = "Build 3-Month Safety Cushion",
            actionDetail = "Add ${gap.toPlainString()} $primaryCode toward your Emergency Fund goal to reach 3 months of essential coverage.",
          ),
        )
      }
    }

    val savingsDim = dimensions.firstOrNull { it.dimension == HealthDimensionType.SAVINGS_RATE }
    if (savingsDim != null && savingsDim.score < 78 && income > BigDecimal.ZERO) {
      val target20 = DecimalUtils.scaleToCurrency(income.multiply(BigDecimal("0.20")), precision)
      val shortfall = DecimalUtils.scaleToCurrency(target20.subtract(netSurplus.coerceAtLeast(BigDecimal.ZERO)).coerceAtLeast(BigDecimal.ZERO), precision)
      if (shortfall > BigDecimal.ZERO) {
        actions.add(
          HealthActionItem(
            dimensionTitle = HealthDimensionType.SAVINGS_RATE.title,
            actionTitle = "Lift Monthly Savings Rate",
            actionDetail = "Free up ${shortfall.toPlainString()} $primaryCode in discretionary spending or allocate it to Savings to hit a 20% savings rate.",
          ),
        )
      }
    }

    if (actions.isEmpty() && expenses > BigDecimal.ZERO) {
      actions.add(
        HealthActionItem(
          dimensionTitle = HealthDimensionType.CASH_FLOW_HEALTH.title,
          actionTitle = "Sustain Healthy Cashflow Habits",
          actionDetail = "Continue directing surplus cash into your Emergency Fund and goal targets.",
        ),
      )
    }
    return actions.take(4)
  }

  private fun computeRealizedNetWorthAsOf(
    realizedPrimaryTxs: List<TransactionEntity>,
    goals: List<SavingsGoalEntity>,
    loans: List<LoanEntity>,
    primaryCode: String,
    asOfEpochDay: Long,
    precision: Int,
  ): BigDecimal {
    var cash = BigDecimal.ZERO
    for (tx in realizedPrimaryTxs) {
      if (tx.transactionDateEpochDay > asOfEpochDay) continue
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      cash = when (tx.type) {
        TransactionType.INCOME -> cash.add(amt)
        TransactionType.EXPENSE -> cash.subtract(amt)
        TransactionType.SAVINGS_ALLOCATION -> cash
      }
    }
    val goalAssets = goals
      .filter { it.currencyCode.equals(primaryCode, ignoreCase = true) && it.createdAtEpochMillis > 0L }
      .fold(BigDecimal.ZERO) { acc, g -> acc.add(g.currentSavedAmount.coerceAtLeast(BigDecimal.ZERO)) }
    val debt = loans
      .filter { it.status == LoanStatus.ACTIVE && it.currencyCode.equals(primaryCode, ignoreCase = true) && it.startDateEpochDay <= asOfEpochDay }
      .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.outstandingPrincipal.coerceAtLeast(BigDecimal.ZERO)) }
    return DecimalUtils.scaleToCurrency(cash.add(goalAssets).subtract(debt), precision)
  }

  private fun statusForScore(score: Int): DimensionImpactStatus = when {
    score >= 80 -> DimensionImpactStatus.STRENGTH
    score >= 55 -> DimensionImpactStatus.BALANCED
    else -> DimensionImpactStatus.NEEDS_ATTENTION
  }

  private fun scoreCashflowPillar(
    income: BigDecimal,
    expenses: BigDecimal,
    savingsRatePercent: BigDecimal,
    windowLabel: String,
  ): HealthPillarScore {
    val max = HealthPillarType.CASHFLOW_SAVINGS.maxScore
    if (income <= BigDecimal.ZERO) {
      val score = if (expenses <= BigDecimal.ZERO) 15 else 0
      return HealthPillarScore(
        pillar = HealthPillarType.CASHFLOW_SAVINGS,
        title = HealthPillarType.CASHFLOW_SAVINGS.title,
        score = score,
        maxScore = max,
        metricSummary = "No realized income in $windowLabel",
        reason = if (expenses > BigDecimal.ZERO) {
          "Expenses were recorded in $windowLabel without any realized income yet."
        } else {
          "No realized income or expense deficit in $windowLabel."
        },
        improvementTip = "Log realized income transactions when received and keep monthly expenses below your income.",
      )
    }

    val score = when {
      savingsRatePercent <= BigDecimal.ZERO -> 0
      savingsRatePercent < BigDecimal("20") -> {
        linearScale(savingsRatePercent, BigDecimal.ZERO, BigDecimal("20"), 0, 24)
      }
      savingsRatePercent < BigDecimal("30") -> {
        linearScale(savingsRatePercent, BigDecimal("20"), BigDecimal("30"), 24, 30)
      }
      else -> 30
    }.coerceIn(0, max)

    val reason = when {
      savingsRatePercent >= BigDecimal("30") -> "Strong ${savingsRatePercent.toPlainString()}% cashflow surplus retained in $windowLabel."
      savingsRatePercent >= BigDecimal("20") -> "Healthy ${savingsRatePercent.toPlainString()}% cashflow surplus in $windowLabel."
      savingsRatePercent > BigDecimal.ZERO -> "Modest ${savingsRatePercent.toPlainString()}% cashflow surplus; target is 20%+."
      else -> "Expenses met or exceeded realized income in $windowLabel."
    }

    val tip = when {
      savingsRatePercent >= BigDecimal("30") -> "Keep channeling surplus into long-term goals or debt prepayment."
      savingsRatePercent > BigDecimal.ZERO -> "Trim discretionary categories or allocate extra savings to push surplus above 20–30%."
      else -> "Reduce non-essential spending immediately so monthly outflows stay below realized income."
    }

    return HealthPillarScore(
      pillar = HealthPillarType.CASHFLOW_SAVINGS,
      title = HealthPillarType.CASHFLOW_SAVINGS.title,
      score = score,
      maxScore = max,
      metricSummary = "Savings Rate: ${savingsRatePercent.toPlainString()}%",
      reason = reason,
      improvementTip = tip,
    )
  }

  private fun scoreDebtPillar(
    activeLoanCount: Int,
    monthlyEmi: BigDecimal,
    income: BigDecimal,
    emiToIncomePercent: BigDecimal,
  ): HealthPillarScore {
    val max = HealthPillarType.DEBT_BURDEN.maxScore
    if (activeLoanCount == 0 || monthlyEmi <= BigDecimal.ZERO) {
      return HealthPillarScore(
        pillar = HealthPillarType.DEBT_BURDEN,
        title = HealthPillarType.DEBT_BURDEN.title,
        score = max,
        maxScore = max,
        metricSummary = "Debt-free (0 active loans)",
        reason = "No active loan balances or monthly EMI obligations.",
        improvementTip = "Continue avoiding high-interest debt.",
      )
    }

    if (income <= BigDecimal.ZERO) {
      return HealthPillarScore(
        pillar = HealthPillarType.DEBT_BURDEN,
        title = HealthPillarType.DEBT_BURDEN.title,
        score = 0,
        maxScore = max,
        metricSummary = "$activeLoanCount active loan(s) • No realized income",
        reason = "Active EMI commitments exist with zero realized income in the evaluated window.",
        improvementTip = "Verify realized income is logged and prioritize building an EMI payment buffer.",
      )
    }

    val score = when {
      emiToIncomePercent <= BigDecimal("15") -> 25
      emiToIncomePercent <= BigDecimal("30") -> linearScale(emiToIncomePercent, BigDecimal("15"), BigDecimal("30"), 25, 18)
      emiToIncomePercent <= BigDecimal("45") -> linearScale(emiToIncomePercent, BigDecimal("30"), BigDecimal("45"), 18, 8)
      emiToIncomePercent <= BigDecimal("60") -> linearScale(emiToIncomePercent, BigDecimal("45"), BigDecimal("60"), 8, 2)
      else -> 0
    }.coerceIn(0, max)

    val reason = when {
      emiToIncomePercent <= BigDecimal("15") -> "Very manageable EMI burden (${emiToIncomePercent.toPlainString()}% of income)."
      emiToIncomePercent <= BigDecimal("30") -> "Moderate EMI burden (${emiToIncomePercent.toPlainString()}% of income) across $activeLoanCount loan(s)."
      emiToIncomePercent <= BigDecimal("45") -> "Elevated EMI burden (${emiToIncomePercent.toPlainString()}% of income); limits monthly flexibility."
      else -> "Heavy EMI burden (${emiToIncomePercent.toPlainString()}% of income) creates high cashflow pressure."
    }

    val tip = when {
      emiToIncomePercent <= BigDecimal("15") -> "Consider small principal prepayments on your highest-interest loan to save interest."
      emiToIncomePercent <= BigDecimal("30") -> "Use Avalanche prepayment on the highest-rate loan to bring EMI ratio below 15%."
      else -> "Prioritize paying down high-interest debt and avoid new EMIs until EMI-to-income drops below 30%."
    }

    return HealthPillarScore(
      pillar = HealthPillarType.DEBT_BURDEN,
      title = HealthPillarType.DEBT_BURDEN.title,
      score = score,
      maxScore = max,
      metricSummary = "EMI/Income: ${emiToIncomePercent.toPlainString()}%",
      reason = reason,
      improvementTip = tip,
    )
  }

  private fun scoreEmergencyPillar(
    emergencySaved: BigDecimal,
    essentialBaseline: BigDecimal,
    monthsCovered: BigDecimal,
    usingEstimatedEssentials: Boolean,
  ): HealthPillarScore {
    val max = HealthPillarType.EMERGENCY_FUND.maxScore
    if (emergencySaved <= BigDecimal.ZERO) {
      return HealthPillarScore(
        pillar = HealthPillarType.EMERGENCY_FUND,
        title = HealthPillarType.EMERGENCY_FUND.title,
        score = 0,
        maxScore = max,
        metricSummary = "0.0 months covered",
        reason = "No emergency reserve savings goal balance recorded yet.",
        improvementTip = "Create an Emergency Fund goal and build toward at least 3-6 months of essential expenses.",
      )
    }

    if (essentialBaseline <= BigDecimal.ZERO) {
      return HealthPillarScore(
        pillar = HealthPillarType.EMERGENCY_FUND,
        title = HealthPillarType.EMERGENCY_FUND.title,
        score = 15,
        maxScore = max,
        metricSummary = "Reserve active • Baseline unset",
        reason = "Emergency reserve exists, but monthly essential expenses are not set yet.",
        improvementTip = "Set your monthly essential expenses in Safe-to-Spend configuration to compute exact months of coverage.",
      )
    }

    val score = when {
      monthsCovered >= BigDecimal("6.0") -> 25
      monthsCovered >= BigDecimal("3.0") -> linearScale(monthsCovered, BigDecimal("3.0"), BigDecimal("6.0"), 18, 25)
      monthsCovered >= BigDecimal("1.0") -> linearScale(monthsCovered, BigDecimal("1.0"), BigDecimal("3.0"), 8, 18)
      else -> linearScale(monthsCovered, BigDecimal.ZERO, BigDecimal("1.0"), 0, 8)
    }.coerceIn(0, max)

    val baselineNote = if (usingEstimatedEssentials) " (estimated from current spending)" else ""
    val reason = when {
      monthsCovered >= BigDecimal("6.0") -> "Strong safety net covering ${monthsCovered.toPlainString()} months of essentials$baselineNote."
      monthsCovered >= BigDecimal("3.0") -> "Solid reserve covering ${monthsCovered.toPlainString()} months$baselineNote; full target is 6 months."
      else -> "Emergency reserve covers ${monthsCovered.toPlainString()} months$baselineNote, below the 3-month minimum safety target."
    }

    val tip = when {
      monthsCovered >= BigDecimal("6.0") -> "Your 6-month emergency cushion is complete; surplus can target other goals."
      monthsCovered >= BigDecimal("3.0") -> "Automate monthly contributions to expand your reserve from ${monthsCovered.toPlainString()} to 6.0 months."
      else -> "Prioritize growing your Emergency Fund to at least 3.0 months of essential expenses."
    }

    return HealthPillarScore(
      pillar = HealthPillarType.EMERGENCY_FUND,
      title = HealthPillarType.EMERGENCY_FUND.title,
      score = score,
      maxScore = max,
      metricSummary = "${monthsCovered.toPlainString()} mo covered",
      reason = reason,
      improvementTip = tip,
    )
  }

  private fun scoreBudgetPillar(
    budgetStatuses: List<BudgetStatusItem>,
    income: BigDecimal,
    expenses: BigDecimal,
  ): HealthPillarScore {
    val max = HealthPillarType.BUDGET_DISCIPLINE.maxScore
    if (budgetStatuses.isEmpty()) {
      val spendingControlled = income > BigDecimal.ZERO && expenses <= income
      val score = if (spendingControlled) 10 else 4
      return HealthPillarScore(
        pillar = HealthPillarType.BUDGET_DISCIPLINE,
        title = HealthPillarType.BUDGET_DISCIPLINE.title,
        score = score,
        maxScore = max,
        metricSummary = "No monthly budgets configured",
        reason = if (spendingControlled) {
          "Spending remained within realized income, but no category budgets are configured."
        } else {
          "No category budgets are configured and expenses currently exceed realized income."
        },
        improvementTip = "Set monthly category budgets in Plan → Budgets to track spending limits and earn up to 20 points.",
      )
    }

    var weightSum = BigDecimal.ZERO
    var normalCount = 0
    var approachingCount = 0
    var exceededCount = 0

    for (item in budgetStatuses) {
      val itemWeight = when (item.status) {
        BudgetHealthStatus.NORMAL -> {
          normalCount++
          BigDecimal.ONE
        }
        BudgetHealthStatus.APPROACHING_LIMIT -> {
          approachingCount++
          BigDecimal("0.70")
        }
        BudgetHealthStatus.EXCEEDED -> {
          exceededCount++
          val util = item.utilizationPercent
          when {
            util >= BigDecimal("150") -> BigDecimal.ZERO
            util <= BigDecimal("100") -> BigDecimal("0.40")
            else -> {
              val excessRatio = util.subtract(HUNDRED)
                .divide(BigDecimal("50"), 4, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
              BigDecimal("0.40").multiply(BigDecimal.ONE.subtract(excessRatio))
            }
          }
        }
      }
      weightSum = weightSum.add(itemWeight)
    }

    val avgWeight = weightSum.divide(BigDecimal(budgetStatuses.size), 4, RoundingMode.HALF_UP)
    val score = avgWeight.multiply(BigDecimal(max))
      .setScale(0, RoundingMode.HALF_UP)
      .toInt()
      .coerceIn(0, max)

    val summary = "${normalCount + approachingCount}/${budgetStatuses.size} within limit"
    val reason = when {
      exceededCount == 0 && approachingCount == 0 -> "All ${budgetStatuses.size} active monthly budgets are comfortably within limit."
      exceededCount == 0 -> "$normalCount budget(s) on track and $approachingCount approaching threshold."
      else -> "$exceededCount of ${budgetStatuses.size} monthly budget(s) exceeded limit."
    }
    val tip = when {
      exceededCount > 0 -> "Review exceeded categories first and trim discretionary expenses or adjust unrealistic caps."
      approachingCount > 0 -> "Slow down spending in categories approaching their warning threshold."
      else -> "Keep monitoring weekly spending so all budgets remain green through month-end."
    }

    return HealthPillarScore(
      pillar = HealthPillarType.BUDGET_DISCIPLINE,
      title = HealthPillarType.BUDGET_DISCIPLINE.title,
      score = score,
      maxScore = max,
      metricSummary = summary,
      reason = reason,
      improvementTip = tip,
    )
  }

  private fun linearScale(
    value: BigDecimal,
    minIn: BigDecimal,
    maxIn: BigDecimal,
    minOut: Int,
    maxOut: Int,
  ): Int {
    if (maxIn <= minIn) return maxOut
    val clamped = value.coerceIn(minIn, maxIn)
    val fraction = clamped.subtract(minIn)
      .divide(maxIn.subtract(minIn), 6, RoundingMode.HALF_UP)
    val delta = BigDecimal(maxOut - minOut)
    return BigDecimal(minOut)
      .add(fraction.multiply(delta))
      .setScale(0, RoundingMode.HALF_UP)
      .toInt()
  }

  private fun deduplicateTransactions(transactions: List<TransactionEntity>): List<TransactionEntity> {
    val seenIds = HashSet<Long>()
    val seenKeys = HashSet<String>()
    val result = ArrayList<TransactionEntity>(transactions.size)
    for (tx in transactions) {
      if (tx.id > 0L && !seenIds.add(tx.id)) continue
      val key = tx.deduplicationKey?.trim()
      if (!key.isNullOrEmpty() && !seenKeys.add(key)) continue
      result.add(tx)
    }
    return result
  }

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

  private fun deduplicateGoals(goals: List<SavingsGoalEntity>): List<SavingsGoalEntity> {
    val seenIds = HashSet<Long>()
    val result = ArrayList<SavingsGoalEntity>(goals.size)
    for (goal in goals) {
      if (goal.id > 0L && !seenIds.add(goal.id)) continue
      result.add(goal)
    }
    return result
  }

  private fun deduplicateBudgets(budgets: List<BudgetEntity>): List<BudgetEntity> {
    val seenKeys = HashSet<String>()
    val result = ArrayList<BudgetEntity>(budgets.size)
    for (b in budgets) {
      val key = "${b.categoryId}|${b.yearMonth}|${b.currencyCode.uppercase()}"
      if (seenKeys.add(key)) result.add(b)
    }
    return result
  }

  private fun sumTypeInPrimary(
    txs: List<TransactionEntity>,
    type: TransactionType,
    primaryCode: String,
  ): BigDecimal {
    var total = BigDecimal.ZERO
    for (tx in txs) {
      if (tx.type != type) continue
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      total = total.add(amt)
    }
    return total
  }

  private fun resolvePrimaryAmountOrNull(
    tx: TransactionEntity,
    primaryCode: String,
  ): BigDecimal? {
    return when {
      tx.currencyCode.equals(primaryCode, ignoreCase = true) -> tx.amount
      tx.primaryCurrencyAtEntry.equals(primaryCode, ignoreCase = true) && tx.convertedPrimaryAmount != null ->
        tx.convertedPrimaryAmount
      else -> null
    }
  }

  private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
