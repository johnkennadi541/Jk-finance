package com.skyplus.finance.domain.shopping

import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import javax.inject.Inject

enum class ShoppingBudgetStatus {
  NO_BUDGET_SET,
  ON_TRACK,
  APPROACHING,
  OVER_BUDGET,
}

data class ShoppingListSummary(
  val list: ShoppingListEntity,
  val items: List<ShoppingItemEntity>,
  val totalPlanned: BigDecimal,
  val totalActualSpent: BigDecimal,
  val purchasedCount: Int,
  val remainingBudget: BigDecimal,
  val budgetStatus: ShoppingBudgetStatus,
)

data class ShoppingCategorySpend(
  val categoryName: String,
  val plannedAmount: BigDecimal,
  val actualSpentAmount: BigDecimal,
  val itemCount: Int,
)

data class ShoppingPlannerOverview(
  val listSummaries: List<ShoppingListSummary>,
  val totalShoppingBudget: BigDecimal,
  val totalPlannedSpend: BigDecimal,
  val totalActualSpend: BigDecimal,
  val remainingOverallBudget: BigDecimal,
  val overallStatus: ShoppingBudgetStatus,
  val categoryBreakdown: List<ShoppingCategorySpend>,
)

data class ShopComparisonGroup(
  val itemKey: String,
  val displayName: String,
  val entriesSortedLowestFirst: List<ShoppingShopPriceEntity>,
  val lowestPrice: BigDecimal,
  val highestPrice: BigDecimal,
  val potentialSavings: BigDecimal,
  val currencyCode: String,
  val hasLivePrices: Boolean,
)

/**
 * Pure BigDecimal calculation engine for Smart Shopping planning,
 * planned vs actual tracking, and shop price comparison without inventing prices.
 */
class ShoppingPlannerCalculator @Inject constructor() {

