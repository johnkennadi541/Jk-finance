package com.skyplus.finance.data.repository

import androidx.room.withTransaction
import com.skyplus.finance.data.cloud.OutboxMutationRecorder
import com.skyplus.finance.data.database.DatabaseInitializer
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.AccountEntity
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import com.skyplus.finance.data.entities.InvestmentEntity
import com.skyplus.finance.data.entities.SyncEntityTypes
import com.skyplus.finance.data.entities.SyncOperationType
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.repository.FinanceRepository
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class OfflineFinanceRepository @Inject constructor(
  private val database: SkyPlusDatabase,
  private val initializer: DatabaseInitializer,
  private val loanEmiCalculator: LoanEmiCalculator,
  private val outboxMutationRecorder: OutboxMutationRecorder?,
) : FinanceRepository {

  constructor(
    database: SkyPlusDatabase,
    initializer: DatabaseInitializer,
    loanEmiCalculator: LoanEmiCalculator,
  ) : this(database, initializer, loanEmiCalculator, null)

  private val transactionDao = database.transactionDao()
  private val categoryDao = database.categoryDao()
  private val currencyDao = database.currencyDao()
  private val budgetDao = database.budgetDao()
  private val loanDao = database.loanDao()
  private val savingsGoalDao = database.savingsGoalDao()
  private val accountDao = database.accountDao()
  private val settingsDao = database.appSettingsDao()
  private val shoppingDao = database.shoppingDao()
  private val investmentDao = database.investmentDao()

  override fun observeTransactions(): Flow<List<TransactionEntity>> =
    transactionDao.observeAllTransactions()

  override fun observeRecentTransactions(limit: Int): Flow<List<TransactionEntity>> =
    transactionDao.observeRecentTransactions(limit)

  override fun observeCategories(): Flow<List<CategoryEntity>> =
    categoryDao.observeAllCategories()

  override fun observeCurrencies(): Flow<List<CurrencyEntity>> =
    currencyDao.observeAllCurrencies()

  override fun observeBudgets(): Flow<List<BudgetEntity>> =
    budgetDao.observeAllBudgets()

  override fun observeLoans(): Flow<List<LoanEntity>> =
    loanDao.observeAllLoans()

  override fun observeAllEmiPayments(): Flow<List<EmiPaymentEntity>> =
    loanDao.observeAllEmiPayments()

  override fun observeEmiPaymentsForLoan(loanId: Long): Flow<List<EmiPaymentEntity>> =
    loanDao.observePaymentsForLoan(loanId)

  override fun observeSavingsGoals(): Flow<List<SavingsGoalEntity>> =
    savingsGoalDao.observeAllGoals()

  override fun observeSettings(): Flow<AppSettingsEntity?> =
    settingsDao.observeSettings()

  override fun observeAccounts(): Flow<List<AccountEntity>> =
    accountDao.observeAccounts()

  override fun observeShoppingLists(): Flow<List<ShoppingListEntity>> =
    shoppingDao.observeShoppingLists()

  override fun observeAllShoppingItems(): Flow<List<ShoppingItemEntity>> =
    shoppingDao.observeAllShoppingItems()

  override fun observeAllShopPrices(): Flow<List<ShoppingShopPriceEntity>> =
    shoppingDao.observeAllShopPrices()

  override fun observeInvestments(): Flow<List<InvestmentEntity>> =
    investmentDao.observeInvestments()

  override suspend fun ensureInitialized() {
    initializer.ensureSeeded()
  }

  override suspend fun addTransaction(transaction: TransactionEntity): Result<Long> = runCatching {
    require(transaction.amount > BigDecimal.ZERO) { "Transaction amount must be greater than zero." }
    initializer.ensureSeeded()

    val primaryAccountId = if (transaction.accountId > 0L) {
      transaction.accountId
    } else {
      accountDao.getPrimaryAccountOnce()?.id ?: 1L
    }

    val dedupKey = transaction.deduplicationKey
    if (!dedupKey.isNullOrBlank()) {
      val existing = transactionDao.findByDeduplicationKey(dedupKey)
      if (existing != null) {
        return@runCatching existing.id
      }
    }

    val insertedId = database.withTransaction {
      val currency = currencyDao.getCurrencyByCode(transaction.currencyCode)
      val scaledAmount = DecimalUtils.scaleToCurrency(transaction.amount, currency?.decimalPrecision ?: 2)
      val prepared = transaction.copy(
        accountId = primaryAccountId,
        amount = scaledAmount,
      )
      transactionDao.insert(prepared)
    }
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.TRANSACTION, insertedId.toString())
    insertedId
  }

  override suspend fun updateTransaction(transaction: TransactionEntity): Result<Unit> = runCatching {
    require(transaction.amount > BigDecimal.ZERO) { "Transaction amount must be greater than zero." }
    database.withTransaction {
      val currency = currencyDao.getCurrencyByCode(transaction.currencyCode)
      val scaledAmount = DecimalUtils.scaleToCurrency(transaction.amount, currency?.decimalPrecision ?: 2)
      transactionDao.update(
        transaction.copy(
          amount = scaledAmount,
          updatedAtEpochMillis = System.currentTimeMillis(),
        ),
      )
    }
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.TRANSACTION, transaction.id.toString())
  }

  override suspend fun deleteTransaction(transactionId: Long): Result<Unit> = runCatching {
    database.withTransaction {
      transactionDao.deleteById(transactionId)
    }
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.TRANSACTION,
      transactionId.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun markBillPaid(transactionId: Long): Result<Unit> = runCatching {
    database.withTransaction {
      val existing = transactionDao.getTransactionById(transactionId)
        ?: error("Transaction not found.")
      transactionDao.update(
        existing.copy(
          isPaid = true,
          updatedAtEpochMillis = System.currentTimeMillis(),
        ),
      )
    }
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.TRANSACTION, transactionId.toString())
  }

  override suspend fun upsertBudget(budget: BudgetEntity): Result<Long> = runCatching {
    require(budget.limitAmount > BigDecimal.ZERO) { "Budget limit must be greater than zero." }
    val savedId = database.withTransaction {
      val existing = budgetDao.findBudget(budget.categoryId, budget.yearMonth, budget.currencyCode)
      val currency = currencyDao.getCurrencyByCode(budget.currencyCode)
      val scaledLimit = DecimalUtils.scaleToCurrency(budget.limitAmount, currency?.decimalPrecision ?: 2)
      val toSave = if (existing != null && budget.id == 0L) {
        budget.copy(id = existing.id, limitAmount = scaledLimit, updatedAtEpochMillis = System.currentTimeMillis())
      } else {
        budget.copy(limitAmount = scaledLimit, updatedAtEpochMillis = System.currentTimeMillis())
      }
      val rowId = budgetDao.upsert(toSave)
      if (toSave.id > 0L) toSave.id else rowId
    }
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.BUDGET, savedId.toString())
    savedId
  }

  override suspend fun deleteBudget(budgetId: Long): Result<Unit> = runCatching {
    budgetDao.deleteById(budgetId)
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.BUDGET,
      budgetId.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun addLoan(loan: LoanEntity): Result<Long> = runCatching {
    require(loan.originalPrincipal > BigDecimal.ZERO) { "Loan principal must be greater than zero." }
    require(loan.outstandingPrincipal >= BigDecimal.ZERO) { "Outstanding principal cannot be negative." }
    require(loan.emiAmount >= BigDecimal.ZERO) { "EMI amount cannot be negative." }
    val insertedId = loanDao.insertLoan(loan)
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.LOAN, insertedId.toString())
    insertedId
  }

  override suspend fun updateLoan(loan: LoanEntity): Result<Unit> = runCatching {
    loanDao.updateLoan(loan.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.LOAN, loan.id.toString())
  }

  override suspend fun deleteLoan(loan: LoanEntity): Result<Unit> = runCatching {
    loanDao.deleteLoan(loan)
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.LOAN,
      loan.id.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun recordEmiPayment(
    loanId: Long,
    paymentAmount: BigDecimal,
    extraPrepayment: BigDecimal,
    paymentDateEpochDay: Long,
    note: String,
  ): Result<Long> = runCatching {
    require(paymentAmount > BigDecimal.ZERO) { "EMI payment must be positive." }
    require(extraPrepayment >= BigDecimal.ZERO) { "Extra prepayment cannot be negative." }

    val (paymentId, linkedTxId) = database.withTransaction {
      val loan = loanDao.getLoanById(loanId) ?: error("Loan not found.")
      val currency = currencyDao.getCurrencyByCode(loan.currencyCode)
      val precision = currency?.decimalPrecision ?: 2

      val split = loanEmiCalculator.calculatePaymentSplit(
        outstandingPrincipal = loan.outstandingPrincipal,
        annualInterestRatePercent = loan.annualInterestRatePercent,
        totalPaymentAmount = paymentAmount.add(extraPrepayment),
        decimalPrecision = precision,
      )

      val now = System.currentTimeMillis()
      val payId = loanDao.insertEmiPayment(
        EmiPaymentEntity(
          loanId = loan.id,
          paymentDateEpochDay = paymentDateEpochDay,
          totalPaidAmount = DecimalUtils.scaleToCurrency(paymentAmount.add(extraPrepayment), precision),
          principalComponent = split.principalComponent,
          interestComponent = split.interestComponent,
          extraPrepaymentAmount = DecimalUtils.scaleToCurrency(extraPrepayment, precision),
          remainingBalanceAfter = split.remainingBalanceAfter,
          currencyCode = loan.currencyCode,
          note = note,
          createdAtEpochMillis = now,
        ),
      )

      val nextStatus = if (split.remainingBalanceAfter <= BigDecimal.ZERO) LoanStatus.PAID_OFF else LoanStatus.ACTIVE
      val nextTenure = (loan.remainingTenureMonths - 1).coerceAtLeast(0)
      val currentDueDate = LocalDate.ofEpochDay(loan.nextDueDateEpochDay)
      val nextDueDate = currentDueDate.plusMonths(1).toEpochDay()

      loanDao.updateLoan(
        loan.copy(
          outstandingPrincipal = split.remainingBalanceAfter,
          remainingTenureMonths = if (nextStatus == LoanStatus.PAID_OFF) 0 else nextTenure,
          nextDueDateEpochDay = nextDueDate,
          status = nextStatus,
          updatedAtEpochMillis = now,
        ),
      )

      val emiCategoryId = categoryDao.getAllCategoriesOnce()
        .firstOrNull { it.nameKey == "emi_and_loans" }?.id ?: 3L
      val primaryAccountId = accountDao.getPrimaryAccountOnce()?.id ?: 1L
      val txId = transactionDao.insert(
        TransactionEntity(
          accountId = primaryAccountId,
          categoryId = emiCategoryId,
          type = TransactionType.EXPENSE,
          amount = DecimalUtils.scaleToCurrency(paymentAmount.add(extraPrepayment), precision),
          currencyCode = loan.currencyCode,
          description = if (note.isBlank()) "EMI: ${loan.loanName}" else note,
          transactionDateEpochDay = paymentDateEpochDay,
          isPaid = true,
          linkedLoanId = loan.id,
          createdAtEpochMillis = now,
          updatedAtEpochMillis = now,
        ),
      )

      payId to txId
    }
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.LOAN, loanId.toString())
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.EMI_PAYMENT, paymentId.toString())
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.TRANSACTION, linkedTxId.toString())
    paymentId
  }

  override suspend fun upsertSavingsGoal(goal: SavingsGoalEntity): Result<Long> = runCatching {
    require(goal.targetAmount > BigDecimal.ZERO) { "Target amount must be greater than zero." }
    require(goal.currentSavedAmount >= BigDecimal.ZERO) { "Saved amount cannot be negative." }
    val insertedId = savingsGoalDao.insertGoal(goal.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    val resolvedId = if (goal.id > 0L) goal.id else insertedId
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.SAVINGS_GOAL, resolvedId.toString())
    resolvedId
  }

  override suspend fun deleteSavingsGoal(goal: SavingsGoalEntity): Result<Unit> = runCatching {
    savingsGoalDao.deleteGoal(goal)
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.SAVINGS_GOAL,
      goal.id.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun contributeToSavingsGoal(
    goalId: Long,
    amount: BigDecimal,
    currencyCode: String,
    dateEpochDay: Long,
    note: String,
  ): Result<Long> = runCatching {
    require(amount > BigDecimal.ZERO) { "Savings contribution must be greater than zero." }
    val txId = database.withTransaction {
      val goal = savingsGoalDao.getGoalById(goalId) ?: error("Savings goal not found.")
      val currency = currencyDao.getCurrencyByCode(goal.currencyCode)
      val precision = currency?.decimalPrecision ?: 2
      val now = System.currentTimeMillis()
      val updatedSaved = DecimalUtils.scaleToCurrency(goal.currentSavedAmount.add(amount), precision)

      savingsGoalDao.updateGoal(
        goal.copy(
          currentSavedAmount = updatedSaved,
          updatedAtEpochMillis = now,
        ),
      )

      val savingsCatId = categoryDao.getAllCategoriesOnce()
        .firstOrNull {
          if (goal.isEmergencyFund) it.nameKey == "emergency_fund" else it.nameKey == "personal_goals"
        }?.id ?: 15L
      val primaryAccountId = accountDao.getPrimaryAccountOnce()?.id ?: 1L

      transactionDao.insert(
        TransactionEntity(
          accountId = primaryAccountId,
          categoryId = savingsCatId,
          type = TransactionType.SAVINGS_ALLOCATION,
          amount = DecimalUtils.scaleToCurrency(amount, precision),
          currencyCode = currencyCode,
          description = if (note.isBlank()) "Savings: ${goal.goalName}" else note,
          transactionDateEpochDay = dateEpochDay,
          isPaid = true,
          linkedGoalId = goal.id,
          createdAtEpochMillis = now,
          updatedAtEpochMillis = now,
        ),
      )
    }
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.SAVINGS_GOAL, goalId.toString())
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.TRANSACTION, txId.toString())
    txId
  }

  override suspend fun updateSettings(transform: (AppSettingsEntity) -> AppSettingsEntity): Result<Unit> = runCatching {
    initializer.ensureSeeded()
    val updatedPrimaryCurrency = database.withTransaction {
      val current = settingsDao.getSettingsOnce()
        ?: error("App settings not initialized.")
      val updated = transform(current).copy(updatedAtEpochMillis = System.currentTimeMillis())
      settingsDao.upsertSettings(updated)
      currencyDao.setWalletActive(updated.primaryCurrencyCode, true)
      updated.primaryCurrencyCode
    }
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.APP_SETTINGS, "1")
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.CURRENCY, updatedPrimaryCurrency.uppercase())
  }

  override suspend fun setCurrencyWalletActive(code: String, active: Boolean): Result<Unit> = runCatching {
    currencyDao.setWalletActive(code, active)
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.CURRENCY, code.uppercase())
  }

  override suspend fun updateExchangeRate(
    code: String,
    rateToBase: BigDecimal?,
    source: String?,
  ): Result<Unit> = runCatching {
    val existing = currencyDao.getCurrencyByCode(code) ?: error("Currency $code not found.")
    currencyDao.upsert(
      existing.copy(
        cachedRateToBase = rateToBase,
        rateSource = source,
        rateUpdatedAtEpochMillis = if (rateToBase != null) System.currentTimeMillis() else null,
      ),
    )
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.CURRENCY, code.uppercase())
  }

  override suspend fun upsertShoppingList(list: ShoppingListEntity): Result<Long> = runCatching {
    require(list.name.isNotBlank()) { "Shopping list name cannot be empty." }
    require(list.budgetAmount >= BigDecimal.ZERO) { "Shopping budget cannot be negative." }
    val currency = currencyDao.getCurrencyByCode(list.currencyCode)
    val scaledBudget = DecimalUtils.scaleToCurrency(list.budgetAmount, currency?.decimalPrecision ?: 2)
    val now = System.currentTimeMillis()
    val insertedId = shoppingDao.insertList(
      list.copy(
        name = list.name.trim(),
        budgetAmount = scaledBudget,
        updatedAtEpochMillis = now,
      ),
    )
    val resolvedId = if (list.id > 0L) list.id else insertedId
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.SHOPPING_LIST, resolvedId.toString())
    resolvedId
  }

  override suspend fun deleteShoppingList(listId: Long): Result<Unit> = runCatching {
    shoppingDao.deleteListById(listId)
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.SHOPPING_LIST,
      listId.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun upsertShoppingItem(item: ShoppingItemEntity): Result<Long> = runCatching {
    require(item.name.isNotBlank()) { "Shopping item name cannot be empty." }
    require(item.quantity > BigDecimal.ZERO) { "Quantity must be greater than zero." }
    require(item.estimatedUnitPrice >= BigDecimal.ZERO) { "Price cannot be negative." }
    val now = System.currentTimeMillis()
    val insertedId = shoppingDao.insertItem(
      item.copy(
        name = item.name.trim(),
        categoryName = item.categoryName.ifBlank { "Groceries" },
        unit = item.unit.ifBlank { "pcs" },
        updatedAtEpochMillis = now,
      ),
    )
    val resolvedId = if (item.id > 0L) item.id else insertedId
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.SHOPPING_ITEM, resolvedId.toString())
    resolvedId
  }

  override suspend fun deleteShoppingItem(itemId: Long): Result<Unit> = runCatching {
    shoppingDao.deleteItemById(itemId)
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.SHOPPING_ITEM,
      itemId.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun upsertShopPrice(price: ShoppingShopPriceEntity): Result<Long> = runCatching {
    require(price.productDisplayName.isNotBlank()) { "Product name cannot be empty." }
    require(price.shopName.isNotBlank()) { "Shop name cannot be empty." }
    require(price.price >= BigDecimal.ZERO) { "Price cannot be negative." }
    val currency = currencyDao.getCurrencyByCode(price.currencyCode)
    val scaledPrice = DecimalUtils.scaleToCurrency(price.price, currency?.decimalPrecision ?: 2)
    val insertedId = shoppingDao.insertShopPrice(
      price.copy(
        itemNameNormalized = price.itemNameNormalized.ifBlank { price.productDisplayName.trim().lowercase() },
        productDisplayName = price.productDisplayName.trim(),
        shopName = price.shopName.trim(),
        price = scaledPrice,
        updatedAtEpochMillis = System.currentTimeMillis(),
      ),
    )
    val resolvedId = if (price.id > 0L) price.id else insertedId
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.SHOPPING_SHOP_PRICE, resolvedId.toString())
    resolvedId
  }

  override suspend fun deleteShopPrice(priceId: Long): Result<Unit> = runCatching {
    shoppingDao.deleteShopPriceById(priceId)
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.SHOPPING_SHOP_PRICE,
      priceId.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun upsertInvestment(investment: InvestmentEntity): Result<Long> = runCatching {
    require(investment.investmentName.isNotBlank()) { "Investment name cannot be empty." }
    require(investment.amountInvested >= BigDecimal.ZERO) { "Investment amount cannot be negative." }
    val currency = currencyDao.getCurrencyByCode(investment.currencyCode)
    val scaledAmount = DecimalUtils.scaleToCurrency(investment.amountInvested, currency?.decimalPrecision ?: 2)
    val scaledCurrent = investment.currentValue?.let { DecimalUtils.scaleToCurrency(it, currency?.decimalPrecision ?: 2) }
    val now = System.currentTimeMillis()
    val insertedId = investmentDao.insertInvestment(
      investment.copy(
        investmentName = investment.investmentName.trim(),
        amountInvested = scaledAmount,
        currentValue = scaledCurrent,
        updatedAtEpochMillis = now,
      ),
    )
    val resolvedId = if (investment.id > 0L) investment.id else insertedId
    outboxMutationRecorder?.recordMutation(SyncEntityTypes.INVESTMENT, resolvedId.toString())
    resolvedId
  }

  override suspend fun deleteInvestment(investmentId: Long): Result<Unit> = runCatching {
    investmentDao.deleteInvestmentById(investmentId)
    outboxMutationRecorder?.recordMutation(
      SyncEntityTypes.INVESTMENT,
      investmentId.toString(),
      SyncOperationType.DELETE,
    )
  }

  override suspend fun eraseAllFinancialData(): Result<Unit> = runCatching {
    database.withTransaction {
      transactionDao.deleteAll()
      budgetDao.deleteAll()
      loanDao.deleteAllEmiPayments()
      loanDao.deleteAllLoans()
      savingsGoalDao.deleteAll()
      shoppingDao.deleteAllItems()
      shoppingDao.deleteAllLists()
      shoppingDao.deleteAllShopPrices()
      investmentDao.deleteAllInvestments()
      database.monthlySummaryDao().deleteAll()
      database.syncDao().deleteAllOutbox()
      database.syncDao().deleteAllConflicts()
      database.syncDao().deleteAllMaps()
      accountDao.deleteAll()
      settingsDao.deleteAll()
    }
    initializer.resetCachedFlag()
    initializer.ensureSeeded()
  }
}
