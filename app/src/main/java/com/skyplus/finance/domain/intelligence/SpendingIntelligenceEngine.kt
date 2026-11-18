package com.skyplus.finance.domain.intelligence

import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.math.abs

/**
 * Deterministic, offline-first Spending Analysis, Pattern Detection, and Smart Alerts engine.
 *
 * Guarantees:
 * - Never triggers unusual spending alerts without sufficient historical user data.
 * - Never alerts on already-paid bills or already-paid EMIs.
 * - Strictly deduplicates transactions, loans, budgets, and generated alerts.
 * - Uses exact BigDecimal arithmetic and respects currency decimal precisions.
 */
class SpendingIntelligenceEngine @Inject constructor(
  private val budgetAndSavingsCalculator: BudgetAndSavingsCalculator,
) {

  companion object {
    private const val UPCOMING_WINDOW_DAYS = 7L
    private const val MIN_EXPENSES_FOR_WEEKDAY_WEEKEND = 3
    private const val MIN_PRIOR_MONTHS_FOR_CATEGORY_SPIKE = 2
    private const val MIN_PRIOR_TX_FOR_SINGLE_ANOMALY = 5
    private val HUNDRED = BigDecimal("100")
    private val CATEGORY_SPIKE_MULTIPLIER = BigDecimal("1.50")
    private val SINGLE_TX_SPIKE_MULTIPLIER = BigDecimal("2.00")
  }

  fun analyze(
    transactions: List<TransactionEntity>,
    budgets: List<BudgetEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    loans: List<LoanEntity>,
    emiPayments: List<EmiPaymentEntity>,
    primaryCurrencyCode: String,
    currencyPrecisionMap: Map<String, Int>,
    today: LocalDate = LocalDate.now(),
  ): SpendingIntelligenceReport {
    val primaryCode = primaryCurrencyCode.uppercase()
    val precision = currencyPrecisionMap[primaryCode] ?: 2
    val todayEpoch = today.toEpochDay()
    val currentMonth = YearMonth.from(today)
    val previousMonth = currentMonth.minusMonths(1)

    val dedupedTxs = deduplicateTransactions(transactions)
    val dedupedLoans = deduplicateLoans(loans)
    val dedupedEmiPayments = deduplicateEmiPayments(emiPayments)
    val dedupedBudgets = deduplicateBudgets(budgets)

    // Realized primary-currency expenses up to today (excludes unpaid bills, future entries, and SAVINGS_ALLOCATION)
    val realizedPrimaryExpenses = dedupedTxs.filter { tx ->
      tx.type == TransactionType.EXPENSE &&
        tx.isPaid &&
        tx.transactionDateEpochDay <= todayEpoch &&
        resolvePrimaryAmountOrNull(tx, primaryCode) != null
    }

    // 1. Month-over-month category comparison
    val currStart = currentMonth.atDay(1).toEpochDay()
    val currEnd = todayEpoch
    val prevStart = previousMonth.atDay(1).toEpochDay()
    val prevEnd = previousMonth.atEndOfMonth().toEpochDay()

    val currentMonthExpenses = realizedPrimaryExpenses.filter { it.transactionDateEpochDay in currStart..currEnd }
    val previousMonthExpenses = realizedPrimaryExpenses.filter { it.transactionDateEpochDay in prevStart..prevEnd }

    val currentTotalSpent = DecimalUtils.scaleToCurrency(
      sumPrimaryAmounts(currentMonthExpenses, primaryCode),
      precision,
    )
    val previousTotalSpent = DecimalUtils.scaleToCurrency(
      sumPrimaryAmounts(previousMonthExpenses, primaryCode),
      precision,
    )
    val momDelta = DecimalUtils.scaleToCurrency(currentTotalSpent.subtract(previousTotalSpent), precision)
    val momPercent = computeDeltaPercent(momDelta, previousTotalSpent)

    val categoryComparisons = buildCategoryComparisons(
      currentExpenses = currentMonthExpenses,
      previousExpenses = previousMonthExpenses,
      categoriesById = categoriesById,
      primaryCode = primaryCode,
      precision = precision,
    )

    // 2. Weekday vs Weekend spending analysis (trailing 90 days up to today)
    val weekdayWeekend = buildWeekdayWeekendAnalysis(
      expenses = realizedPrimaryExpenses,
      primaryCode = primaryCode,
      precision = precision,
      today = today,
    )

    // 3. Recurring expenses detection
    val recurringExpenses = detectRecurringExpenses(
      expenses = realizedPrimaryExpenses,
      categoriesById = categoriesById,
      primaryCode = primaryCode,
      precision = precision,
    )

    // 4. Smart Alerts (Unusual spending + Upcoming 7-day Bills/EMIs + Budget 80%/100%)
    val alerts = LinkedHashMap<String, SmartAlert>()

    val (anomalyAlerts, hasBaseline) = detectUnusualSpendingAlerts(
      realizedExpenses = realizedPrimaryExpenses,
      currentMonth = currentMonth,
      categoriesById = categoriesById,
      primaryCode = primaryCode,
      precision = precision,
      today = today,
    )
    anomalyAlerts.forEach { alerts.putIfAbsent(it.id, it) }

    detectUpcomingBillAlerts(
      dedupedTxs = dedupedTxs,
      categoriesById = categoriesById,
      currencyPrecisionMap = currencyPrecisionMap,
      today = today,
    ).forEach { alerts.putIfAbsent(it.id, it) }

    detectUpcomingEmiAlerts(
      dedupedLoans = dedupedLoans,
      dedupedEmiPayments = dedupedEmiPayments,
      dedupedTxs = dedupedTxs,
      currencyPrecisionMap = currencyPrecisionMap,
      today = today,
    ).forEach { alerts.putIfAbsent(it.id, it) }

    detectBudgetAlerts(
      budgets = dedupedBudgets.filter { it.yearMonth == currentMonth.toString() },
      categoriesById = categoriesById,
      realizedTransactions = dedupedTxs.filter { it.isPaid && it.transactionDateEpochDay <= todayEpoch },
      currencyPrecisionMap = currencyPrecisionMap,
    ).forEach { alerts.putIfAbsent(it.id, it) }

    val sortedAlerts = alerts.values.sortedWith(
      compareBy<SmartAlert> { severityRank(it.severity) }
        .thenBy { it.dueEpochDay ?: Long.MAX_VALUE }
        .thenBy { it.id },
    )

    return SpendingIntelligenceReport(
      evaluatedCurrencyCode = primaryCode,
      currentMonthTotalSpent = currentTotalSpent,
      previousMonthTotalSpent = previousTotalSpent,
      monthOverMonthDelta = momDelta,
      monthOverMonthPercent = momPercent,
      categoryComparisons = categoryComparisons,
      weekdayWeekendAnalysis = weekdayWeekend,
      recurringExpenses = recurringExpenses,
      alerts = sortedAlerts,
      hasSufficientAnomalyBaseline = hasBaseline,
    )
  }

  private fun buildCategoryComparisons(
    currentExpenses: List<TransactionEntity>,
    previousExpenses: List<TransactionEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    primaryCode: String,
    precision: Int,
  ): List<CategoryMonthComparison> {
    val currByCat = mutableMapOf<Long, BigDecimal>()
    for (tx in currentExpenses) {
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      currByCat[tx.categoryId] = (currByCat[tx.categoryId] ?: BigDecimal.ZERO).add(amt)
    }

    val prevByCat = mutableMapOf<Long, BigDecimal>()
    for (tx in previousExpenses) {
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      prevByCat[tx.categoryId] = (prevByCat[tx.categoryId] ?: BigDecimal.ZERO).add(amt)
    }

    val allCategoryIds = (currByCat.keys + prevByCat.keys).distinct()
    return allCategoryIds.map { catId ->
      val curr = DecimalUtils.scaleToCurrency(currByCat[catId] ?: BigDecimal.ZERO, precision)
      val prev = DecimalUtils.scaleToCurrency(prevByCat[catId] ?: BigDecimal.ZERO, precision)
      val delta = DecimalUtils.scaleToCurrency(curr.subtract(prev), precision)
      val trend = when {
        prev <= BigDecimal.ZERO && curr > BigDecimal.ZERO -> CategorySpendTrend.NEW_THIS_MONTH
        delta > BigDecimal.ZERO -> CategorySpendTrend.INCREASED
        delta < BigDecimal.ZERO -> CategorySpendTrend.DECREASED
        else -> CategorySpendTrend.UNCHANGED
      }
      CategoryMonthComparison(
        categoryId = catId,
        categoryName = categoriesById[catId]?.displayNameEn ?: "Other",
        currencyCode = primaryCode,
        currentMonthSpent = curr,
        previousMonthSpent = prev,
        deltaAmount = delta,
        deltaPercent = computeDeltaPercent(delta, prev),
        trend = trend,
      )
    }.sortedWith(
      compareByDescending<CategoryMonthComparison> { it.currentMonthSpent }
        .thenByDescending { it.previousMonthSpent },
    )
  }

  private fun buildWeekdayWeekendAnalysis(
    expenses: List<TransactionEntity>,
    primaryCode: String,
    precision: Int,
    today: LocalDate,
  ): WeekdayWeekendAnalysis {
    val startEpoch = today.minusDays(89).toEpochDay()
    val todayEpoch = today.toEpochDay()
    val windowExpenses = expenses.filter { it.transactionDateEpochDay in startEpoch..todayEpoch }

    var weekdayTotal = BigDecimal.ZERO
    var weekendTotal = BigDecimal.ZERO
    var weekdayCount = 0
    var weekendCount = 0

    for (tx in windowExpenses) {
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      val dow = LocalDate.ofEpochDay(tx.transactionDateEpochDay).dayOfWeek
      if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
        weekendTotal = weekendTotal.add(amt)
        weekendCount++
      } else {
        weekdayTotal = weekdayTotal.add(amt)
        weekdayCount++
      }
    }

    val scaledWeekday = DecimalUtils.scaleToCurrency(weekdayTotal, precision)
    val scaledWeekend = DecimalUtils.scaleToCurrency(weekendTotal, precision)
    val combined = scaledWeekday.add(scaledWeekend)

    val weekdayShare = if (combined > BigDecimal.ZERO) DecimalUtils.percentage(scaledWeekday, combined) else BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    val weekendShare = if (combined > BigDecimal.ZERO) DecimalUtils.percentage(scaledWeekend, combined) else BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

    val avgWeekdayTx = if (weekdayCount > 0) DecimalUtils.divideExact(scaledWeekday, BigDecimal(weekdayCount), precision) else DecimalUtils.scaleToCurrency(BigDecimal.ZERO, precision)
    val avgWeekendTx = if (weekendCount > 0) DecimalUtils.divideExact(scaledWeekend, BigDecimal(weekendCount), precision) else DecimalUtils.scaleToCurrency(BigDecimal.ZERO, precision)

    val totalCount = weekdayCount + weekendCount
    if (totalCount < MIN_EXPENSES_FOR_WEEKDAY_WEEKEND || combined <= BigDecimal.ZERO) {
      return WeekdayWeekendAnalysis(
        currencyCode = primaryCode,
        evaluationWindowLabel = "Trailing 90 Days",
        weekdayTotal = scaledWeekday,
        weekendTotal = scaledWeekend,
        weekdayTransactionCount = weekdayCount,
        weekendTransactionCount = weekendCount,
        weekdaySharePercent = weekdayShare,
        weekendSharePercent = weekendShare,
        averagePerWeekdayTx = avgWeekdayTx,
        averagePerWeekendTx = avgWeekendTx,
        preference = SpendingDayPreference.INSUFFICIENT_DATA,
        insightSummary = "Record at least $MIN_EXPENSES_FOR_WEEKDAY_WEEKEND paid expenses in $primaryCode to analyze weekday vs weekend spending patterns.",
      )
    }

    // Compare per-day-type intensity (2 weekend days vs 5 weekday days -> neutral weekend share is ~28.6%)
    val preference = when {
      weekendShare >= BigDecimal("40.00") -> SpendingDayPreference.WEEKEND_HEAVY
      weekendShare <= BigDecimal("20.00") -> SpendingDayPreference.WEEKDAY_HEAVY
      else -> SpendingDayPreference.BALANCED
    }

    val summary = when (preference) {
      SpendingDayPreference.WEEKEND_HEAVY ->
        "Weekend spending accounts for ${weekendShare.toPlainString()}% of total outflow across $weekendCount transaction(s), higher than the 2-day share baseline."
      SpendingDayPreference.WEEKDAY_HEAVY ->
        "Weekday spending dominates at ${weekdayShare.toPlainString()}% of total outflow across $weekdayCount transaction(s)."
      SpendingDayPreference.BALANCED ->
        "Spending is balanced between weekdays (${weekdayShare.toPlainString()}%) and weekends (${weekendShare.toPlainString()}%)."
      SpendingDayPreference.INSUFFICIENT_DATA -> ""
    }

    return WeekdayWeekendAnalysis(
      currencyCode = primaryCode,
      evaluationWindowLabel = "Trailing 90 Days",
      weekdayTotal = scaledWeekday,
      weekendTotal = scaledWeekend,
      weekdayTransactionCount = weekdayCount,
      weekendTransactionCount = weekendCount,
      weekdaySharePercent = weekdayShare,
      weekendSharePercent = weekendShare,
      averagePerWeekdayTx = avgWeekdayTx,
      averagePerWeekendTx = avgWeekendTx,
      preference = preference,
      insightSummary = summary,
    )
  }

  private fun detectRecurringExpenses(
    expenses: List<TransactionEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    primaryCode: String,
    precision: Int,
  ): List<DetectedRecurringExpense> {
    if (expenses.isEmpty()) return emptyList()

    val groups = expenses
      .filter { tx -> tx.description.isNotBlank() || tx.isRecurring }
      .groupBy { tx ->
        val catName = categoriesById[tx.categoryId]?.displayNameEn ?: "expense"
        val normalizedLabel = tx.description.trim().lowercase().ifEmpty { catName.lowercase() }
        "${tx.categoryId}|$normalizedLabel"
      }

    val detected = mutableListOf<DetectedRecurringExpense>()

    for ((key, groupTxs) in groups) {
      val sorted = groupTxs.sortedBy { it.transactionDateEpochDay }
      val anyExplicitRecurring = sorted.any { it.isRecurring }
      val categoryId = sorted.first().categoryId
      val categoryName = categoriesById[categoryId]?.displayNameEn ?: "Other"
      val displayTitle = sorted.last().description.trim().ifEmpty { categoryName }

      val amounts = sorted.mapNotNull { resolvePrimaryAmountOrNull(it, primaryCode) }
      if (amounts.isEmpty()) continue
      val sum = amounts.fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }
      val avg = DecimalUtils.divideExact(sum, BigDecimal(amounts.size), precision)

      if (sorted.size == 1) {
        if (!anyExplicitRecurring) continue
        val onlyTx = sorted.first()
        val interval = (onlyTx.recurrenceIntervalDays ?: 30).coerceAtLeast(1)
        detected.add(
          DetectedRecurringExpense(
            key = key,
            title = displayTitle,
            categoryId = categoryId,
            categoryName = categoryName,
            currencyCode = primaryCode,
            averageAmount = avg,
            occurrenceCount = 1,
            estimatedIntervalDays = interval,
            cadence = classifyCadence(interval),
            lastTransactionEpochDay = onlyTx.transactionDateEpochDay,
            nextEstimatedDueEpochDay = onlyTx.transactionDateEpochDay + interval,
            explicitlyMarkedRecurring = true,
          ),
        )
        continue
      }

      // Compute intervals between distinct occurrence dates (skip same-day splits)
      val intervals = mutableListOf<Int>()
      for (i in 1 until sorted.size) {
        val gap = (sorted[i].transactionDateEpochDay - sorted[i - 1].transactionDateEpochDay).toInt()
        if (gap >= 5) intervals.add(gap)
      }
      if (intervals.isEmpty() && !anyExplicitRecurring) continue

      val avgInterval = if (intervals.isNotEmpty()) {
        intervals.sum() / intervals.size
      } else {
        (sorted.last().recurrenceIntervalDays ?: 30)
      }.coerceAtLeast(1)

      // Check interval consistency & amount stability for statistically detected patterns
      val intervalConsistent = if (intervals.size >= 2) {
        intervals.all { abs(it - avgInterval) <= maxOf(5, avgInterval / 3) }
      } else {
        avgInterval in 5..45
      }

      val maxAmt = amounts.maxOrNull() ?: BigDecimal.ZERO
      val minAmt = amounts.minOrNull() ?: BigDecimal.ZERO
      val amountConsistent = if (avg > BigDecimal.ZERO) {
        val spread = maxAmt.subtract(minAmt)
        spread <= avg.multiply(BigDecimal("0.35"))
      } else {
        false
      }

      if (anyExplicitRecurring || (intervalConsistent && amountConsistent)) {
        val lastDay = sorted.last().transactionDateEpochDay
        detected.add(
          DetectedRecurringExpense(
            key = key,
            title = displayTitle,
            categoryId = categoryId,
            categoryName = categoryName,
            currencyCode = primaryCode,
            averageAmount = avg,
            occurrenceCount = sorted.size,
            estimatedIntervalDays = avgInterval,
            cadence = classifyCadence(avgInterval),
            lastTransactionEpochDay = lastDay,
            nextEstimatedDueEpochDay = lastDay + avgInterval,
            explicitlyMarkedRecurring = anyExplicitRecurring,
          ),
        )
      }
    }

    return detected.sortedWith(
      compareByDescending<DetectedRecurringExpense> { it.occurrenceCount }
        .thenByDescending { it.averageAmount },
    )
  }

  private fun detectUnusualSpendingAlerts(
    realizedExpenses: List<TransactionEntity>,
    currentMonth: YearMonth,
    categoriesById: Map<Long, CategoryEntity>,
    primaryCode: String,
    precision: Int,
    today: LocalDate,
  ): Pair<List<SmartAlert>, Boolean> {
    val currentMonthStart = currentMonth.atDay(1).toEpochDay()
    val todayEpoch = today.toEpochDay()
    val priorExpenses = realizedExpenses.filter { it.transactionDateEpochDay < currentMonthStart }

    val priorMonthsDistinct = priorExpenses
      .map { YearMonth.from(LocalDate.ofEpochDay(it.transactionDateEpochDay)) }
      .distinct()
      .size

    val alerts = mutableListOf<SmartAlert>()
    var anyBaselineSufficient = false

    // A. Category monthly spike detection (requires >= 2 historical months in that category)
    val currentByCategory = realizedExpenses
      .filter { it.transactionDateEpochDay in currentMonthStart..todayEpoch }
      .groupBy { it.categoryId }

    val priorByCategory = priorExpenses.groupBy { it.categoryId }

    for ((catId, currTxs) in currentByCategory) {
      val histTxs = priorByCategory[catId].orEmpty()
      val histByMonth = histTxs.groupBy {
        YearMonth.from(LocalDate.ofEpochDay(it.transactionDateEpochDay))
      }
      if (histByMonth.size < MIN_PRIOR_MONTHS_FOR_CATEGORY_SPIKE) continue

      anyBaselineSufficient = true
      val histMonthlyTotals = histByMonth.values.map { monthList ->
        sumPrimaryAmounts(monthList, primaryCode)
      }.filter { it > BigDecimal.ZERO }
      if (histMonthlyTotals.size < MIN_PRIOR_MONTHS_FOR_CATEGORY_SPIKE) continue

      val histSum = histMonthlyTotals.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
      val histAvg = DecimalUtils.divideExact(histSum, BigDecimal(histMonthlyTotals.size), precision)
      if (histAvg <= BigDecimal.ZERO) continue

      val currTotal = DecimalUtils.scaleToCurrency(sumPrimaryAmounts(currTxs, primaryCode), precision)
      if (currTotal >= histAvg.multiply(CATEGORY_SPIKE_MULTIPLIER)) {
        val catName = categoriesById[catId]?.displayNameEn ?: "Category"
        val pctOver = DecimalUtils.percentage(currTotal.subtract(histAvg), histAvg)
        alerts.add(
          SmartAlert(
            id = "anomaly_cat_${catId}_${currentMonth}",
            type = SmartAlertType.UNUSUAL_CATEGORY_SPIKE,
            severity = AlertSeverity.WARNING,
            title = "Unusual $catName Spending",
            message = "$catName spending this month ($primaryCode ${currTotal.toPlainString()}) is ${pctOver.toPlainString()}% above your ${histMonthlyTotals.size}-month average ($primaryCode ${histAvg.toPlainString()}).",
            recommendation = "Review recent $catName transactions and pause discretionary purchases in this category.",
            currencyCode = primaryCode,
            amount = currTotal,
          ),
        )
      }
    }

    // B. Single large outlier transaction detection in last 7 days (requires >= 5 prior transactions in same category)
    val recentStart = today.minusDays(UPCOMING_WINDOW_DAYS).toEpochDay()
    val recentTxs = realizedExpenses.filter { it.transactionDateEpochDay in recentStart..todayEpoch }

    for (tx in recentTxs) {
      val txAmount = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      val sameCatPrior = realizedExpenses.filter {
        it.categoryId == tx.categoryId &&
          it.id != tx.id &&
          it.transactionDateEpochDay < tx.transactionDateEpochDay
      }
      if (sameCatPrior.size < MIN_PRIOR_TX_FOR_SINGLE_ANOMALY) continue

      anyBaselineSufficient = true
      val priorSum = sumPrimaryAmounts(sameCatPrior, primaryCode)
      val priorAvg = DecimalUtils.divideExact(priorSum, BigDecimal(sameCatPrior.size), precision)
      if (priorAvg <= BigDecimal.ZERO) continue

      if (txAmount >= priorAvg.multiply(SINGLE_TX_SPIKE_MULTIPLIER)) {
        val catName = categoriesById[tx.categoryId]?.displayNameEn ?: "Expense"
        val label = tx.description.trim().ifEmpty { catName }
        val txKey = if (tx.id > 0L) "${tx.id}" else "${tx.categoryId}_${tx.transactionDateEpochDay}_${txAmount.toPlainString()}"
        alerts.add(
          SmartAlert(
            id = "anomaly_tx_$txKey",
            type = SmartAlertType.UNUSUAL_SINGLE_TRANSACTION,
            severity = AlertSeverity.WARNING,
            title = "Unusually Large $catName Expense",
            message = "\"$label\" ($primaryCode ${DecimalUtils.scaleToCurrency(txAmount, precision).toPlainString()}) is over 2x your typical $catName expense ($primaryCode ${priorAvg.toPlainString()} across ${sameCatPrior.size} prior entries).",
            recommendation = "Verify this charge and check your Safe-to-Spend limit for the rest of the month.",
            currencyCode = primaryCode,
            amount = DecimalUtils.scaleToCurrency(txAmount, precision),
            dueEpochDay = tx.transactionDateEpochDay,
          ),
        )
      }
    }

    if (priorMonthsDistinct >= MIN_PRIOR_MONTHS_FOR_CATEGORY_SPIKE) {
      anyBaselineSufficient = true
    }

    return alerts to anyBaselineSufficient
  }

  private fun detectUpcomingBillAlerts(
    dedupedTxs: List<TransactionEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    currencyPrecisionMap: Map<String, Int>,
    today: LocalDate,
  ): List<SmartAlert> {
    val todayEpoch = today.toEpochDay()
    val horizonEpoch = today.plusDays(UPCOMING_WINDOW_DAYS).toEpochDay()

    return dedupedTxs.asSequence()
      .filter { tx ->
        tx.type == TransactionType.EXPENSE &&
          tx.isUpcomingBill &&
          !tx.isPaid &&
          tx.transactionDateEpochDay <= horizonEpoch
      }
      .map { tx ->
        val code = tx.currencyCode.uppercase()
        val precision = currencyPrecisionMap[code] ?: 2
        val scaledAmt = DecimalUtils.scaleToCurrency(tx.amount, precision)
        val catName = categoriesById[tx.categoryId]?.displayNameEn ?: "Bill"
        val titleText = tx.description.trim().ifEmpty { catName }
        val isOverdue = tx.transactionDateEpochDay < todayEpoch
        val daysDelta = abs(tx.transactionDateEpochDay - todayEpoch)
        val dueMsg = when {
          isOverdue -> "Overdue by $daysDelta day(s)"
          daysDelta == 0L -> "Due today"
          else -> "Due in $daysDelta day(s) (${LocalDate.ofEpochDay(tx.transactionDateEpochDay)})"
        }
        val uniqueId = if (tx.id > 0L) "bill_${tx.id}" else "bill_${titleText.lowercase()}_${tx.transactionDateEpochDay}_$code"
        SmartAlert(
          id = uniqueId,
          type = if (isOverdue) SmartAlertType.OVERDUE_BILL else SmartAlertType.UPCOMING_BILL_DUE,
          severity = if (isOverdue) AlertSeverity.HIGH else AlertSeverity.WARNING,
          title = if (isOverdue) "Overdue Bill: $titleText" else "Upcoming Bill: $titleText",
          message = "$dueMsg • Amount: $code ${scaledAmt.toPlainString()}",
          recommendation = "Pay or mark this bill as paid once settled so Safe-to-Spend stays accurate.",
          currencyCode = code,
          amount = scaledAmt,
          dueEpochDay = tx.transactionDateEpochDay,
        )
      }
      .toList()
  }

  private fun detectUpcomingEmiAlerts(
    dedupedLoans: List<LoanEntity>,
    dedupedEmiPayments: List<EmiPaymentEntity>,
    dedupedTxs: List<TransactionEntity>,
    currencyPrecisionMap: Map<String, Int>,
    today: LocalDate,
  ): List<SmartAlert> {
    val todayEpoch = today.toEpochDay()
    val horizonEpoch = today.plusDays(UPCOMING_WINDOW_DAYS).toEpochDay()

    val results = mutableListOf<SmartAlert>()
    for (loan in dedupedLoans) {
      if (loan.status != LoanStatus.ACTIVE || loan.outstandingPrincipal <= BigDecimal.ZERO) continue
      if (loan.emiAmount <= BigDecimal.ZERO) continue
      if (loan.nextDueDateEpochDay > horizonEpoch) continue

      val dueYearMonth = YearMonth.from(LocalDate.ofEpochDay(loan.nextDueDateEpochDay))
      val cycleWindowStart = loan.nextDueDateEpochDay - 15L
      val cycleWindowEnd = loan.nextDueDateEpochDay + 5L

      // Never alert if EMI has already been paid for this due cycle (via EmiPaymentEntity or linked paid Transaction)
      val alreadyPaidByEmiTable = dedupedEmiPayments.any { p ->
        p.loanId == loan.id && (
          YearMonth.from(LocalDate.ofEpochDay(p.paymentDateEpochDay)) == dueYearMonth ||
            p.paymentDateEpochDay in cycleWindowStart..cycleWindowEnd
          )
      }
      val alreadyPaidByTransaction = dedupedTxs.any { tx ->
        tx.isPaid &&
          tx.linkedLoanId == loan.id && (
          YearMonth.from(LocalDate.ofEpochDay(tx.transactionDateEpochDay)) == dueYearMonth ||
            tx.transactionDateEpochDay in cycleWindowStart..cycleWindowEnd
          )
      }
      if (alreadyPaidByEmiTable || alreadyPaidByTransaction) continue

      val code = loan.currencyCode.uppercase()
      val precision = currencyPrecisionMap[code] ?: 2
      val emiScaled = DecimalUtils.scaleToCurrency(loan.emiAmount, precision)
      val daysUntil = loan.nextDueDateEpochDay - todayEpoch
      val timing = when {
        daysUntil < 0L -> "Overdue by ${abs(daysUntil)} day(s)"
        daysUntil == 0L -> "Due today"
        else -> "Due in $daysUntil day(s) (${LocalDate.ofEpochDay(loan.nextDueDateEpochDay)})"
      }

      results.add(
        SmartAlert(
          id = "emi_${loan.id}_${loan.nextDueDateEpochDay}",
          type = SmartAlertType.UPCOMING_EMI_DUE,
          severity = if (daysUntil <= 1L) AlertSeverity.HIGH else AlertSeverity.WARNING,
          title = "Upcoming EMI: ${loan.loanName}",
          message = "$timing • EMI: $code ${emiScaled.toPlainString()} (${loan.lender})",
          recommendation = "Keep $code ${emiScaled.toPlainString()} reserved in your balance for timely EMI payment.",
          currencyCode = code,
          amount = emiScaled,
          dueEpochDay = loan.nextDueDateEpochDay,
        ),
      )
    }
    return results
  }

  private fun detectBudgetAlerts(
    budgets: List<BudgetEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    realizedTransactions: List<TransactionEntity>,
    currencyPrecisionMap: Map<String, Int>,
  ): List<SmartAlert> {
    if (budgets.isEmpty()) return emptyList()
    val statuses = budgetAndSavingsCalculator.computeBudgetStatuses(
      budgets = budgets,
      categories = categoriesById,
      transactions = realizedTransactions,
      currencyPrecisionMap = currencyPrecisionMap,
    )

    return statuses.mapNotNull { b ->
      when (b.status) {
        BudgetHealthStatus.EXCEEDED -> SmartAlert(
          id = "budget_${b.categoryId}_${b.yearMonth}_${b.currencyCode.uppercase()}",
          type = SmartAlertType.BUDGET_EXCEEDED_100,
          severity = AlertSeverity.HIGH,
          title = "Budget Exceeded: ${b.categoryNameEn}",
          message = "Spent ${b.currencyCode} ${b.actualSpent.toPlainString()} out of ${b.currencyCode} ${b.effectiveLimit.toPlainString()} (${b.utilizationPercent.toPlainString()}%).",
          recommendation = "Pause new ${b.categoryNameEn} expenses or rebalance from another category.",
          currencyCode = b.currencyCode,
          amount = b.actualSpent,
        )

        BudgetHealthStatus.APPROACHING_LIMIT -> SmartAlert(
          id = "budget_${b.categoryId}_${b.yearMonth}_${b.currencyCode.uppercase()}",
          type = SmartAlertType.BUDGET_APPROACHING_80,
          severity = AlertSeverity.WARNING,
          title = "Budget Alert (${b.utilizationPercent.toPlainString()}%): ${b.categoryNameEn}",
          message = "Remaining ${b.currencyCode} ${b.remainingAmount.toPlainString()} out of ${b.currencyCode} ${b.effectiveLimit.toPlainString()}.",
          recommendation = "Pace remaining ${b.categoryNameEn} spending to stay within your monthly budget.",
          currencyCode = b.currencyCode,
          amount = b.remainingAmount,
        )

        BudgetHealthStatus.NORMAL -> null
      }
    }
  }

  private fun classifyCadence(intervalDays: Int): RecurringCadence = when (intervalDays) {
    in 5..10 -> RecurringCadence.WEEKLY
    in 11..19 -> RecurringCadence.BIWEEKLY
    in 24..35 -> RecurringCadence.MONTHLY
    else -> RecurringCadence.PERIODIC
  }

  private fun severityRank(severity: AlertSeverity): Int = when (severity) {
    AlertSeverity.HIGH -> 0
    AlertSeverity.WARNING -> 1
    AlertSeverity.INFO -> 2
  }

  private fun computeDeltaPercent(delta: BigDecimal, previous: BigDecimal): BigDecimal? {
    if (previous <= BigDecimal.ZERO) return null
    return delta.multiply(HUNDRED, DecimalUtils.FINANCIAL_MATH_CONTEXT)
      .divide(previous, 2, RoundingMode.HALF_UP)
  }

  private fun sumPrimaryAmounts(txs: List<TransactionEntity>, primaryCode: String): BigDecimal {
    var sum = BigDecimal.ZERO
    for (tx in txs) {
      val amt = resolvePrimaryAmountOrNull(tx, primaryCode) ?: continue
      sum = sum.add(amt)
    }
    return sum
  }

  private fun resolvePrimaryAmountOrNull(tx: TransactionEntity, primaryCode: String): BigDecimal? {
    return when {
      tx.currencyCode.equals(primaryCode, ignoreCase = true) -> tx.amount
      tx.primaryCurrencyAtEntry.equals(primaryCode, ignoreCase = true) && tx.convertedPrimaryAmount != null ->
        tx.convertedPrimaryAmount
      else -> null
    }
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
}