  fun computePlannerOverview(
    lists: List<ShoppingListEntity>,
    items: List<ShoppingItemEntity>,
    currencyPrecisionMap: Map<String, Int>,
    defaultPrecision: Int = 2,
  ): ShoppingPlannerOverview {
    val itemsByList = items.groupBy { it.listId }

    val listSummaries = lists.map { list ->
      val scale = currencyPrecisionMap[list.currencyCode.uppercase()] ?: defaultPrecision
      val listItems = itemsByList[list.id].orEmpty()

      var planned = BigDecimal.ZERO
      var actual = BigDecimal.ZERO
      var purchasedCount = 0

      for (item in listItems) {
        val estLine = item.quantity.multiply(item.estimatedUnitPrice, DecimalUtils.FINANCIAL_MATH_CONTEXT)
        planned = planned.add(estLine)
        if (item.isPurchased) {
          purchasedCount++
          val unitActual = item.actualUnitPrice ?: item.estimatedUnitPrice
          actual = actual.add(item.quantity.multiply(unitActual, DecimalUtils.FINANCIAL_MATH_CONTEXT))
        }
      }

      val scaledPlanned = DecimalUtils.scaleToCurrency(planned, scale)
      val scaledActual = DecimalUtils.scaleToCurrency(actual, scale)
      val usedBudgetMetric = maxOf(scaledPlanned, scaledActual)
      val remaining = DecimalUtils.scaleToCurrency(list.budgetAmount.subtract(usedBudgetMetric), scale)

      val status = when {
        list.budgetAmount <= BigDecimal.ZERO -> ShoppingBudgetStatus.NO_BUDGET_SET
        usedBudgetMetric > list.budgetAmount -> ShoppingBudgetStatus.OVER_BUDGET
        DecimalUtils.percentage(usedBudgetMetric, list.budgetAmount) >= BigDecimal("80") ->
          ShoppingBudgetStatus.APPROACHING
        else -> ShoppingBudgetStatus.ON_TRACK
      }

      ShoppingListSummary(
        list = list,
        items = listItems,
        totalPlanned = scaledPlanned,
        totalActualSpent = scaledActual,
        purchasedCount = purchasedCount,
        remainingBudget = remaining,
        budgetStatus = status,
      )
    }

    val totalBudget = DecimalUtils.scaleToCurrency(
      lists.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.budgetAmount) },
      defaultPrecision,
    )
    val totalPlanned = DecimalUtils.scaleToCurrency(
      listSummaries.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.totalPlanned) },
      defaultPrecision,
    )
    val totalActual = DecimalUtils.scaleToCurrency(
      listSummaries.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.totalActualSpent) },
      defaultPrecision,
    )
    val overallMetric = maxOf(totalPlanned, totalActual)
    val remainingAll = DecimalUtils.scaleToCurrency(totalBudget.subtract(overallMetric), defaultPrecision)
    val overallStatus = when {
      totalBudget <= BigDecimal.ZERO -> ShoppingBudgetStatus.NO_BUDGET_SET
      overallMetric > totalBudget -> ShoppingBudgetStatus.OVER_BUDGET
      DecimalUtils.percentage(overallMetric, totalBudget) >= BigDecimal("80") -> ShoppingBudgetStatus.APPROACHING
      else -> ShoppingBudgetStatus.ON_TRACK
    }

    val categoryBreakdown = items
      .groupBy { it.categoryName.ifBlank { "Groceries" } }
      .entries
      .map { (cat, catItems) ->
        val catPlanned = catItems.fold(BigDecimal.ZERO) { acc, it ->
          acc.add(it.quantity.multiply(it.estimatedUnitPrice, DecimalUtils.FINANCIAL_MATH_CONTEXT))
        }
        val catActual = catItems
          .filter { it.isPurchased }
          .fold(BigDecimal.ZERO) { acc, it ->
            acc.add(it.quantity.multiply(it.actualUnitPrice ?: it.estimatedUnitPrice, DecimalUtils.FINANCIAL_MATH_CONTEXT))
          }
        ShoppingCategorySpend(
          categoryName = cat,
          plannedAmount = DecimalUtils.scaleToCurrency(catPlanned, defaultPrecision),
          actualSpentAmount = DecimalUtils.scaleToCurrency(catActual, defaultPrecision),
          itemCount = catItems.size,
        )
      }
      .sortedByDescending { it.plannedAmount }

    return ShoppingPlannerOverview(
      listSummaries = listSummaries,
      totalShoppingBudget = totalBudget,
      totalPlannedSpend = totalPlanned,
      totalActualSpend = totalActual,
      remainingOverallBudget = remainingAll,
      overallStatus = overallStatus,
      categoryBreakdown = categoryBreakdown,
    )
  }

  fun groupShopPrices(
    prices: List<ShoppingShopPriceEntity>,
    currencyPrecisionMap: Map<String, Int>,
  ): List<ShopComparisonGroup> {
    if (prices.isEmpty()) return emptyList()
    return prices
      .groupBy { "${it.itemNameNormalized.lowercase()}|${it.currencyCode.uppercase()}" }
      .values
      .map { groupEntries ->
        val sorted = groupEntries.sortedWith(compareBy<ShoppingShopPriceEntity> { it.price }.thenByDescending { it.updatedAtEpochMillis })
        val first = sorted.first()
        val last = sorted.last()
        val scale = currencyPrecisionMap[first.currencyCode.uppercase()] ?: 2
        val lowest = DecimalUtils.scaleToCurrency(first.price, scale)
        val highest = DecimalUtils.scaleToCurrency(last.price, scale)
        val savings = DecimalUtils.scaleToCurrency(highest.subtract(lowest).coerceAtLeast(BigDecimal.ZERO), scale)
        ShopComparisonGroup(
          itemKey = first.itemNameNormalized,
          displayName = first.productDisplayName,
          entriesSortedLowestFirst = sorted,
          lowestPrice = lowest,
          highestPrice = highest,
          potentialSavings = savings,
          currencyCode = first.currencyCode,
          hasLivePrices = sorted.any { it.isLiveFeedAvailable },
        )
      }
      .sortedByDescending { it.potentialSavings }
  }
}
