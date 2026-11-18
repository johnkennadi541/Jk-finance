package com.skyplus.finance.domain.usecase

import com.skyplus.finance.data.entities.AccountEntity
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.InvestmentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.calculator.SafeToSpendCalculator
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceEngine
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceReport
import com.skyplus.finance.domain.intelligence.FinancialHealthReport
import com.skyplus.finance.domain.intelligence.FinancialHealthScoreEngine
import com.skyplus.finance.domain.intelligence.ForecastAndRecommendationBundle
import com.skyplus.finance.domain.intelligence.ForecastAndRecommendationEngine
import com.skyplus.finance.domain.intelligence.SpendingIntelligenceEngine
import com.skyplus.finance.domain.intelligence.SpendingIntelligenceReport
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.BudgetStatusItem
import com.skyplus.finance.domain.model.CurrencyBreakdownSummary
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.DebtStrategyPlan
import com.skyplus.finance.domain.model.EmergencyFundSummary
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.model.SafeToSpendResult
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.repository.FinanceRepository
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class PortfolioIntelligenceBundle(
  val healthReport: FinancialHealthReport,
  val spendingReport: SpendingIntelligenceReport,
  val forecastAndRecommendations: ForecastAndRecommendationBundle,
  val debtPayoffReport: DebtPayoffIntelligenceReport,
)

data class DashboardSnapshot(
  val primaryCurrency: CurrencyEntity?,
  val primaryCurrencyCode: String,
  val themeMode: AppThemeMode,
  val linkedGoogleEmail: String?,
  val settings: AppSettingsEntity?,
  val totalAssets: BigDecimal,
  val totalLiabilities: BigDecimal,
  val totalNetWorth: BigDecimal,
  val monthlyIncome: BigDecimal,
  val monthlyExpenses: BigDecimal,
  val monthlyNetSavings: BigDecimal,
  val safeToSpend: SafeToSpendResult,
  val activeLoanCount: Int,
  val totalOutstandingDebt: BigDecimal,
  val totalMonthlyEmi: BigDecimal,
  val nextEmiDueEpochDay: Long?,
  val estimatedDebtFreeEpochDay: Long?,
  val emergencyFund: EmergencyFundSummary,
  val recentTransactions: List<TransactionEntity>,
  val allTransactions: List<TransactionEntity>,
  val categoriesById: Map<Long, CategoryEntity>,
  val currenciesByCode: Map<String, CurrencyEntity>,
  val allCurrencies: List<CurrencyEntity>,
  val budgetStatuses: List<BudgetStatusItem>,
  val loans: List<LoanEntity>,
  val emiPayments: List<EmiPaymentEntity>,
  val savingsGoals: List<SavingsGoalEntity>,
  val currencyBreakdowns: List<CurrencyBreakdownSummary>,
  val snowballPlan: DebtStrategyPlan,
  val avalanchePlan: DebtStrategyPlan,
  val investments: List<InvestmentEntity> = emptyList(),
  val intelligence: PortfolioIntelligenceBundle? = null,
)

