package com.skyplus.finance.domain.intelligence

import java.math.BigDecimal

enum class CategorySpendTrend {
  INCREASED,
  DECREASED,
  UNCHANGED,
  NEW_THIS_MONTH,
}

data class CategoryMonthComparison(
  val categoryId: Long,
  val categoryName: String,
  val currencyCode: String,
  val currentMonthSpent: BigDecimal,
  val previousMonthSpent: BigDecimal,
  val deltaAmount: BigDecimal,
  val deltaPercent: BigDecimal?,
  val trend: CategorySpendTrend,
)

enum class SpendingDayPreference {
  WEEKDAY_HEAVY,
  WEEKEND_HEAVY,
  BALANCED,
  INSUFFICIENT_DATA,
}

data class WeekdayWeekendAnalysis(
  val currencyCode: String,
  val evaluationWindowLabel: String,
  val weekdayTotal: BigDecimal,
  val weekendTotal: BigDecimal,
  val weekdayTransactionCount: Int,
  val weekendTransactionCount: Int,
  val weekdaySharePercent: BigDecimal,
  val weekendSharePercent: BigDecimal,
  val averagePerWeekdayTx: BigDecimal,
  val averagePerWeekendTx: BigDecimal,
  val preference: SpendingDayPreference,
  val insightSummary: String,
)

enum class RecurringCadence(val label: String) {
  WEEKLY("Weekly"),
  BIWEEKLY("Bi-Weekly"),
  MONTHLY("Monthly"),
  PERIODIC("Periodic"),
}

data class DetectedRecurringExpense(
  val key: String,
  val title: String,
  val categoryId: Long,
  val categoryName: String,
  val currencyCode: String,
  val averageAmount: BigDecimal,
  val occurrenceCount: Int,
  val estimatedIntervalDays: Int,
  val cadence: RecurringCadence,
  val lastTransactionEpochDay: Long,
  val nextEstimatedDueEpochDay: Long,
  val explicitlyMarkedRecurring: Boolean,
)

enum class SmartAlertType {
  UNUSUAL_CATEGORY_SPIKE,
  UNUSUAL_SINGLE_TRANSACTION,
  UPCOMING_BILL_DUE,
  OVERDUE_BILL,
  UPCOMING_EMI_DUE,
  BUDGET_APPROACHING_80,
  BUDGET_EXCEEDED_100,
}

enum class AlertSeverity {
  HIGH,
  WARNING,
  INFO,
}

data class SmartAlert(
  val id: String,
  val type: SmartAlertType,
  val severity: AlertSeverity,
  val title: String,
  val message: String,
  val recommendation: String,
  val currencyCode: String,
  val amount: BigDecimal? = null,
  val dueEpochDay: Long? = null,
)

data class SpendingIntelligenceReport(
  val evaluatedCurrencyCode: String,
  val currentMonthTotalSpent: BigDecimal,
  val previousMonthTotalSpent: BigDecimal,
  val monthOverMonthDelta: BigDecimal,
  val monthOverMonthPercent: BigDecimal?,
  val categoryComparisons: List<CategoryMonthComparison>,
  val weekdayWeekendAnalysis: WeekdayWeekendAnalysis,
  val recurringExpenses: List<DetectedRecurringExpense>,
  val alerts: List<SmartAlert>,
  val hasSufficientAnomalyBaseline: Boolean,
)
