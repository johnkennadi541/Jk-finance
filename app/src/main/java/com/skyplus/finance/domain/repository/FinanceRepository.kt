package com.skyplus.finance.domain.repository

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
import com.skyplus.finance.data.entities.TransactionEntity
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

interface FinanceRepository {
  fun observeTransactions(): Flow<List<TransactionEntity>>
  fun observeRecentTransactions(limit: Int): Flow<List<TransactionEntity>>
  fun observeCategories(): Flow<List<CategoryEntity>>
  fun observeCurrencies(): Flow<List<CurrencyEntity>>
  fun observeBudgets(): Flow<List<BudgetEntity>>
  fun observeLoans(): Flow<List<LoanEntity>>
  fun observeAllEmiPayments(): Flow<List<EmiPaymentEntity>>
  fun observeEmiPaymentsForLoan(loanId: Long): Flow<List<EmiPaymentEntity>>
  fun observeSavingsGoals(): Flow<List<SavingsGoalEntity>>
  fun observeSettings(): Flow<AppSettingsEntity?>
  fun observeAccounts(): Flow<List<AccountEntity>>
  fun observeShoppingLists(): Flow<List<ShoppingListEntity>>
  fun observeAllShoppingItems(): Flow<List<ShoppingItemEntity>>
  fun observeAllShopPrices(): Flow<List<ShoppingShopPriceEntity>>
  fun observeInvestments(): Flow<List<InvestmentEntity>>

  suspend fun ensureInitialized()
  suspend fun addTransaction(transaction: TransactionEntity): Result<Long>
  suspend fun updateTransaction(transaction: TransactionEntity): Result<Unit>
  suspend fun deleteTransaction(transactionId: Long): Result<Unit>
  suspend fun markBillPaid(transactionId: Long): Result<Unit>

  suspend fun upsertBudget(budget: BudgetEntity): Result<Long>
  suspend fun deleteBudget(budgetId: Long): Result<Unit>

  suspend fun addLoan(loan: LoanEntity): Result<Long>
  suspend fun updateLoan(loan: LoanEntity): Result<Unit>
  suspend fun deleteLoan(loan: LoanEntity): Result<Unit>
  suspend fun recordEmiPayment(loanId: Long, paymentAmount: BigDecimal, extraPrepayment: BigDecimal, paymentDateEpochDay: Long, note: String): Result<Long>

  suspend fun upsertSavingsGoal(goal: SavingsGoalEntity): Result<Long>
  suspend fun deleteSavingsGoal(goal: SavingsGoalEntity): Result<Unit>
  suspend fun contributeToSavingsGoal(goalId: Long, amount: BigDecimal, currencyCode: String, dateEpochDay: Long, note: String): Result<Long>

  suspend fun upsertShoppingList(list: ShoppingListEntity): Result<Long>
  suspend fun deleteShoppingList(listId: Long): Result<Unit>
  suspend fun upsertShoppingItem(item: ShoppingItemEntity): Result<Long>
  suspend fun deleteShoppingItem(itemId: Long): Result<Unit>
  suspend fun upsertShopPrice(price: ShoppingShopPriceEntity): Result<Long>
  suspend fun deleteShopPrice(priceId: Long): Result<Unit>
  suspend fun upsertInvestment(investment: InvestmentEntity): Result<Long>
  suspend fun deleteInvestment(investmentId: Long): Result<Unit>

  suspend fun updateSettings(transform: (AppSettingsEntity) -> AppSettingsEntity): Result<Unit>
  suspend fun setCurrencyWalletActive(code: String, active: Boolean): Result<Unit>
  suspend fun updateExchangeRate(code: String, rateToBase: BigDecimal?, source: String?): Result<Unit>

  suspend fun eraseAllFinancialData(): Result<Unit>
}

/**
 * Clean Phase 3 integration boundary for optional Google Drive AppData backup and restore.
 * Not implemented with network calls in Phase 1 (strictly offline-first).
 */
interface CloudBackupGateway {
  suspend fun isCloudAccountLinked(): Boolean
  suspend fun getLinkedAccountEmail(): String?
  suspend fun exportEncryptedPayloadToLocalFile(passphrase: CharArray): Result<String>
  suspend fun importEncryptedPayloadFromJson(encryptedEnvelopeJson: String, passphrase: CharArray, overwriteExisting: Boolean): Result<Int>
}
