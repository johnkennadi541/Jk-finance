package com.skyplus.finance.domain.usecase

import androidx.compose.ui.graphics.Color
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.designsystem.BarSeriesPoint
import com.skyplus.finance.designsystem.CategorySlice
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.TrendPoint
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class PortfolioChartsBundle(
  val incomeVsExpenseBars: List<BarSeriesPoint>,
  val netTrendPoints: List<TrendPoint>,
  val categorySlices: List<CategorySlice>,
  val periodIncome: BigDecimal,
  val periodExpense: BigDecimal,
  val periodNetSavings: BigDecimal,
)

/**
 * Builds interactive chart datasets exclusively from real user transactions.
 * Never fabricates sample numbers.
 */
class PortfolioChartBuilder @Inject constructor() {

  private val slicePalette = listOf(
    SkyPlusBrandColors.RoyalBlue,
    SkyPlusBrandColors.EmeraldGreen,
    SkyPlusBrandColors.SoftCyan,
    Color(0xFF3B82F6),
    Color(0xFF14B8A6),
    Color(0xFF6366F1),
  )

  fun buildCharts(
    transactions: List<TransactionEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    currenciesByCode: Map<String, CurrencyEntity>,
    primaryCurrencyCode: String,
    periodType: ReportPeriodType,
    customStartEpochDay: Long? = null,
    customEndEpochDay: Long? = null,
    today: LocalDate = LocalDate.now(),
  ): PortfolioChartsBundle {
    val primaryCode = primaryCurrencyCode.uppercase()
    val primaryCur = currenciesByCode[primaryCode]
    val primaryPrecision = primaryCur?.decimalPrecision ?: 2

    val (startDate, endDate) = when (periodType) {
      ReportPeriodType.DAILY -> today to today
      ReportPeriodType.WEEKLY -> today.minusDays(6) to today
      ReportPeriodType.MONTHLY -> YearMonth.from(today).atDay(1) to YearMonth.from(today).atEndOfMonth()
      ReportPeriodType.YEARLY -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
      ReportPeriodType.CUSTOM -> {
        val s = customStartEpochDay?.let { LocalDate.ofEpochDay(it) } ?: YearMonth.from(today).atDay(1)
        val e = customEndEpochDay?.let { LocalDate.ofEpochDay(it) } ?: today
        if (s.isAfter(e)) e to s else s to e
      }
    }

    val startEpoch = startDate.toEpochDay()
    val endEpoch = endDate.toEpochDay()

    val paidPeriodTxs = transactions.filter {
      it.isPaid && it.transactionDateEpochDay in startEpoch..endEpoch
    }

    val periodIncome = paidPeriodTxs
      .filter { it.type == TransactionType.INCOME }
      .mapNotNull { resolveAmount(it, primaryCode, currenciesByCode) }
      .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }

