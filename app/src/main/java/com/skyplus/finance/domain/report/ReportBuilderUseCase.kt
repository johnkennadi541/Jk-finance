package com.skyplus.finance.domain.report

import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class ReportBuilderUseCase @Inject constructor(
  private val budgetAndSavingsCalculator: BudgetAndSavingsCalculator,
) {

  fun defaultDatesForPeriod(
    period: ReportPeriodType,
    today: LocalDate = LocalDate.now(),
  ): Pair<LocalDate, LocalDate> = when (period) {
    ReportPeriodType.DAILY -> today to today
    ReportPeriodType.WEEKLY -> today.minusDays(6) to today
    ReportPeriodType.MONTHLY -> YearMonth.from(today).atDay(1) to YearMonth.from(today).atEndOfMonth()
    ReportPeriodType.YEARLY -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
    ReportPeriodType.CUSTOM -> YearMonth.from(today).atDay(1) to today
  }

  fun build(
    snapshot: DashboardSnapshot,
    request: ReportRequest,
    today: LocalDate = LocalDate.now(),
  ): FinancialReportData {
    val startDay = minOf(request.startEpochDay, request.endEpochDay)
    val endDay = maxOf(request.startEpochDay, request.endEpochDay)
    val displayCode = request.displayCurrencyCode.uppercase()
    val displayCurrency = snapshot.currenciesByCode[displayCode]
    val displayPrecision = displayCurrency?.decimalPrecision ?: 2
    val precisionMap = snapshot.allCurrencies.associate { it.code.uppercase() to it.decimalPrecision }

    val paidTxs = snapshot.allTransactions.filter { it.isPaid }
    val priorTxs = paidTxs.filter { it.transactionDateEpochDay < startDay }
    val periodTxs = paidTxs.filter { it.transactionDateEpochDay in startDay..endDay }

    val openingBalance = sumNetInDisplayCurrency(priorTxs, displayCode, snapshot.currenciesByCode)
    val totalIncome = sumByTypeInDisplayCurrency(periodTxs, TransactionType.INCOME, displayCode, snapshot.currenciesByCode)
    val totalExpenses = sumByTypeInDisplayCurrency(periodTxs, TransactionType.EXPENSE, displayCode, snapshot.currenciesByCode)
    val savingsAllocated = sumByTypeInDisplayCurrency(periodTxs, TransactionType.SAVINGS_ALLOCATION, displayCode, snapshot.currenciesByCode)
    val netSavings = totalIncome.subtract(totalExpenses)
    val closingBalance = openingBalance.add(totalIncome).subtract(totalExpenses).subtract(savingsAllocated)

    val unconvertedCount = periodTxs.count {
      !it.currencyCode.equals(displayCode, ignoreCase = true) &&
        resolveConvertedAmount(it, displayCode, snapshot.currenciesByCode) == null
    }

    val categoryExpenses = buildCategoryBreakdown(
      txs = periodTxs.filter { it.type == TransactionType.EXPENSE },
      totalForType = totalExpenses,
      snapshot = snapshot,
      displayCode = displayCode,
      displayCurrency = displayCurrency,
    )

    val incomeSources = buildCategoryBreakdown(
      txs = periodTxs.filter { it.type == TransactionType.INCOME },
      totalForType = totalIncome,
      snapshot = snapshot,
      displayCode = displayCode,
      displayCurrency = displayCurrency,
    )

    val budgetRows = snapshot.budgetStatuses.map { b ->
      val cur = snapshot.currenciesByCode[b.currencyCode.uppercase()]
      ReportBudgetRow(
        categoryName = b.categoryNameEn,
        yearMonth = b.yearMonth,
        limitFormatted = CurrencyFormatter.formatWithEntity(b.effectiveLimit, cur, b.currencyCode),
        spentFormatted = CurrencyFormatter.formatWithEntity(b.actualSpent, cur, b.currencyCode),
        remainingFormatted = CurrencyFormatter.formatWithEntity(b.remainingAmount, cur, b.currencyCode),
        utilizationPercent = b.utilizationPercent,
        status = b.status,
      )
    }

    val loansById = snapshot.loans.associateBy { it.id }
    val loanRows = snapshot.loans.map { l ->
      val cur = snapshot.currenciesByCode[l.currencyCode.uppercase()]
      ReportLoanRow(
        loanName = l.loanName,
        lender = l.lender,
        outstandingFormatted = CurrencyFormatter.formatWithEntity(l.outstandingPrincipal, cur, l.currencyCode),
        emiFormatted = CurrencyFormatter.formatWithEntity(l.emiAmount, cur, l.currencyCode),
        annualRatePercent = "${l.annualInterestRatePercent.toPlainString()}%",
        nextDueDate = LocalDate.ofEpochDay(l.nextDueDateEpochDay).toString(),
        remainingMonths = l.remainingTenureMonths,
        status = l.status.name,
      )
    }

    val periodEmis = snapshot.emiPayments.filter { it.paymentDateEpochDay in startDay..endDay }
    val emiPaymentRows = periodEmis.map { emi ->
      val cur = snapshot.currenciesByCode[emi.currencyCode.uppercase()]
      ReportEmiPaymentRow(
        date = LocalDate.ofEpochDay(emi.paymentDateEpochDay).toString(),
        loanName = loansById[emi.loanId]?.loanName ?: "Loan #${emi.loanId}",
        totalPaidFormatted = CurrencyFormatter.formatWithEntity(emi.totalPaidAmount, cur, emi.currencyCode),
        principalFormatted = CurrencyFormatter.formatWithEntity(emi.principalComponent, cur, emi.currencyCode),
        interestFormatted = CurrencyFormatter.formatWithEntity(emi.interestComponent, cur, emi.currencyCode),
        extraPrepaymentFormatted = CurrencyFormatter.formatWithEntity(emi.extraPrepaymentAmount, cur, emi.currencyCode),
      )
    }
    val totalEmiPaidInDisplay = periodEmis
      .filter { it.currencyCode.equals(displayCode, ignoreCase = true) }
      .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.totalPaidAmount) }
    val totalPrincipalReducedInDisplay = periodEmis
      .filter { it.currencyCode.equals(displayCode, ignoreCase = true) }
      .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.principalComponent) }

    // Net Worth accounting for period: Net Worth evolves by net earnings + debt principal paydown
    val netWorthEnd = snapshot.totalNetWorth
    val netWorthPeriodDelta = netSavings.add(totalPrincipalReducedInDisplay)
    val netWorthStart = netWorthEnd.subtract(netWorthPeriodDelta)

    // Financial Health summary & trends
    val healthReport = snapshot.intelligence?.healthReport
    val healthScore = healthReport?.overallScore
    val healthBandLabel = healthReport?.band?.label ?: "Insufficient Data"
    val healthSavingsRateStr = healthReport?.metrics?.savingsRatePercent?.toPlainString()?.let { "$it%" } ?: "0%"
    val healthDebtToIncomeStr = healthReport?.metrics?.emiToIncomePercent?.toPlainString()?.let { "$it%" } ?: "0%"
    val healthEmergencyMonthsStr = healthReport?.metrics?.emergencyMonthsCovered?.toPlainString()?.let { "$it mo" } ?: "0.0 mo"
    val healthTrendSummary = when {
      healthScore == null -> "Insufficient transaction history for automated trend scoring."
      netWorthPeriodDelta > BigDecimal.ZERO && healthScore >= 60 ->
        "Positive wealth trajectory: score $healthScore/100 ($healthBandLabel) with positive net worth growth."
      netWorthPeriodDelta < BigDecimal.ZERO ->
        "Net worth contracted in this period; monitor discretionary outflow and EMI ratio ($healthDebtToIncomeStr)."
      else ->
        "Financial health score stands at $healthScore/100 ($healthBandLabel); savings rate $healthSavingsRateStr."
    }

    val goalRows = snapshot.savingsGoals.map { g ->
      val cur = snapshot.currenciesByCode[g.currencyCode.uppercase()]
      ReportGoalRow(
        goalName = g.goalName,
        category = g.goalCategory.name.replace("_", " "),
        savedFormatted = CurrencyFormatter.formatWithEntity(g.currentSavedAmount, cur, g.currencyCode),
        targetFormatted = CurrencyFormatter.formatWithEntity(g.targetAmount, cur, g.currencyCode),
        progressPercent = DecimalUtils.percentage(g.currentSavedAmount, g.targetAmount),
        targetDate = g.targetDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "Flexible",
      )
    }

    val currencyHoldings = budgetAndSavingsCalculator.computeCurrencyBreakdowns(
      transactions = periodTxs,
      yearMonth = null,
      currencyPrecisionMap = precisionMap,
    )

    val txRows = periodTxs.map { tx ->
      val origCur = snapshot.currenciesByCode[tx.currencyCode.uppercase()]
      val catName = snapshot.categoriesById[tx.categoryId]?.displayNameEn ?: "Other"
      val converted = if (!tx.currencyCode.equals(displayCode, ignoreCase = true)) {
        resolveConvertedAmount(tx, displayCode, snapshot.currenciesByCode)
      } else {
        null
      }
      ReportTransactionRow(
        date = LocalDate.ofEpochDay(tx.transactionDateEpochDay).toString(),
        type = tx.type,
        categoryName = catName,
        description = tx.description.ifBlank { catName },
        originalFormatted = CurrencyFormatter.formatWithEntity(tx.amount, origCur, tx.currencyCode),
        originalCurrencyCode = tx.currencyCode,
        convertedDisplayFormatted = converted?.let {
          CurrencyFormatter.formatWithEntity(it, displayCurrency, displayCode)
        },
        conversionNote = tx.exchangeRateSource,
      )
    }

    val startStr = LocalDate.ofEpochDay(startDay).toString()
    val endStr = LocalDate.ofEpochDay(endDay).toString()
    val effectiveSections = if (request.detailLevel == ReportDetailLevel.SUMMARY) {
      request.includedSections - ReportSection.TRANSACTION_HISTORY
    } else {
      request.includedSections
    }

    return FinancialReportData(
      title = "SKYPLUS FINANCE — ${request.periodType.label} Financial Report",
      periodLabel = request.periodType.label,
      dateRangeText = "$startStr to $endStr",
      generatedDateText = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
      displayCurrencyCode = displayCode,
      detailLevel = request.detailLevel,
      includedSections = effectiveSections,
      openingBalanceFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(openingBalance, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      closingBalanceFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(closingBalance, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      totalIncomeFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(totalIncome, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      totalExpensesFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(totalExpenses, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      netSavingsFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(netSavings, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      savingsAllocatedFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(savingsAllocated, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      categoryExpenses = categoryExpenses,
      incomeSources = incomeSources,
      budgetRows = budgetRows,
      loanRows = loanRows,
      savingsGoalRows = goalRows,
      currencyHoldings = currencyHoldings,
      unconvertedForeignTxCount = unconvertedCount,
      transactionRows = txRows,
      emiPaymentRows = emiPaymentRows,
      totalEmiPaidInPeriodFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(totalEmiPaidInDisplay, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      netWorthStartFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(netWorthStart, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      netWorthEndFormatted = CurrencyFormatter.formatWithEntity(
        DecimalUtils.scaleToCurrency(netWorthEnd, displayPrecision),
        displayCurrency,
        displayCode,
      ),
      netWorthChangeFormatted = (if (netWorthPeriodDelta >= BigDecimal.ZERO) "+" else "") +
        CurrencyFormatter.formatWithEntity(
          DecimalUtils.scaleToCurrency(netWorthPeriodDelta, displayPrecision),
          displayCurrency,
          displayCode,
        ),
      netWorthChangePositive = netWorthPeriodDelta >= BigDecimal.ZERO,
      healthScore = healthScore,
      healthBandLabel = healthBandLabel,
      healthSavingsRatePercent = healthSavingsRateStr,
      healthDebtToIncomePercent = healthDebtToIncomeStr,
      healthEmergencyMonths = healthEmergencyMonthsStr,
      healthTrendSummary = healthTrendSummary,
    )
  }

  private fun buildCategoryBreakdown(
    txs: List<TransactionEntity>,
    totalForType: BigDecimal,
    snapshot: DashboardSnapshot,
    displayCode: String,
    displayCurrency: CurrencyEntity?,
  ): List<ReportBreakdownRow> {
    val grouped = mutableMapOf<Long, BigDecimal>()
    for (tx in txs) {
      val amt = resolveConvertedAmount(tx, displayCode, snapshot.currenciesByCode) ?: continue
      grouped[tx.categoryId] = (grouped[tx.categoryId] ?: BigDecimal.ZERO).add(amt)
    }
    return grouped.entries
      .sortedByDescending { it.value }
      .map { (catId, amount) ->
        val name = snapshot.categoriesById[catId]?.displayNameEn ?: "Other"
        ReportBreakdownRow(
          name = name,
          formattedAmount = CurrencyFormatter.formatWithEntity(amount, displayCurrency, displayCode),
          amount = amount,
          sharePercent = DecimalUtils.percentage(amount, totalForType),
        )
      }
  }

  private fun sumNetInDisplayCurrency(
    txs: List<TransactionEntity>,
    displayCode: String,
    currencies: Map<String, CurrencyEntity>,
  ): BigDecimal {
    var net = BigDecimal.ZERO
    for (tx in txs) {
      val amt = resolveConvertedAmount(tx, displayCode, currencies) ?: continue
      net = when (tx.type) {
        TransactionType.INCOME -> net.add(amt)
        TransactionType.EXPENSE -> net.subtract(amt)
        TransactionType.SAVINGS_ALLOCATION -> net.subtract(amt)
      }
    }
    return net
  }

  private fun sumByTypeInDisplayCurrency(
    txs: List<TransactionEntity>,
    type: TransactionType,
    displayCode: String,
    currencies: Map<String, CurrencyEntity>,
  ): BigDecimal {
    return txs.asSequence()
      .filter { it.type == type }
      .mapNotNull { resolveConvertedAmount(it, displayCode, currencies) }
      .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }
  }

  private fun resolveConvertedAmount(
    tx: TransactionEntity,
    displayCode: String,
    currencies: Map<String, CurrencyEntity>,
  ): BigDecimal? {
    if (tx.currencyCode.equals(displayCode, ignoreCase = true)) return tx.amount
    if (tx.primaryCurrencyAtEntry.equals(displayCode, ignoreCase = true) && tx.convertedPrimaryAmount != null) {
      return tx.convertedPrimaryAmount
    }
    val rate = currencies[tx.currencyCode.uppercase()]?.cachedRateToBase
    if (rate != null && rate > BigDecimal.ZERO) {
      return tx.amount.multiply(rate, DecimalUtils.FINANCIAL_MATH_CONTEXT)
    }
    return null
  }
}
