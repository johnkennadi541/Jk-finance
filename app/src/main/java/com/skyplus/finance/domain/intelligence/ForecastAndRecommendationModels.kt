package com.skyplus.finance.domain.intelligence

import java.math.BigDecimal

enum class ForecastStatus {
  READY,
  INSUFFICIENT_DATA,
}

enum class ForecastConfidence(val label: String) {
  LOW("Low Confidence"),
  MEDIUM("Medium Confidence"),
  HIGH("High Confidence"),
}

data class EndOfMonthForecast(
  val yearMonth: String,
  val currencyCode: String,
  val realizedIncomeToDate: BigDecimal,
  val projectedRemainingIncome: BigDecimal,
  val projectedTotalIncome: BigDecimal,
  val realizedExpensesToDate: BigDecimal,
  val scheduledUnpaidCommitmentsRemaining: BigDecimal,
  val projectedVariableExpensesRemaining: BigDecimal,
  val projectedTotalExpenses: BigDecimal,
  val currentCashBalance: BigDecimal,
  val projectedEndOfMonthBalance: BigDecimal,
  val disclaimer: String,
)

data class MonthlyCashflowForecast(
  val yearMonth: String,
  val monthLabel: String,
  val currencyCode: String,
  val projectedIncome: BigDecimal,
  val projectedExpenses: BigDecimal,
  val projectedNetCashflow: BigDecimal,
  val projectedEndingBalance: BigDecimal,
  val includesScheduledEmiAmount: BigDecimal,
)

data class CashflowForecastReport(
  val status: ForecastStatus,
  val confidence: ForecastConfidence?,
  val evaluatedCurrencyCode: String,
  val priorMonthsAnalyzed: Int,
  val hasIrregularOrSeasonalIncome: Boolean,
  val incomePatternNote: String,
  val statusReason: String,
  val endOfMonthForecast: EndOfMonthForecast?,
  val nextThreeMonths: List<MonthlyCashflowForecast>,
  val estimateDisclaimer: String,
)

enum class BudgetRecommendationAction {
  CREATE_BUDGET,
  INCREASE_LIMIT,
  LOWER_LIMIT,
  KEEP_CURRENT,
}

data class BudgetRecommendation(
  val categoryId: Long,
  val categoryName: String,
  val currencyCode: String,
  val currentBudgetLimit: BigDecimal?,
  val threeMonthAverageSpend: BigDecimal,
  val suggestedBudgetLimit: BigDecimal,
  val action: BudgetRecommendationAction,
  val monthsSampled: Int,
  val reason: String,
  val requiresUserConfirmation: Boolean = true,
)

data class GoalContributionRecommendation(
  val goalId: Long,
  val goalName: String,
  val currencyCode: String,
  val targetAmount: BigDecimal,
  val currentSavedAmount: BigDecimal,
  val remainingTargetAmount: BigDecimal,
  val alreadyContributedThisMonth: BigDecimal,
  val suggestedMonthlyContribution: BigDecimal,
  val remainingToContributeThisMonth: BigDecimal,
  val estimatedMonthsToComplete: Int?,
  val reason: String,
)

data class EmergencyFundRecommendation(
  val currencyCode: String,
  val monthlyEssentialBaseline: BigDecimal,
  val isBaselineEstimatedFromSpending: Boolean,
  val currentEmergencySaved: BigDecimal,
  val threeMonthTarget: BigDecimal,
  val sixMonthTarget: BigDecimal,
  val remainingForThreeMonthTarget: BigDecimal,
  val remainingForSixMonthTarget: BigDecimal,
  val alreadyAllocatedThisMonth: BigDecimal,
  val recommendedMonthlySavings: BigDecimal,
  val remainingRecommendedThisMonth: BigDecimal,
  val estimatedMonthsToThreeMonthTarget: Int?,
  val estimatedMonthsToSixMonthTarget: Int?,
  val summaryReason: String,
)

data class ForecastAndRecommendationBundle(
  val cashflowForecast: CashflowForecastReport,
  val budgetRecommendations: List<BudgetRecommendation>,
  val emergencyFundRecommendation: EmergencyFundRecommendation,
  val goalRecommendations: List<GoalContributionRecommendation>,
)
