package com.skyplus.finance.domain.intelligence

import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.DebtStrategyPlan
import com.skyplus.finance.domain.model.EarlyRepaymentEstimate
import com.skyplus.finance.domain.model.EmiSplitResult
import java.math.BigDecimal

enum class DebtPortfolioStatus {
  ACTIVE_DEBT,
  DEBT_FREE,
}

enum class DebtRiskLevel(val label: String) {
  NONE("Debt-Free"),
  HEALTHY("Healthy"),
  MODERATE("Moderate"),
  HIGH("Elevated"),
  CRITICAL("Critical"),
}

data class LoanPayoffMilestone(
  val loanId: Long,
  val loanName: String,
  val lender: String,
  val currencyCode: String,
  val outstandingPrincipal: BigDecimal,
  val annualInterestRatePercent: BigDecimal,
  val monthlyEmi: BigDecimal,
  val payoffOrder: Int,
  val estimatedMonthsToPayoff: Int,
  val estimatedPayoffEpochDay: Long,
  val projectedInterestPaid: BigDecimal,
  val amortizesWithinHorizon: Boolean,
)

data class StrategyBreakdown(
  val strategy: DebtPayoffStrategy,
  val strategyTitle: String,
  val plan: DebtStrategyPlan,
  val orderedLoanIds: List<Long>,
  val orderedLoanNames: List<String>,
  val monthsToDebtFree: Int,
  val estimatedDebtFreeEpochDay: Long,
  val totalPrincipalRemaining: BigDecimal,
  val totalInterestProjected: BigDecimal,
  val totalPayoutProjected: BigDecimal,
  val loanPayoffTimeline: List<LoanPayoffMilestone>,
  val advantages: List<String>,
  val disadvantages: List<String>,
  val hasNonAmortizingLoanWarning: Boolean,
)

data class SnowballVsAvalancheComparison(
  val status: DebtPortfolioStatus,
  val evaluatedCurrencyCode: String,
  val extraMonthlyBudgetApplied: BigDecimal,
  val snowball: StrategyBreakdown,
  val avalanche: StrategyBreakdown,
  val recommendedStrategy: DebtPayoffStrategy,
  val lowerInterestStrategy: DebtPayoffStrategy,
  val interestSavedByLowerInterestStrategy: BigDecimal,
  val fasterStrategy: DebtPayoffStrategy,
  val monthsSavedByFasterStrategy: Int,
  val comparisonSummary: String,
)

data class ExtraEmiSimulationResult(
  val loanId: Long,
  val loanName: String,
  val currencyCode: String,
  val outstandingPrincipal: BigDecimal,
  val annualInterestRatePercent: BigDecimal,
  val regularMonthlyEmi: BigDecimal,
  val requestedExtraMonthlyPayment: BigDecimal,
  val effectiveExtraMonthlyPayment: BigDecimal,
  val combinedMonthlyPayment: BigDecimal,
  val isAlreadyPaidOff: Boolean,
  val isZeroInterestLoan: Boolean,
  val isValidSimulation: Boolean,
  val validationWarning: String?,
  val earlyRepaymentEstimate: EarlyRepaymentEstimate,
  val normalPayoffEpochDay: Long,
  val acceleratedPayoffEpochDay: Long,
  val normalTotalPayout: BigDecimal,
  val acceleratedTotalPayout: BigDecimal,
  val interestSavedPercent: BigDecimal,
  val firstMonthNormalSplit: EmiSplitResult,
  val firstMonthExtraPrincipalPreview: BigDecimal,
  val comparisonSummary: String,
  val isSimulationOnly: Boolean = true,
)

data class DebtPayoffIntelligenceReport(
  val status: DebtPortfolioStatus,
  val evaluatedCurrencyCode: String,
  val activeLoanCount: Int,
  val paidOffLoanCount: Int,
  val excludedOtherCurrencyLoanCount: Int,
  val totalOutstandingPrincipal: BigDecimal,
  val totalMinimumMonthlyEmi: BigDecimal,
  val weightedAverageInterestRatePercent: BigDecimal,
  val emiToMonthlyIncomePercent: BigDecimal?,
  val debtRiskLevel: DebtRiskLevel,
  val riskSummary: String,
  val strategyComparison: SnowballVsAvalancheComparison,
  val perLoanSimulations: List<ExtraEmiSimulationResult>,
  val calculationAssumptions: List<String>,
  val bankPayoffDisclaimer: String,
)
