package com.skyplus.finance.domain.intelligence

import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * Offline, ₹0-cost deterministic Cashflow Forecasting, Smart Budget Recommendation,
 * and Savings / Emergency Fund Advisory Engine.
 *
 * Strict guarantees:
 * - Never treats future expected income or unpaid entries as received income.
 * - Never double-counts already-paid bills, already-paid EMIs, or already-allocated savings.
 * - Never mutates existing user budgets or financial records; recommendations require confirmation.
 * - Uses exact BigDecimal arithmetic throughout.
 */
class ForecastAndRecommendationEngine @Inject constructor() {

  companion object {
    private const val ESTIMATE_DISCLAIMER =
      "Forecast values are statistical estimates based on your historical patterns and scheduled commitments — not guaranteed future income."
    private const val MIN_TRANSACTIONS_FOR_FORECAST = 3
    private val BUDGET_BUFFER_MULTIPLIER = BigDecimal("1.05")
  }

  fun analyze(
    transactions: List<TransactionEntity>,
    budgets: List<BudgetEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    loans: List<LoanEntity>,
    emiPayments: List<EmiPaymentEntity>,
    savingsGoals: List<SavingsGoalEntity>,
    settings: AppSettingsEntity?,
    primaryCurrencyCode: String,
    currencyPrecisionMap: Map<String, Int>,
    today: LocalDate = LocalDate.now(),
  ): ForecastAndRecommendationBundle {
    val primaryCode = primaryCurrencyCode.uppercase()
    val precision = currencyPrecisionMap[primaryCode] ?: 2

    val dedupedTxs = deduplicateTransactions(transactions)
    val dedupedLoans = deduplicateLoans(loans)
    val dedupedEmiPayments = deduplicateEmiPayments(emiPayments)
    val dedupedBudgets = deduplicateBudgets(budgets)
    val dedupedGoals = deduplicateGoals(savingsGoals)

    val forecast = buildCashflowForecast(
      dedupedTxs = dedupedTxs,
      dedupedLoans = dedupedLoans,
      dedupedEmiPayments = dedupedEmiPayments,
      settings = settings,
      primaryCode = primaryCode,
      precision = precision,
      today = today,
    )

    val budgetRecs = buildSmartBudgetRecommendations(
      dedupedTxs = dedupedTxs,
      dedupedBudgets = dedupedBudgets,
      categoriesById = categoriesById,
      primaryCode = primaryCode,
      precision = precision,
      today = today,
    )

    val (emergencyRec, goalRecs) = buildSavingsRecommendations(
      dedupedTxs = dedupedTxs,
      dedupedGoals = dedupedGoals,
      settings = settings,
      forecast = forecast,
      primaryCode = primaryCode,
      precision = precision,
      today = today,
    )

    return ForecastAndRecommendationBundle(
      cashflowForecast = forecast,
      budgetRecommendations = budgetRecs,
      emergencyFundRecommendation = emergencyRec,
      goalRecommendations = goalRecs,
    )
  }

