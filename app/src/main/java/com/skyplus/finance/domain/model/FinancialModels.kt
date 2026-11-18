package com.skyplus.finance.domain.model

import java.math.BigDecimal

data class SafeToSpendInput(
  val availableBalance: BigDecimal,
  val confirmedRemainingIncome: BigDecimal,
  val unpaidUpcomingBills: BigDecimal,
  val plannedEmiPayments: BigDecimal,
  val savingsAllocation: BigDecimal,
  val reservedAmount: BigDecimal,
  val remainingDaysInMonth: Int,
  val currencyCode: String,
  val currencyDecimalPrecision: Int,
)

data class SafeToSpendResult(
  val dailyLimit: BigDecimal,
  val spendablePool: BigDecimal,
  val availableBalance: BigDecimal,
  val confirmedRemainingIncome: BigDecimal,
  val unpaidUpcomingBills: BigDecimal,
  val plannedEmiPayments: BigDecimal,
  val savingsAllocation: BigDecimal,
  val reservedAmount: BigDecimal,
  val remainingDaysInMonth: Int,
  val isInsufficientFunds: Boolean,
  val isTightBudget: Boolean,
  val currencyCode: String,
)

data class EmiSplitResult(
  val interestComponent: BigDecimal,
  val principalComponent: BigDecimal,
  val remainingBalanceAfter: BigDecimal,
)

data class EarlyRepaymentEstimate(
  val baselineMonthsRemaining: Int,
  val acceleratedMonthsRemaining: Int,
  val monthsSaved: Int,
  val baselineTotalInterest: BigDecimal,
  val acceleratedTotalInterest: BigDecimal,
  val interestSaved: BigDecimal,
  val estimatedPayoffEpochDay: Long,
  val isEstimated: Boolean = true,
)

data class DebtStrategyPlan(
  val strategy: DebtPayoffStrategy,
  val orderedLoanNames: List<String>,
  val monthsToDebtFree: Int,
  val estimatedDebtFreeEpochDay: Long,
  val totalInterestProjected: BigDecimal,
  val isEstimated: Boolean = true,
)

data class BudgetStatusItem(
  val budgetId: Long,
  val categoryId: Long,
  val categoryNameEn: String,
  val categoryNameTa: String,
  val iconKey: String,
  val colorHex: Long,
  val yearMonth: String,
  val currencyCode: String,
  val baseLimit: BigDecimal,
  val rolloverAmount: BigDecimal,
  val rolloverEnabled: Boolean,
  val effectiveLimit: BigDecimal,
  val actualSpent: BigDecimal,
  val remainingAmount: BigDecimal,
  val utilizationPercent: BigDecimal,
  val status: BudgetHealthStatus,
)

data class EmergencyFundSummary(
  val currentEmergencySavings: BigDecimal,
  val targetEmergencySavings: BigDecimal,
  val monthlyEssentialExpenses: BigDecimal,
  val monthsCovered: BigDecimal,
  val progressPercent: BigDecimal,
  val currencyCode: String,
)

data class CurrencyBreakdownSummary(
  val currencyCode: String,
  val incomeTotal: BigDecimal,
  val expenseTotal: BigDecimal,
  val savingsTotal: BigDecimal,
  val netBalance: BigDecimal,
)
