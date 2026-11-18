package com.skyplus.finance.domain.intelligence

import java.math.BigDecimal

enum class FinancialHealthStatus {
  COMPUTED,
  INSUFFICIENT_DATA,
}

enum class HealthScoreBand(val label: String) {
  EXCELLENT("Strong Financial Health"),
  GOOD("Steady Financial Health"),
  FAIR("Needs Attention"),
  CRITICAL("Financial Stress Risk"),
}

enum class HealthPillarType(val title: String, val maxScore: Int) {
  CASHFLOW_SAVINGS("Cashflow & Savings Rate", 30),
  DEBT_BURDEN("Debt & EMI Burden", 25),
  EMERGENCY_FUND("Emergency Fund Coverage", 25),
  BUDGET_DISCIPLINE("Budget Discipline", 20),
}

enum class HealthDimensionType(val title: String) {
  CASH_FLOW_HEALTH("Cash Flow Health"),
  SAVINGS_RATE("Savings Rate"),
  EMERGENCY_FUND("Emergency Fund"),
  DEBT_AND_EMI("Debt & EMI Management"),
  INTEREST_BURDEN("Interest Burden"),
  SPENDING_DISCIPLINE("Spending Discipline"),
  NET_WORTH_TREND("Net Worth Trend"),
  INCOME_STABILITY("Income Stability"),
}

enum class DimensionImpactStatus(val label: String) {
  STRENGTH("Strength"),
  BALANCED("Balanced"),
  NEEDS_ATTENTION("Needs Attention"),
  BASELINE_FORMING("Baseline Forming"),
}

data class HealthDimensionScore(
  val dimension: HealthDimensionType,
  val title: String,
  val score: Int,
  val maxScore: Int = 100,
  val status: DimensionImpactStatus,
  val metricSummary: String,
  val explanation: String,
)

data class HealthContributor(
  val title: String,
  val impactSummary: String,
  val explanation: String,
  val isPositive: Boolean,
)

data class HealthScoreHistoryPoint(
  val monthLabel: String,
  val yearMonth: String,
  val score: Int,
  val savingsRatePercent: BigDecimal,
  val netWorth: BigDecimal,
)

data class HealthActionItem(
  val dimensionTitle: String,
  val actionTitle: String,
  val actionDetail: String,
)

data class HealthPillarScore(
  val pillar: HealthPillarType,
  val title: String,
  val score: Int,
  val maxScore: Int,
  val metricSummary: String,
  val reason: String,
  val improvementTip: String,
)

data class FinancialHealthMetrics(
  val evaluatedCurrencyCode: String,
  val evaluationWindowLabel: String,
  val realizedTransactionCount: Int,
  val realizedIncome: BigDecimal,
  val realizedExpenses: BigDecimal,
  val netCashSurplus: BigDecimal,
  val savingsRatePercent: BigDecimal,
  val monthlyEmiCommitment: BigDecimal,
  val emiToIncomePercent: BigDecimal,
  val emergencyFundSaved: BigDecimal,
  val emergencyMonthsCovered: BigDecimal,
  val activeBudgetCount: Int,
  val exceededBudgetCount: Int,
  val weightedInterestAprPercent: BigDecimal = BigDecimal.ZERO,
  val monthlyInterestCost: BigDecimal = BigDecimal.ZERO,
  val netWorthDelta: BigDecimal = BigDecimal.ZERO,
  val activeIncomeMonthsLast3: Int = 0,
)

data class FinancialHealthReport(
  val status: FinancialHealthStatus,
  val overallScore: Int?,
  val band: HealthScoreBand?,
  val summaryHeadline: String,
  val primaryExplanation: String,
  val pillars: List<HealthPillarScore>,
  val topReasons: List<String>,
  val priorityImprovements: List<String>,
  val metrics: FinancialHealthMetrics,
  val dimensions: List<HealthDimensionScore> = emptyList(),
  val positiveContributors: List<HealthContributor> = emptyList(),
  val negativeContributors: List<HealthContributor> = emptyList(),
  val scoreHistory: List<HealthScoreHistoryPoint> = emptyList(),
  val previousScore: Int? = null,
  val scoreChangeDelta: Int? = null,
  val scoreChangeExplanation: String = "",
  val practicalActions: List<HealthActionItem> = emptyList(),
)