    val periodExpense = paidPeriodTxs
      .filter { it.type == TransactionType.EXPENSE }
      .mapNotNull { resolveAmount(it, primaryCode, currenciesByCode) }
      .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }

    val periodNetSavings = periodIncome.subtract(periodExpense)

    val bars = buildBars(periodType, startDate, endDate, paidPeriodTxs, primaryCode, currenciesByCode)
    val trend = buildTrend(bars)
    val slices = buildCategorySlices(
      paidPeriodTxs = paidPeriodTxs,
      totalExpense = periodExpense,
      categoriesById = categoriesById,
      primaryCurrency = primaryCur,
      primaryCode = primaryCode,
      primaryPrecision = primaryPrecision,
      currenciesByCode = currenciesByCode,
    )

    return PortfolioChartsBundle(
      incomeVsExpenseBars = bars,
      netTrendPoints = trend,
      categorySlices = slices,
      periodIncome = DecimalUtils.scaleToCurrency(periodIncome, primaryPrecision),
      periodExpense = DecimalUtils.scaleToCurrency(periodExpense, primaryPrecision),
      periodNetSavings = DecimalUtils.scaleToCurrency(periodNetSavings, primaryPrecision),
    )
  }

  private fun buildBars(
    periodType: ReportPeriodType,
    startDate: LocalDate,
    endDate: LocalDate,
    paidTxs: List<TransactionEntity>,
    primaryCode: String,
    currenciesByCode: Map<String, CurrencyEntity>,
  ): List<BarSeriesPoint> {
    if (paidTxs.isEmpty()) return emptyList()

    return when (periodType) {
      ReportPeriodType.DAILY -> {
        val inc = sumType(paidTxs, TransactionType.INCOME, primaryCode, currenciesByCode)
        val exp = sumType(paidTxs, TransactionType.EXPENSE, primaryCode, currenciesByCode)
        listOf(
          BarSeriesPoint(
            label = "${startDate.monthValue}/${startDate.dayOfMonth}",
            income = inc,
            expense = exp,
          ),
        )
      }

      ReportPeriodType.WEEKLY -> {
        (0..6).map { offset ->
          val d = startDate.plusDays(offset.toLong())
          val epoch = d.toEpochDay()
          val dayTxs = paidTxs.filter { it.transactionDateEpochDay == epoch }
          val inc = sumType(dayTxs, TransactionType.INCOME, primaryCode, currenciesByCode)
          val exp = sumType(dayTxs, TransactionType.EXPENSE, primaryCode, currenciesByCode)
          BarSeriesPoint(
            label = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
            income = inc,
            expense = exp,
          )
        }
      }

      ReportPeriodType.MONTHLY -> {
        val totalDays = endDate.dayOfMonth
        val buckets = listOf(1..7, 8..14, 15..21, 22..totalDays)
        val labels = listOf("W1", "W2", "W3", "W4")
        buckets.mapIndexed { idx, range ->
          val bucketTxs = paidTxs.filter {
            val dom = LocalDate.ofEpochDay(it.transactionDateEpochDay).dayOfMonth
            dom in range
          }
          BarSeriesPoint(
            label = labels[idx],
            income = sumType(bucketTxs, TransactionType.INCOME, primaryCode, currenciesByCode),
            expense = sumType(bucketTxs, TransactionType.EXPENSE, primaryCode, currenciesByCode),
          )
        }
      }

      ReportPeriodType.YEARLY -> {
        val quarters = listOf(1..3 to "Q1", 4..6 to "Q2", 7..9 to "Q3", 10..12 to "Q4")
        quarters.map { (months, label) ->
          val qTxs = paidTxs.filter {
            LocalDate.ofEpochDay(it.transactionDateEpochDay).monthValue in months
          }
          BarSeriesPoint(
            label = label,
            income = sumType(qTxs, TransactionType.INCOME, primaryCode, currenciesByCode),
            expense = sumType(qTxs, TransactionType.EXPENSE, primaryCode, currenciesByCode),
          )
        }
      }

      ReportPeriodType.CUSTOM -> {
        val daysSpan = (endDate.toEpochDay() - startDate.toEpochDay() + 1).coerceAtLeast(1L)
        val bucketCount = minOf(6, daysSpan.toInt()).coerceAtLeast(1)
        val step = (daysSpan + bucketCount - 1) / bucketCount
        (0 until bucketCount).map { i ->
          val bStart = startDate.plusDays(i * step).toEpochDay()
          val bEnd = minOf(endDate.toEpochDay(), startDate.plusDays((i + 1) * step - 1).toEpochDay())
          val bucketTxs = paidTxs.filter { it.transactionDateEpochDay in bStart..bEnd }
          val d = LocalDate.ofEpochDay(bStart)
          BarSeriesPoint(
            label = "${d.monthValue}/${d.dayOfMonth}",
            income = sumType(bucketTxs, TransactionType.INCOME, primaryCode, currenciesByCode),
            expense = sumType(bucketTxs, TransactionType.EXPENSE, primaryCode, currenciesByCode),
          )
        }
      }
    }
  }

  private fun buildTrend(bars: List<BarSeriesPoint>): List<TrendPoint> {
    if (bars.size < 2) return emptyList()
    var running = BigDecimal.ZERO
    return bars.map { b ->
      running = running.add(b.income).subtract(b.expense)
      TrendPoint(label = b.label, value = running)
    }
  }

  private fun buildCategorySlices(
    paidPeriodTxs: List<TransactionEntity>,
    totalExpense: BigDecimal,
    categoriesById: Map<Long, CategoryEntity>,
    primaryCurrency: CurrencyEntity?,
    primaryCode: String,
    primaryPrecision: Int,
    currenciesByCode: Map<String, CurrencyEntity>,
  ): List<CategorySlice> {
    if (totalExpense <= BigDecimal.ZERO) return emptyList()
    val grouped = mutableMapOf<Long, BigDecimal>()
    for (tx in paidPeriodTxs) {
      if (tx.type != TransactionType.EXPENSE) continue
      val amt = resolveAmount(tx, primaryCode, currenciesByCode) ?: continue
      grouped[tx.categoryId] = (grouped[tx.categoryId] ?: BigDecimal.ZERO).add(amt)
    }
    return grouped.entries
      .sortedByDescending { it.value }
      .take(6)
      .mapIndexed { idx, (catId, amt) ->
        val cat = categoriesById[catId]
        val pct = DecimalUtils.percentage(amt, totalExpense).toFloat()
        CategorySlice(
          label = cat?.displayNameEn ?: "Other",
          amount = DecimalUtils.scaleToCurrency(amt, primaryPrecision),
          formattedAmount = CurrencyFormatter.formatWithEntity(amt, primaryCurrency, primaryCode),
          color = slicePalette[idx % slicePalette.size],
          percentage = pct,
        )
      }
  }

  private fun sumType(
    txs: List<TransactionEntity>,
    type: TransactionType,
    primaryCode: String,
    currenciesByCode: Map<String, CurrencyEntity>,
  ): BigDecimal =
    txs.asSequence()
      .filter { it.type == type }
      .mapNotNull { resolveAmount(it, primaryCode, currenciesByCode) }
      .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }

  private fun resolveAmount(
    tx: TransactionEntity,
    primaryCode: String,
    currenciesByCode: Map<String, CurrencyEntity>,
  ): BigDecimal? {
    if (tx.currencyCode.equals(primaryCode, ignoreCase = true)) return tx.amount
    if (tx.primaryCurrencyAtEntry.equals(primaryCode, ignoreCase = true) && tx.convertedPrimaryAmount != null) {
      return tx.convertedPrimaryAmount
    }
    val rate = currenciesByCode[tx.currencyCode.uppercase()]?.cachedRateToBase
    if (rate != null && rate > BigDecimal.ZERO) {
      return tx.amount.multiply(rate, DecimalUtils.FINANCIAL_MATH_CONTEXT)
    }
    return null
  }
}