class ObserveDashboardSnapshotUseCase @Inject constructor(
  private val repository: FinanceRepository,
  private val safeToSpendCalculator: SafeToSpendCalculator,
  private val loanEmiCalculator: LoanEmiCalculator,
  private val budgetAndSavingsCalculator: BudgetAndSavingsCalculator,
  private val financialHealthScoreEngine: FinancialHealthScoreEngine,
  private val spendingIntelligenceEngine: SpendingIntelligenceEngine,
  private val forecastAndRecommendationEngine: ForecastAndRecommendationEngine,
  private val debtPayoffIntelligenceEngine: DebtPayoffIntelligenceEngine,
) {

  operator fun invoke(todayProvider: () -> LocalDate = { LocalDate.now() }): Flow<DashboardSnapshot> {
    val coreFlow = combine(
      repository.observeTransactions(),
      repository.observeCategories(),
      repository.observeCurrencies(),
      repository.observeBudgets(),
      repository.observeSettings(),
    ) { transactions, categories, currencies, budgets, settings ->
      CoreBundle(transactions, categories, currencies, budgets, settings)
    }

    val debtAndAccountFlow = combine(
      repository.observeLoans(),
      repository.observeAllEmiPayments(),
      repository.observeSavingsGoals(),
      repository.observeAccounts(),
      repository.observeInvestments(),
    ) { loans, payments, goals, accounts, investments ->
      DebtAndAccountBundle(loans, payments, goals, accounts, investments)
    }

    return combine(coreFlow, debtAndAccountFlow) { core, extra ->
      val today = todayProvider()
      val currentMonth = YearMonth.of(today.year, today.monthValue)
      val monthStartEpochDay = currentMonth.atDay(1).toEpochDay()
      val monthEndEpochDay = currentMonth.atEndOfMonth().toEpochDay()

      val settings = core.settings
      val primaryCode = settings?.primaryCurrencyCode ?: "INR"
      val currenciesMap = core.currencies.associateBy { it.code.uppercase() }
      val primaryCurrency = currenciesMap[primaryCode.uppercase()]
      val primaryPrecision = primaryCurrency?.decimalPrecision ?: 2
      val precisionMap = core.currencies.associate { it.code.uppercase() to it.decimalPrecision }
      val categoriesMap = core.categories.associateBy { it.id }

      // Paid cash balance in primary currency
      val availableBalance = computeAvailableBalanceInPrimary(
        transactions = core.transactions,
        primaryCode = primaryCode,
      )

      // Current month metrics in primary currency
      val monthPaidTxs = core.transactions.filter {
        it.isPaid && it.transactionDateEpochDay in monthStartEpochDay..monthEndEpochDay
      }
      val monthlyIncome = monthPaidTxs
        .filter { it.type == TransactionType.INCOME }
        .mapNotNull { amountInPrimaryOrNull(it, primaryCode) }
        .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }
      val monthlyExpenses = monthPaidTxs
        .filter { it.type == TransactionType.EXPENSE }
        .mapNotNull { amountInPrimaryOrNull(it, primaryCode) }
        .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }
      val monthlyNetSavings = monthlyIncome.subtract(monthlyExpenses)

      // Unpaid upcoming bills due by end of this month
      val unpaidUpcomingBills = core.transactions
        .asSequence()
        .filter { tx ->
          tx.type == TransactionType.EXPENSE &&
            tx.isUpcomingBill &&
            !tx.isPaid &&
            tx.transactionDateEpochDay <= monthEndEpochDay
        }
        .mapNotNull { amountInPrimaryOrNull(it, primaryCode) }
        .fold(BigDecimal.ZERO) { acc, amt -> acc.add(amt) }

      val loanIdsPaidThisMonth = extra.emiPayments
        .asSequence()
        .filter { it.paymentDateEpochDay in monthStartEpochDay..monthEndEpochDay }
        .map { it.loanId }
        .toSet()

      val activeLoans = extra.loans.filter { it.status == LoanStatus.ACTIVE }
      val primaryActiveLoans = activeLoans.filter { it.currencyCode.equals(primaryCode, ignoreCase = true) }

      val plannedEmiPayments = primaryActiveLoans
        .asSequence()
        .filter { loan ->
          loan.nextDueDateEpochDay <= monthEndEpochDay &&
            !loanIdsPaidThisMonth.contains(loan.id)
        }
        .fold(BigDecimal.ZERO) { acc, loan -> acc.add(loan.emiAmount) }

      val remainingDays = safeToSpendCalculator.remainingDaysIncludingToday(today)
      val safeToSpend = safeToSpendCalculator.calculate(
        SafeToSpendInput(
          availableBalance = availableBalance,
          confirmedRemainingIncome = settings?.confirmedRemainingIncome ?: BigDecimal.ZERO,
          unpaidUpcomingBills = unpaidUpcomingBills,
          plannedEmiPayments = plannedEmiPayments,
          savingsAllocation = settings?.monthlySavingsAllocation ?: BigDecimal.ZERO,
          reservedAmount = settings?.reservedAmount ?: BigDecimal.ZERO,
          remainingDaysInMonth = remainingDays,
          currencyCode = primaryCode,
          currencyDecimalPrecision = primaryPrecision,
        ),
      )

      val totalOutstandingDebt = primaryActiveLoans.fold(BigDecimal.ZERO) { acc, l ->
        acc.add(l.outstandingPrincipal)
      }
      val totalMonthlyEmi = primaryActiveLoans.fold(BigDecimal.ZERO) { acc, l ->
        acc.add(l.emiAmount)
      }
      val nextEmiDueEpochDay = activeLoans.minOfOrNull { it.nextDueDateEpochDay }

      // Net Worth accounting: cash balance + goal savings + unlinked savings allocations - active loan liabilities
      val goalSavingsTotal = extra.goals
        .filter { it.currencyCode.equals(primaryCode, ignoreCase = true) }
        .fold(BigDecimal.ZERO) { acc, g -> acc.add(g.currentSavedAmount) }
      val unlinkedSavingsTotal = core.transactions
        .filter { it.isPaid && it.type == TransactionType.SAVINGS_ALLOCATION && it.linkedGoalId == null }
        .mapNotNull { amountInPrimaryOrNull(it, primaryCode) }
        .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }
      val totalInvestments = extra.investments
        .filter { it.currencyCode.equals(primaryCode, ignoreCase = true) }
        .fold(BigDecimal.ZERO) { acc, inv -> acc.add(inv.currentValue ?: inv.amountInvested) }
      val totalAssets = availableBalance.add(goalSavingsTotal).add(unlinkedSavingsTotal).add(totalInvestments)
      val totalLiabilities = totalOutstandingDebt
      val totalNetWorth = totalAssets.subtract(totalLiabilities)

      val snowballPlan = loanEmiCalculator.calculateDebtStrategyPlan(
        loans = primaryActiveLoans,
        strategy = DebtPayoffStrategy.SNOWBALL,
        decimalPrecision = primaryPrecision,
        today = today,
      )
      val avalanchePlan = loanEmiCalculator.calculateDebtStrategyPlan(
        loans = primaryActiveLoans,
        strategy = DebtPayoffStrategy.AVALANCHE,
        decimalPrecision = primaryPrecision,
        today = today,
      )

      val budgetStatuses = budgetAndSavingsCalculator.computeBudgetStatuses(
        budgets = core.budgets.filter { it.yearMonth == currentMonth.toString() },
        categories = categoriesMap,
        transactions = core.transactions,
        currencyPrecisionMap = precisionMap,
      )

      val emergencyFund = budgetAndSavingsCalculator.computeEmergencyFund(
        goals = extra.goals,
        monthlyEssentialExpenses = settings?.monthlyEssentialExpenses ?: BigDecimal.ZERO,
        primaryCurrencyCode = primaryCode,
        primaryPrecision = primaryPrecision,
      )

      val breakdowns = budgetAndSavingsCalculator.computeCurrencyBreakdowns(
        transactions = core.transactions,
        yearMonth = currentMonth,
        currencyPrecisionMap = precisionMap,
      )

      val linkedEmail = extra.accounts.firstOrNull()?.googleAccountEmail?.takeIf { it.isNotBlank() }

      val healthReport = financialHealthScoreEngine.evaluate(
        transactions = core.transactions,
        budgets = core.budgets,
        categoriesById = categoriesMap,
        loans = extra.loans,
        savingsGoals = extra.goals,
        settings = settings,
        primaryCurrencyCode = primaryCode,
        currencyPrecisionMap = precisionMap,
        today = today,
      )
      val spendingReport = spendingIntelligenceEngine.analyze(
        transactions = core.transactions,
        budgets = core.budgets,
        categoriesById = categoriesMap,
        loans = extra.loans,
        emiPayments = extra.emiPayments,
        primaryCurrencyCode = primaryCode,
        currencyPrecisionMap = precisionMap,
        today = today,
      )
      val forecastBundle = forecastAndRecommendationEngine.analyze(
        transactions = core.transactions,
        budgets = core.budgets,
        categoriesById = categoriesMap,
        loans = extra.loans,
        emiPayments = extra.emiPayments,
        savingsGoals = extra.goals,
        settings = settings,
        primaryCurrencyCode = primaryCode,
        currencyPrecisionMap = precisionMap,
        today = today,
      )
      val debtReport = debtPayoffIntelligenceEngine.analyze(
        loans = extra.loans,
        primaryCurrencyCode = primaryCode,
        currencyPrecisionMap = precisionMap,
        realizedMonthlyIncome = healthReport.metrics.realizedIncome.takeIf { it > BigDecimal.ZERO },
        today = today,
      )

      DashboardSnapshot(
        primaryCurrency = primaryCurrency,
        primaryCurrencyCode = primaryCode,
        themeMode = runCatching {
          AppThemeMode.valueOf(settings?.themeMode ?: "SYSTEM")
        }.getOrDefault(AppThemeMode.SYSTEM),
        linkedGoogleEmail = linkedEmail,
        settings = settings,
        totalAssets = DecimalUtils.scaleToCurrency(totalAssets, primaryPrecision),
        totalLiabilities = DecimalUtils.scaleToCurrency(totalLiabilities, primaryPrecision),
        totalNetWorth = DecimalUtils.scaleToCurrency(totalNetWorth, primaryPrecision),
        monthlyIncome = DecimalUtils.scaleToCurrency(monthlyIncome, primaryPrecision),
        monthlyExpenses = DecimalUtils.scaleToCurrency(monthlyExpenses, primaryPrecision),
        monthlyNetSavings = DecimalUtils.scaleToCurrency(monthlyNetSavings, primaryPrecision),
        safeToSpend = safeToSpend,
        activeLoanCount = activeLoans.size,
        totalOutstandingDebt = DecimalUtils.scaleToCurrency(totalOutstandingDebt, primaryPrecision),
        totalMonthlyEmi = DecimalUtils.scaleToCurrency(totalMonthlyEmi, primaryPrecision),
        nextEmiDueEpochDay = nextEmiDueEpochDay,
        estimatedDebtFreeEpochDay = if (primaryActiveLoans.isNotEmpty()) avalanchePlan.estimatedDebtFreeEpochDay else null,
        emergencyFund = emergencyFund,
        recentTransactions = core.transactions.take(6),
        allTransactions = core.transactions,
        categoriesById = categoriesMap,
        currenciesByCode = currenciesMap,
        allCurrencies = core.currencies,
        budgetStatuses = budgetStatuses,
        loans = extra.loans,
        emiPayments = extra.emiPayments,
        savingsGoals = extra.goals,
        currencyBreakdowns = breakdowns,
        snowballPlan = snowballPlan,
        avalanchePlan = avalanchePlan,
        investments = extra.investments,
        intelligence = PortfolioIntelligenceBundle(
          healthReport = healthReport,
          spendingReport = spendingReport,
          forecastAndRecommendations = forecastBundle,
          debtPayoffReport = debtReport,
        ),
      )
    }
  }

  private fun computeAvailableBalanceInPrimary(
    transactions: List<TransactionEntity>,
    primaryCode: String,
  ): BigDecimal {
    var balance = BigDecimal.ZERO
    for (tx in transactions) {
      if (!tx.isPaid) continue
      val amt = amountInPrimaryOrNull(tx, primaryCode) ?: continue
      balance = when (tx.type) {
        TransactionType.INCOME -> balance.add(amt)
        TransactionType.EXPENSE -> balance.subtract(amt)
        TransactionType.SAVINGS_ALLOCATION -> balance.subtract(amt)
      }
    }
    return balance
  }

  private fun amountInPrimaryOrNull(tx: TransactionEntity, primaryCode: String): BigDecimal? {
    return when {
      tx.currencyCode.equals(primaryCode, ignoreCase = true) -> tx.amount
      tx.primaryCurrencyAtEntry.equals(primaryCode, ignoreCase = true) && tx.convertedPrimaryAmount != null ->
        tx.convertedPrimaryAmount
      else -> null
    }
  }

  private data class CoreBundle(
    val transactions: List<TransactionEntity>,
    val categories: List<CategoryEntity>,
    val currencies: List<CurrencyEntity>,
    val budgets: List<BudgetEntity>,
    val settings: AppSettingsEntity?,
  )

  private data class DebtAndAccountBundle(
    val loans: List<LoanEntity>,
    val emiPayments: List<EmiPaymentEntity>,
    val goals: List<SavingsGoalEntity>,
    val accounts: List<AccountEntity>,
    val investments: List<InvestmentEntity>,
  )
}
