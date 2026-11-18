package com.skyplus.finance.domain.report

import com.skyplus.finance.domain.model.BudgetHealthStatus
import com.skyplus.finance.domain.model.CurrencyBreakdownSummary
import com.skyplus.finance.domain.model.TransactionType
import java.math.BigDecimal

enum class ReportPeriodType(val label: String) {
  DAILY("Daily"),
  WEEKLY("Weekly"),
  MONTHLY("Monthly"),
  YEARLY("Yearly"),
  CUSTOM("Custom Date Range"),
}

enum class ReportExportFormat(val label: String, val extension: String, val mimeType: String) {
  PDF("PDF Document (.pdf)", "pdf", "application/pdf"),
  DOCX("Microsoft Word (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
  CSV("CSV Spreadsheet (.csv)", "csv", "text/csv"),
}

enum class ReportDetailLevel(val label: String) {
  SUMMARY("Summary Report"),
  DETAILED("Detailed Report"),
}

enum class ReportSection(val label: String) {
  SUMMARY("Executive Summary"),
  NET_WORTH_CHANGE("Net Worth Changes"),
  FINANCIAL_HEALTH("Financial Health Trends"),
  CATEGORY_EXPENSES("Category-wise Expenses"),
  INCOME_SOURCES("Income Sources"),
  BUDGET_VS_ACTUAL("Budget vs Actual"),
  LOANS_AND_EMI("Loans & EMI Summary"),
  SAVINGS_GOALS("Savings Goal Progress"),
  MULTI_CURRENCY("Multi-Currency Holdings"),
  TRANSACTION_HISTORY("Detailed Transaction History"),
}

data class ReportRequest(
  val periodType: ReportPeriodType,
  val startEpochDay: Long,
  val endEpochDay: Long,
  val displayCurrencyCode: String,
  val detailLevel: ReportDetailLevel = ReportDetailLevel.DETAILED,
  val includedSections: Set<ReportSection> = ReportSection.entries.toSet(),
)

data class ReportBreakdownRow(
  val name: String,
  val formattedAmount: String,
  val amount: BigDecimal,
  val sharePercent: BigDecimal,
)

data class ReportBudgetRow(
  val categoryName: String,
  val yearMonth: String,
  val limitFormatted: String,
  val spentFormatted: String,
  val remainingFormatted: String,
  val utilizationPercent: BigDecimal,
  val status: BudgetHealthStatus,
)

data class ReportLoanRow(
  val loanName: String,
  val lender: String,
  val outstandingFormatted: String,
  val emiFormatted: String,
  val annualRatePercent: String,
  val nextDueDate: String,
  val remainingMonths: Int,
  val status: String,
)

data class ReportEmiPaymentRow(
  val date: String,
  val loanName: String,
  val totalPaidFormatted: String,
  val principalFormatted: String,
  val interestFormatted: String,
  val extraPrepaymentFormatted: String,
)

data class ReportGoalRow(
  val goalName: String,
  val category: String,
  val savedFormatted: String,
  val targetFormatted: String,
  val progressPercent: BigDecimal,
  val targetDate: String,
)

data class ReportTransactionRow(
  val date: String,
  val type: TransactionType,
  val categoryName: String,
  val description: String,
  val originalFormatted: String,
  val originalCurrencyCode: String,
  val convertedDisplayFormatted: String?,
  val conversionNote: String?,
)

data class FinancialReportData(
  val title: String,
  val periodLabel: String,
  val dateRangeText: String,
  val generatedDateText: String,
  val displayCurrencyCode: String,
  val detailLevel: ReportDetailLevel,
  val includedSections: Set<ReportSection>,
  val openingBalanceFormatted: String,
  val closingBalanceFormatted: String,
  val totalIncomeFormatted: String,
  val totalExpensesFormatted: String,
  val netSavingsFormatted: String,
  val savingsAllocatedFormatted: String,
  val categoryExpenses: List<ReportBreakdownRow>,
  val incomeSources: List<ReportBreakdownRow>,
  val budgetRows: List<ReportBudgetRow>,
  val loanRows: List<ReportLoanRow>,
  val savingsGoalRows: List<ReportGoalRow>,
  val currencyHoldings: List<CurrencyBreakdownSummary>,
  val unconvertedForeignTxCount: Int,
  val transactionRows: List<ReportTransactionRow>,
  val emiPaymentRows: List<ReportEmiPaymentRow> = emptyList(),
  val totalEmiPaidInPeriodFormatted: String = "0",
  val netWorthStartFormatted: String = "0",
  val netWorthEndFormatted: String = "0",
  val netWorthChangeFormatted: String = "0",
  val netWorthChangePositive: Boolean = true,
  val healthScore: Int? = null,
  val healthBandLabel: String = "Insufficient Data",
  val healthSavingsRatePercent: String = "0%",
  val healthDebtToIncomePercent: String = "0%",
  val healthEmergencyMonths: String = "0.0 mo",
  val healthTrendSummary: String = "",
)