  private fun buildCashflowForecast(
    dedupedTxs: List<TransactionEntity>,
    dedupedLoans: List<LoanEntity>,
    dedupedEmiPayments: List<EmiPaymentEntity>,
    settings: AppSettingsEntity?,
    primaryCode: String,
    precision: Int,
    today: LocalDate,
  ): CashflowForecastReport {
    val todayEpoch = today.toEpochDay()
    val currentMonth = YearMonth.from(today)
    val monthStartEpoch = currentMonth.atDay(1).toEpochDay()
    val monthEndEpoch = currentMonth.atEndOfMonth().toEpochDay()

    // Strictly realized transactions up to today in primary currency
    val realizedPrimaryTxs = dedupedTxs.filter { tx ->
      tx.isPaid &&
        tx.transactionDateEpochDay <= todayEpoch &&
        resolvePrimaryAmountOrNull(tx, primaryCode) != null
    }

    val realizedIncomeOrExpenseCount = realizedPrimaryTxs.count {
      it.type == TransactionType.INCOME || it.type == TransactionType.EXPENSE
    }

    // Collect up to 6 prior completed calendar months with activity
    val priorMonthBuckets = (1..6).map { currentMonth.minusMonths(it.toLong()) }
      .reversed()
      .mapNotNull { ym ->
        val s = ym.atDay(1).toEpochDay()
        val e = ym.atEndOfMonth().toEpochDay()
        val txs = realizedPrimaryTxs.filter { it.transactionDateEpochDay in s..e }
        if (txs.isEmpty()) null else ym to txs
      }

    val activeLoans = dedupedLoans.filter {
      it.status == LoanStatus.ACTIVE &&
        it.outstandingPrincipal > BigDecimal.ZERO &&
        it.currencyCode.equals(primaryCode, ignoreCase = true)
    }

    val currentMonthTxs = realizedPrimaryTxs.filter { it.transactionDateEpochDay in monthStartEpoch..todayEpoch }

    // Insufficient data guard
    if (realizedIncomeOrExpenseCount < MIN_TRANSACTIONS_FOR_FORECAST ||
      (priorMonthBuckets.isEmpty() && currentMonthTxs.none { it.type == TransactionType.INCOME || it.type == TransactionType.EXPENSE })
    ) {
      return CashflowForecastReport(
        status = ForecastStatus.INSUFFICIENT_DATA,
        confidence = null,
        evaluatedCurrencyCode = primaryCode,
        priorMonthsAnalyzed = priorMonthBuckets.size,
        hasIrregularOrSeasonalIncome = false,
        incomePatternNote = "Insufficient historical data to evaluate income regularity.",
        statusReason = "Record at least $MIN_TRANSACTIONS_FOR_FORECAST realized transactions in $primaryCode to generate cashflow projections.",
        endOfMonthForecast = null,
        nextThreeMonths = emptyList(),
        estimateDisclaimer = ESTIMATE_DISCLAIMER,
      )
    }

    // Current cash balance strictly from paid transactions <= today
    var currentCashBalance = BigDecimal.ZERO
    for (tx in realizedPrimaryTxs) {
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      currentCashBalance = when (tx.type) {
        TransactionType.INCOME -> currentCashBalance.add(amt)
        TransactionType.EXPENSE -> currentCashBalance.subtract(amt)
        TransactionType.SAVINGS_ALLOCATION -> currentCashBalance.subtract(amt)
      }
    }
    currentCashBalance = DecimalUtils.scaleToCurrency(currentCashBalance, precision)

    // Prior monthly realized totals
    val priorIncomes = priorMonthBuckets.map { (_, list) ->
      sumType(list, TransactionType.INCOME, primaryCode)
    }
    val priorTotalExpenses = priorMonthBuckets.map { (_, list) ->
      sumType(list, TransactionType.EXPENSE, primaryCode)
    }
    // Non-EMI variable+recurring expenses in prior months (to avoid double counting active loan EMIs in future projections)
    val priorVariableExpensesOnly = priorMonthBuckets.map { (_, list) ->
      sumPrimary(
        list.filter { it.type == TransactionType.EXPENSE && it.linkedLoanId == null && !it.isRecurring },
        primaryCode,
      )
    }

    // Seasonal / irregular income detection across prior months
    val (isIrregularIncome, incomeNote) = evaluateIncomeSeasonality(priorIncomes, primaryCode, precision)

    val confidence = when {
      priorMonthBuckets.size >= 3 && !isIrregularIncome -> ForecastConfidence.HIGH
      priorMonthBuckets.isNotEmpty() -> ForecastConfidence.MEDIUM
      else -> ForecastConfidence.LOW
    }

    // Weighted averages across prior months (recent months weighted slightly higher: 1..N)
    val avgMonthlyIncomeBaseline = if (priorIncomes.isNotEmpty()) {
      weightedAverage(priorIncomes, precision)
    } else {
      sumType(currentMonthTxs, TransactionType.INCOME, primaryCode)
    }

    val avgMonthlyTotalExpenseBaseline = if (priorTotalExpenses.isNotEmpty()) {
      weightedAverage(priorTotalExpenses, precision)
    } else {
      sumType(currentMonthTxs, TransactionType.EXPENSE, primaryCode)
    }

    val avgMonthlyVariableBaseline = if (priorVariableExpensesOnly.isNotEmpty()) {
      weightedAverage(priorVariableExpensesOnly, precision)
    } else {
      sumPrimary(
        currentMonthTxs.filter { it.type == TransactionType.EXPENSE && it.linkedLoanId == null && !it.isRecurring },
        primaryCode,
      )
    }

    // ---- End-of-Month Projection (Strictly anti-double-counting) ----
    val realizedMonthIncome = DecimalUtils.scaleToCurrency(sumType(currentMonthTxs, TransactionType.INCOME, primaryCode), precision)
    val realizedMonthExpenses = DecimalUtils.scaleToCurrency(sumType(currentMonthTxs, TransactionType.EXPENSE, primaryCode), precision)

    // Remaining scheduled UNPAID upcoming bills in current month after today (never paid bills)
    val remainingUnpaidBills = dedupedTxs.filter { tx ->
      tx.type == TransactionType.EXPENSE &&
        tx.isUpcomingBill &&
        !tx.isPaid &&
        tx.currencyCode.equals(primaryCode, ignoreCase = true) &&
        tx.transactionDateEpochDay in (todayEpoch + 1)..monthEndEpoch
    }
    val remainingBillsSum = sumPrimary(remainingUnpaidBills, primaryCode)

    // Remaining active loan EMIs due in (today+1 .. monthEnd) NOT already paid this month
    val loanIdsPaidThisMonth = (
      dedupedEmiPayments
        .filter { it.paymentDateEpochDay in monthStartEpoch..todayEpoch }
        .map { it.loanId } +
        currentMonthTxs.mapNotNull { it.linkedLoanId }
      ).toSet()

    val remainingUnpaidEmiSum = activeLoans
      .filter { loan ->
        loan.nextDueDateEpochDay in (todayEpoch + 1)..monthEndEpoch &&
          !loanIdsPaidThisMonth.contains(loan.id)
      }
      .fold(BigDecimal.ZERO) { acc, loan -> acc.add(loan.emiAmount.coerceAtLeast(BigDecimal.ZERO)) }

    // Scheduled recurring expenses not already paid this month & not already covered by unpaid bills
    val recurringPatterns = detectMonthlyRecurringCommitments(realizedPrimaryTxs, primaryCode, precision)
    var remainingRecurringEstSum = BigDecimal.ZERO
    for (rec in recurringPatterns) {
      if (rec.nextDueEpochDay !in (todayEpoch + 1)..monthEndEpoch) continue
      val alreadyPaidThisMonth = currentMonthTxs.any { tx ->
        tx.type == TransactionType.EXPENSE &&
          tx.categoryId == rec.categoryId &&
          tx.description.trim().equals(rec.title, ignoreCase = true)
      }
      val alreadyInUnpaidBills = remainingUnpaidBills.any { b ->
        b.categoryId == rec.categoryId &&
          b.description.trim().equals(rec.title, ignoreCase = true)
      }
      if (!alreadyPaidThisMonth && !alreadyInUnpaidBills) {
        remainingRecurringEstSum = remainingRecurringEstSum.add(rec.monthlyAmount)
      }
    }

    val scheduledCommitmentsRemaining = DecimalUtils.scaleToCurrency(
      remainingBillsSum.add(remainingUnpaidEmiSum).add(remainingRecurringEstSum),
      precision,
    )

    // Remaining variable spending projection
    val elapsedDaysInMonth = today.dayOfMonth.coerceAtLeast(1)
    val totalDaysInMonth = currentMonth.lengthOfMonth()
    val remainingDaysInMonth = (totalDaysInMonth - today.dayOfMonth).coerceAtLeast(0)

    val currentMonthVariableSpent = sumPrimary(
      currentMonthTxs.filter { it.type == TransactionType.EXPENSE && it.linkedLoanId == null && !it.isRecurring },
      primaryCode,
    )
    val currentDailyVariableRate = DecimalUtils.divideExact(
      currentMonthVariableSpent,
      BigDecimal(elapsedDaysInMonth),
      4,
    )
    val historicalDailyVariableRate = DecimalUtils.divideExact(
      avgMonthlyVariableBaseline,
      BigDecimal(totalDaysInMonth),
      4,
    )
    val blendedDailyVariableRate = if (priorVariableExpensesOnly.isNotEmpty()) {
      currentDailyVariableRate.multiply(BigDecimal("0.40"))
        .add(historicalDailyVariableRate.multiply(BigDecimal("0.60")))
    } else {
      currentDailyVariableRate
    }

    val projectedVariableRemaining = DecimalUtils.scaleToCurrency(
      blendedDailyVariableRate.multiply(BigDecimal(remainingDaysInMonth)),
      precision,
    )

    val projectedTotalMonthExpenses = DecimalUtils.scaleToCurrency(
      realizedMonthExpenses.add(scheduledCommitmentsRemaining).add(projectedVariableRemaining),
      precision,
    )

    // Projected additional (NOT YET RECEIVED) income for remainder of month
    val configuredRemainingIncome = settings?.confirmedRemainingIncome?.coerceAtLeast(BigDecimal.ZERO) ?: BigDecimal.ZERO
    val futureDatedScheduledIncome = dedupedTxs
      .filter {
        it.type == TransactionType.INCOME &&
          it.transactionDateEpochDay in (todayEpoch + 1)..monthEndEpoch &&
          it.currencyCode.equals(primaryCode, ignoreCase = true)
      }
      .fold(BigDecimal.ZERO) { acc, tx -> acc.add(tx.amount) }

    val projectedAdditionalIncomeRaw = when {
      configuredRemainingIncome > BigDecimal.ZERO || futureDatedScheduledIncome > BigDecimal.ZERO ->
        maxOf(configuredRemainingIncome, futureDatedScheduledIncome)
      remainingDaysInMonth > 0 && realizedMonthIncome < avgMonthlyIncomeBaseline && priorIncomes.isNotEmpty() -> {
        // Estimate remaining only if user typically gets salary/income later in the month
        val usualSalaryLater = priorMonthBuckets.any { (_, list) ->
          list.any {
            it.type == TransactionType.INCOME &&
              LocalDate.ofEpochDay(it.transactionDateEpochDay).dayOfMonth > today.dayOfMonth
          }
        }
        if (usualSalaryLater) avgMonthlyIncomeBaseline.subtract(realizedMonthIncome).coerceAtLeast(BigDecimal.ZERO) else BigDecimal.ZERO
      }
      else -> BigDecimal.ZERO
    }
    val projectedRemainingIncome = DecimalUtils.scaleToCurrency(projectedAdditionalIncomeRaw, precision)
    val projectedTotalMonthIncome = DecimalUtils.scaleToCurrency(realizedMonthIncome.add(projectedRemainingIncome), precision)

    val projectedEndOfMonthBalance = DecimalUtils.scaleToCurrency(
      currentCashBalance
        .add(projectedRemainingIncome)
        .subtract(scheduledCommitmentsRemaining)
        .subtract(projectedVariableRemaining),
      precision,
    )

    val eomForecast = EndOfMonthForecast(
      yearMonth = currentMonth.toString(),
      currencyCode = primaryCode,
      realizedIncomeToDate = realizedMonthIncome,
      projectedRemainingIncome = projectedRemainingIncome,
      projectedTotalIncome = projectedTotalMonthIncome,
      realizedExpensesToDate = realizedMonthExpenses,
      scheduledUnpaidCommitmentsRemaining = scheduledCommitmentsRemaining,
      projectedVariableExpensesRemaining = projectedVariableRemaining,
      projectedTotalExpenses = projectedTotalMonthExpenses,
      currentCashBalance = currentCashBalance,
      projectedEndOfMonthBalance = projectedEndOfMonthBalance,
      disclaimer = ESTIMATE_DISCLAIMER,
    )

    // ---- Next 3 Months Forecast ----
    val nextThreeMonths = mutableListOf<MonthlyCashflowForecast>()
    var runningBalance = projectedEndOfMonthBalance
    val recurringMonthlyCommitmentsTotal = recurringPatterns.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.monthlyAmount) }
    val baseNonRecurringVariableExpense = if (priorVariableExpensesOnly.isNotEmpty()) {
      avgMonthlyVariableBaseline
    } else {
      avgMonthlyTotalExpenseBaseline
        .subtract(activeLoans.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.emiAmount) })
        .subtract(recurringMonthlyCommitmentsTotal)
        .coerceAtLeast(BigDecimal.ZERO)
    }

    for (offset in 1..3) {
      val targetYm = currentMonth.plusMonths(offset.toLong())
      val emiForMonth = activeLoans
        .filter { loan -> loan.remainingTenureMonths >= offset }
        .fold(BigDecimal.ZERO) { acc, loan -> acc.add(loan.emiAmount.coerceAtLeast(BigDecimal.ZERO)) }

      val projectedInc = DecimalUtils.scaleToCurrency(avgMonthlyIncomeBaseline, precision)
      val projectedExp = DecimalUtils.scaleToCurrency(
        baseNonRecurringVariableExpense.add(recurringMonthlyCommitmentsTotal).add(emiForMonth),
        precision,
      )
      val netCash = DecimalUtils.scaleToCurrency(projectedInc.subtract(projectedExp), precision)
      runningBalance = DecimalUtils.scaleToCurrency(runningBalance.add(netCash), precision)

      val label = "${targetYm.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${targetYm.year}"
      nextThreeMonths.add(
        MonthlyCashflowForecast(
          yearMonth = targetYm.toString(),
          monthLabel = label,
          currencyCode = primaryCode,
          projectedIncome = projectedInc,
          projectedExpenses = projectedExp,
          projectedNetCashflow = netCash,
          projectedEndingBalance = runningBalance,
          includesScheduledEmiAmount = DecimalUtils.scaleToCurrency(emiForMonth, precision),
        ),
      )
    }

    val statusReason = "Analyzed ${priorMonthBuckets.size} completed prior month(s) and $realizedIncomeOrExpenseCount realized $primaryCode transaction(s) up to $today."

    return CashflowForecastReport(
      status = ForecastStatus.READY,
      confidence = confidence,
      evaluatedCurrencyCode = primaryCode,
      priorMonthsAnalyzed = priorMonthBuckets.size,
      hasIrregularOrSeasonalIncome = isIrregularIncome,
      incomePatternNote = incomeNote,
      statusReason = statusReason,
      endOfMonthForecast = eomForecast,
      nextThreeMonths = nextThreeMonths,
      estimateDisclaimer = ESTIMATE_DISCLAIMER,
    )
  }

  private fun buildSmartBudgetRecommendations(
    dedupedTxs: List<TransactionEntity>,
    dedupedBudgets: List<BudgetEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    primaryCode: String,
    precision: Int,
    today: LocalDate,
  ): List<BudgetRecommendation> {
    val currentMonth = YearMonth.from(today)
    val threeFullPriorMonths = listOf(
      currentMonth.minusMonths(3),
      currentMonth.minusMonths(2),
      currentMonth.minusMonths(1),
    )
    val startEpoch = threeFullPriorMonths.first().atDay(1).toEpochDay()
    val endEpoch = threeFullPriorMonths.last().atEndOfMonth().toEpochDay()

    val prior3MonthExpenses = dedupedTxs.filter { tx ->
      tx.type == TransactionType.EXPENSE &&
        tx.isPaid &&
        tx.transactionDateEpochDay in startEpoch..endEpoch &&
        resolvePrimaryAmountOrNull(tx, primaryCode) != null
    }

    if (prior3MonthExpenses.isEmpty()) return emptyList()

    val currentBudgetsByCat = dedupedBudgets
      .filter { it.yearMonth == currentMonth.toString() && it.currencyCode.equals(primaryCode, ignoreCase = true) }
      .associateBy { it.categoryId }

    val byCategory = prior3MonthExpenses.groupBy { it.categoryId }
    val recs = mutableListOf<BudgetRecommendation>()

    for ((catId, catTxs) in byCategory) {
      val cat = categoriesById[catId]
      if (cat != null && cat.type != CategoryType.EXPENSE) continue

      val monthlyTotals = threeFullPriorMonths.mapNotNull { ym ->
        val s = ym.atDay(1).toEpochDay()
        val e = ym.atEndOfMonth().toEpochDay()
        val txsInMonth = catTxs.filter { it.transactionDateEpochDay in s..e }
        if (txsInMonth.isEmpty()) null else sumPrimary(txsInMonth, primaryCode)
      }
      if (monthlyTotals.isEmpty()) continue

      val total3M = monthlyTotals.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
      val avgSpend = DecimalUtils.divideExact(total3M, BigDecimal(monthlyTotals.size), precision)
      if (avgSpend <= BigDecimal.ZERO) continue

      val suggestedRaw = DecimalUtils.scaleToCurrency(avgSpend.multiply(BUDGET_BUFFER_MULTIPLIER), precision)
      val existingBudget = currentBudgetsByCat[catId]
      val catName = cat?.displayNameEn ?: "Category"

      if (existingBudget == null) {
        recs.add(
          BudgetRecommendation(
            categoryId = catId,
            categoryName = catName,
            currencyCode = primaryCode,
            currentBudgetLimit = null,
            threeMonthAverageSpend = avgSpend,
            suggestedBudgetLimit = suggestedRaw,
            action = BudgetRecommendationAction.CREATE_BUDGET,
            monthsSampled = monthlyTotals.size,
            reason = "You spent an average of $primaryCode ${avgSpend.toPlainString()}/month over ${monthlyTotals.size} recent month(s) with no budget set.",
            requiresUserConfirmation = true,
          ),
        )
      } else {
        val currentLimit = DecimalUtils.scaleToCurrency(existingBudget.limitAmount, precision)
        val highThreshold = currentLimit.multiply(BigDecimal("1.15"))
        val lowThreshold = currentLimit.multiply(BigDecimal("0.65"))

        val (action, targetLimit, reason) = when {
          avgSpend > highThreshold -> Triple(
            BudgetRecommendationAction.INCREASE_LIMIT,
            suggestedRaw,
            "Your ${monthlyTotals.size}-month average ($primaryCode ${avgSpend.toPlainString()}) exceeds your current limit ($primaryCode ${currentLimit.toPlainString()}) by >15%.",
          )
          avgSpend < lowThreshold -> Triple(
            BudgetRecommendationAction.LOWER_LIMIT,
            suggestedRaw,
            "Your ${monthlyTotals.size}-month average ($primaryCode ${avgSpend.toPlainString()}) is under 65% of your current limit ($primaryCode ${currentLimit.toPlainString()}); freeing headroom can boost savings.",
          )
          else -> Triple(
            BudgetRecommendationAction.KEEP_CURRENT,
            currentLimit,
            "Your current limit ($primaryCode ${currentLimit.toPlainString()}) aligns well with your ${monthlyTotals.size}-month average ($primaryCode ${avgSpend.toPlainString()}).",
          )
        }

        recs.add(
          BudgetRecommendation(
            categoryId = catId,
            categoryName = catName,
            currencyCode = primaryCode,
            currentBudgetLimit = currentLimit,
            threeMonthAverageSpend = avgSpend,
            suggestedBudgetLimit = targetLimit,
            action = action,
            monthsSampled = monthlyTotals.size,
            reason = reason,
            requiresUserConfirmation = true,
          ),
        )
      }
    }

    return recs.sortedByDescending { it.threeMonthAverageSpend }
  }

  private fun buildSavingsRecommendations(
    dedupedTxs: List<TransactionEntity>,
    dedupedGoals: List<SavingsGoalEntity>,
    settings: AppSettingsEntity?,
    forecast: CashflowForecastReport,
    primaryCode: String,
    precision: Int,
    today: LocalDate,
  ): Pair<EmergencyFundRecommendation, List<GoalContributionRecommendation>> {
    val todayEpoch = today.toEpochDay()
    val currentMonth = YearMonth.from(today)
    val monthStartEpoch = currentMonth.atDay(1).toEpochDay()

    // Essential baseline: configured setting if > 0; otherwise estimated from forecasted/average monthly expense
    val configuredEssential = settings?.monthlyEssentialExpenses ?: BigDecimal.ZERO
    val (essentialBaseline, isEstimated) = if (configuredEssential > BigDecimal.ZERO) {
      DecimalUtils.scaleToCurrency(configuredEssential, precision) to false
    } else {
      val fallback = forecast.endOfMonthForecast?.projectedTotalExpenses ?: BigDecimal.ZERO
      DecimalUtils.scaleToCurrency(fallback, precision) to true
    }

    val threeMonthTarget = DecimalUtils.scaleToCurrency(essentialBaseline.multiply(BigDecimal("3")), precision)
    val sixMonthTarget = DecimalUtils.scaleToCurrency(essentialBaseline.multiply(BigDecimal("6")), precision)

    // Use goal.currentSavedAmount directly (NEVER add linkedGoalId transactions on top!)
    val primaryGoals = dedupedGoals.filter { it.currencyCode.equals(primaryCode, ignoreCase = true) }
    val emergencyGoals = primaryGoals.filter { it.isEmergencyFund }
    val currentEmergencySaved = DecimalUtils.scaleToCurrency(
      emergencyGoals.fold(BigDecimal.ZERO) { acc, g -> acc.add(g.currentSavedAmount.coerceAtLeast(BigDecimal.ZERO)) },
      precision,
    )

    val rem3 = DecimalUtils.scaleToCurrency(threeMonthTarget.subtract(currentEmergencySaved).coerceAtLeast(BigDecimal.ZERO), precision)
    val rem6 = DecimalUtils.scaleToCurrency(sixMonthTarget.subtract(currentEmergencySaved).coerceAtLeast(BigDecimal.ZERO), precision)

    val emergencyGoalIds = emergencyGoals.map { it.id }.toSet()
    val alreadyAllocatedToEfThisMonth = DecimalUtils.scaleToCurrency(
      dedupedTxs.filter { tx ->
        tx.isPaid &&
          tx.type == TransactionType.SAVINGS_ALLOCATION &&
          tx.transactionDateEpochDay in monthStartEpoch..todayEpoch &&
          tx.currencyCode.equals(primaryCode, ignoreCase = true) &&
          (tx.linkedGoalId == null || emergencyGoalIds.contains(tx.linkedGoalId))
      }.fold(BigDecimal.ZERO) { acc, tx -> acc.add(tx.amount) },
      precision,
    )

    val expectedMonthlySurplus = forecast.endOfMonthForecast?.let { eom ->
      eom.projectedTotalIncome.subtract(eom.projectedTotalExpenses).coerceAtLeast(BigDecimal.ZERO)
    } ?: BigDecimal.ZERO

    val rawMonthlySuggestion = when {
      rem6 <= BigDecimal.ZERO -> BigDecimal.ZERO
      rem3 > BigDecimal.ZERO -> {
        val pace6Mo = DecimalUtils.divideExact(rem3, BigDecimal("6"), precision)
        val capFromSurplus = if (expectedMonthlySurplus > BigDecimal.ZERO) {
          DecimalUtils.scaleToCurrency(expectedMonthlySurplus.multiply(BigDecimal("0.60")), precision)
        } else {
          pace6Mo
        }
        minOf(rem3, maxOf(pace6Mo, capFromSurplus).coerceAtMost(rem3))
      }
      else -> {
        val pace12Mo = DecimalUtils.divideExact(rem6, BigDecimal("12"), precision)
        if (expectedMonthlySurplus > BigDecimal.ZERO) {
          minOf(rem6, DecimalUtils.scaleToCurrency(expectedMonthlySurplus.multiply(BigDecimal("0.40")), precision).coerceAtLeast(pace12Mo))
        } else {
          pace12Mo
        }
      }
    }

    val recommendedMonthly = DecimalUtils.scaleToCurrency(rawMonthlySuggestion, precision)
    val remainingThisMonth = DecimalUtils.scaleToCurrency(
      recommendedMonthly.subtract(alreadyAllocatedToEfThisMonth).coerceAtLeast(BigDecimal.ZERO),
      precision,
    )

    val monthsTo3 = if (rem3 <= BigDecimal.ZERO) {
      0
    } else if (recommendedMonthly > BigDecimal.ZERO) {
      rem3.divide(recommendedMonthly, 0, RoundingMode.CEILING).toInt()
    } else {
      null
    }

    val monthsTo6 = if (rem6 <= BigDecimal.ZERO) {
      0
    } else if (recommendedMonthly > BigDecimal.ZERO) {
      rem6.divide(recommendedMonthly, 0, RoundingMode.CEILING).toInt()
    } else {
      null
    }

    val summaryReason = when {
      essentialBaseline <= BigDecimal.ZERO ->
        "Set your Monthly Essential Expenses to compute exact 3-month and 6-month emergency fund milestones."
      rem6 <= BigDecimal.ZERO ->
        "Your emergency reserve ($primaryCode ${currentEmergencySaved.toPlainString()}) already meets or exceeds the 6-month target ($primaryCode ${sixMonthTarget.toPlainString()})."
      rem3 <= BigDecimal.ZERO ->
        "3-month emergency milestone achieved. Contribute $primaryCode ${recommendedMonthly.toPlainString()}/month to complete the 6-month cushion."
      else ->
        "Save $primaryCode ${recommendedMonthly.toPlainString()}/month ($primaryCode ${remainingThisMonth.toPlainString()} remaining this month after already allocated savings) to build your 3-month reserve."
    }

    val efRec = EmergencyFundRecommendation(
      currencyCode = primaryCode,
      monthlyEssentialBaseline = essentialBaseline,
      isBaselineEstimatedFromSpending = isEstimated,
      currentEmergencySaved = currentEmergencySaved,
      threeMonthTarget = threeMonthTarget,
      sixMonthTarget = sixMonthTarget,
      remainingForThreeMonthTarget = rem3,
      remainingForSixMonthTarget = rem6,
      alreadyAllocatedThisMonth = alreadyAllocatedToEfThisMonth,
      recommendedMonthlySavings = recommendedMonthly,
      remainingRecommendedThisMonth = remainingThisMonth,
      estimatedMonthsToThreeMonthTarget = monthsTo3,
      estimatedMonthsToSixMonthTarget = monthsTo6,
      summaryReason = summaryReason,
    )

    val goalRecs = primaryGoals
      .filter { it.targetAmount > it.currentSavedAmount }
      .map { goal ->
        val remaining = DecimalUtils.scaleToCurrency(goal.targetAmount.subtract(goal.currentSavedAmount).coerceAtLeast(BigDecimal.ZERO), precision)
        val alreadyThisMonth = DecimalUtils.scaleToCurrency(
          dedupedTxs.filter { tx ->
            tx.isPaid &&
              tx.type == TransactionType.SAVINGS_ALLOCATION &&
              tx.linkedGoalId == goal.id &&
              tx.transactionDateEpochDay in monthStartEpoch..todayEpoch &&
              tx.currencyCode.equals(primaryCode, ignoreCase = true)
          }.fold(BigDecimal.ZERO) { acc, tx -> acc.add(tx.amount) },
          precision,
        )

        val targetMonths = goal.targetDateEpochDay?.let { targetEpoch ->
          val targetDate = LocalDate.ofEpochDay(targetEpoch)
          if (targetDate.isAfter(today)) {
            ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(targetDate)).toInt().coerceAtLeast(1)
          } else {
            1
          }
        }
        val monthlyPace = if (targetMonths != null) {
          DecimalUtils.divideExact(remaining, BigDecimal(targetMonths), precision)
        } else {
          DecimalUtils.divideExact(remaining, BigDecimal("6"), precision)
        }
        val remGoalThisMonth = DecimalUtils.scaleToCurrency(
          monthlyPace.subtract(alreadyThisMonth).coerceAtLeast(BigDecimal.ZERO),
          precision,
        )
        val estMonths = if (monthlyPace > BigDecimal.ZERO) {
          remaining.divide(monthlyPace, 0, RoundingMode.CEILING).toInt()
        } else {
          null
        }
        val reason = if (targetMonths != null) {
          "To reach ${goal.goalName} on schedule ($targetMonths mo), allocate $primaryCode ${monthlyPace.toPlainString()}/month."
        } else {
          "Recommended steady pace of $primaryCode ${monthlyPace.toPlainString()}/month to complete ${goal.goalName} in ~6 months."
        }

        GoalContributionRecommendation(
          goalId = goal.id,
          goalName = goal.goalName,
          currencyCode = primaryCode,
          targetAmount = DecimalUtils.scaleToCurrency(goal.targetAmount, precision),
          currentSavedAmount = DecimalUtils.scaleToCurrency(goal.currentSavedAmount, precision),
          remainingTargetAmount = remaining,
          alreadyContributedThisMonth = alreadyThisMonth,
          suggestedMonthlyContribution = monthlyPace,
          remainingToContributeThisMonth = remGoalThisMonth,
          estimatedMonthsToComplete = estMonths,
          reason = reason,
        )
      }

    return efRec to goalRecs
  }

  private data class MonthlyRecurringPattern(
    val title: String,
    val categoryId: Long,
    val monthlyAmount: BigDecimal,
    val nextDueEpochDay: Long,
  )

  private fun detectMonthlyRecurringCommitments(
    realizedPrimaryTxs: List<TransactionEntity>,
    primaryCode: String,
    precision: Int,
  ): List<MonthlyRecurringPattern> {
    val expenses = realizedPrimaryTxs.filter { it.type == TransactionType.EXPENSE && (it.description.isNotBlank() || it.isRecurring) }
    val grouped = expenses.groupBy { "${it.categoryId}|${it.description.trim().lowercase()}" }
    val out = mutableListOf<MonthlyRecurringPattern>()

    for ((_, group) in grouped) {
      val sorted = group.sortedBy { it.transactionDateEpochDay }
      val anyRecurring = sorted.any { it.isRecurring }
      if (!anyRecurring && sorted.size < 2) continue

      val amounts = sorted.mapNotNull { resolvePrimaryAmountOrNull(it, primaryCode) }
      if (amounts.isEmpty()) continue
      val avgAmt = DecimalUtils.divideExact(amounts.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }, BigDecimal(amounts.size), precision)

      val intervals = mutableListOf<Int>()
      for (i in 1 until sorted.size) {
        val gap = (sorted[i].transactionDateEpochDay - sorted[i - 1].transactionDateEpochDay).toInt()
        if (gap >= 5) intervals.add(gap)
      }
      val avgInterval = if (intervals.isNotEmpty()) intervals.sum() / intervals.size else (sorted.last().recurrenceIntervalDays ?: 30)
      if (!anyRecurring && avgInterval !in 20..40) continue

      val lastTx = sorted.last()
      out.add(
        MonthlyRecurringPattern(
          title = lastTx.description.trim(),
          categoryId = lastTx.categoryId,
          monthlyAmount = avgAmt,
          nextDueEpochDay = lastTx.transactionDateEpochDay + avgInterval.coerceAtLeast(1),
        ),
      )
    }
    return out
  }

  private fun evaluateIncomeSeasonality(
    priorIncomes: List<BigDecimal>,
    primaryCode: String,
    precision: Int,
  ): Pair<Boolean, String> {
    if (priorIncomes.size < 2) {
      return false to "More completed months of income history will improve seasonality detection."
    }
    val nonZero = priorIncomes.filter { it > BigDecimal.ZERO }
    if (nonZero.isEmpty()) {
      return false to "No realized prior-month income recorded yet."
    }
    val hasZeroGap = priorIncomes.any { it <= BigDecimal.ZERO } && nonZero.isNotEmpty()
    val minInc = DecimalUtils.scaleToCurrency(priorIncomes.minOrNull() ?: BigDecimal.ZERO, precision)
    val maxInc = DecimalUtils.scaleToCurrency(priorIncomes.maxOrNull() ?: BigDecimal.ZERO, precision)
    val mean = DecimalUtils.divideExact(
      priorIncomes.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) },
      BigDecimal(priorIncomes.size),
      precision,
    )
    val maxDeviationRatio = if (mean > BigDecimal.ZERO) {
      priorIncomes.maxOf { abs(it.subtract(mean).toDouble()) / mean.toDouble() }
    } else {
      0.0
    }

    val isIrregular = hasZeroGap ||
      (minInc > BigDecimal.ZERO && maxInc > minInc.multiply(BigDecimal("1.60"))) ||
      maxDeviationRatio >= 0.35

    val note = if (isIrregular) {
      "Irregular or seasonal income detected across ${priorIncomes.size} months ($primaryCode ${minInc.toPlainString()} to ${maxInc.toPlainString()}). Projections use weighted averages and carry wider uncertainty."
    } else {
      "Income is relatively steady across ${priorIncomes.size} analyzed months ($primaryCode ${minInc.toPlainString()} to ${maxInc.toPlainString()})."
    }
    return isIrregular to note
  }

  private fun weightedAverage(values: List<BigDecimal>, precision: Int): BigDecimal {
    if (values.isEmpty()) return DecimalUtils.scaleToCurrency(BigDecimal.ZERO, precision)
    var weightedSum = BigDecimal.ZERO
    var totalWeight = 0
    values.forEachIndexed { idx, v ->
      val weight = idx + 1 // newer items in chronological order carry higher weight
      weightedSum = weightedSum.add(v.multiply(BigDecimal(weight)))
      totalWeight += weight
    }
    return DecimalUtils.divideExact(weightedSum, BigDecimal(totalWeight), precision)
  }

  private fun sumType(txs: List<TransactionEntity>, type: TransactionType, primaryCode: String): BigDecimal =
    sumPrimary(txs.filter { it.type == type }, primaryCode)

  private fun sumPrimary(txs: List<TransactionEntity>, primaryCode: String): BigDecimal {
    var sum = BigDecimal.ZERO
    for (tx in txs) {
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      sum = sum.add(amt)
    }
    return sum
  }

  private fun resolvePrimaryAmountOrNull(tx: TransactionEntity, primaryCode: String): BigDecimal? = when {
    tx.currencyCode.equals(primaryCode, ignoreCase = true) -> tx.amount
    tx.primaryCurrencyAtEntry.equals(primaryCode, ignoreCase = true) && tx.convertedPrimaryAmount != null ->
      tx.convertedPrimaryAmount
    else -> null
  }

  private fun deduplicateTransactions(transactions: List<TransactionEntity>): List<TransactionEntity> {
    val seenIds = HashSet<Long>()
    val seenKeys = HashSet<String>()
    val out = ArrayList<TransactionEntity>(transactions.size)
    for (tx in transactions) {
      if (tx.id > 0L && !seenIds.add(tx.id)) continue
      val key = tx.deduplicationKey?.trim()
      if (!key.isNullOrEmpty() && !seenKeys.add(key)) continue
      out.add(tx)
    }
    return out
  }

  private fun deduplicateLoans(loans: List<LoanEntity>): List<LoanEntity> {
    val seenIds = HashSet<Long>()
    val seenSig = HashSet<String>()
    val out = ArrayList<LoanEntity>(loans.size)
    for (loan in loans) {
      if (loan.id > 0L && !seenIds.add(loan.id)) continue
      val sig = "${loan.loanName.trim().lowercase()}|${loan.lender.trim().lowercase()}|${loan.currencyCode.uppercase()}|${loan.startDateEpochDay}"
      if (loan.id == 0L && !seenSig.add(sig)) continue
      out.add(loan)
    }
    return out
  }

  private fun deduplicateEmiPayments(payments: List<EmiPaymentEntity>): List<EmiPaymentEntity> {
    val seenIds = HashSet<Long>()
    val out = ArrayList<EmiPaymentEntity>(payments.size)
    for (p in payments) {
      if (p.id > 0L && !seenIds.add(p.id)) continue
      out.add(p)
    }
    return out
  }

  private fun deduplicateBudgets(budgets: List<BudgetEntity>): List<BudgetEntity> {
    val seenKeys = HashSet<String>()
    val out = ArrayList<BudgetEntity>(budgets.size)
    for (b in budgets) {
      val key = "${b.categoryId}|${b.yearMonth}|${b.currencyCode.uppercase()}"
      if (seenKeys.add(key)) out.add(b)
    }
    return out
  }

  private fun deduplicateGoals(goals: List<SavingsGoalEntity>): List<SavingsGoalEntity> {
    val seenIds = HashSet<Long>()
    val out = ArrayList<SavingsGoalEntity>(goals.size)
    for (g in goals) {
      if (g.id > 0L && !seenIds.add(g.id)) continue
      out.add(g)
    }
    return out
  }
}
