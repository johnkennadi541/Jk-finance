package com.skyplus.finance.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.skyplus.finance.data.dao.AccountDao
import com.skyplus.finance.data.dao.AppSettingsDao
import com.skyplus.finance.data.dao.BudgetDao
import com.skyplus.finance.data.dao.CategoryDao
import com.skyplus.finance.data.dao.CurrencyDao
import com.skyplus.finance.data.dao.LoanDao
import com.skyplus.finance.data.dao.MonthlySummaryDao
import com.skyplus.finance.data.dao.SavingsGoalDao
import com.skyplus.finance.data.dao.ShoppingDao
import com.skyplus.finance.data.dao.SyncDao
import com.skyplus.finance.data.dao.TransactionDao
import com.skyplus.finance.data.entities.AccountEntity
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.InvestmentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.MonthlyFinancialSummaryEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import com.skyplus.finance.data.entities.SyncConflictEntity
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.SyncOutboxEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.dao.InvestmentDao

@Database(
  entities = [
    TransactionEntity::class,
    CategoryEntity::class,
    BudgetEntity::class,
    LoanEntity::class,
    EmiPaymentEntity::class,
    SavingsGoalEntity::class,
    AccountEntity::class,
    CurrencyEntity::class,
    MonthlyFinancialSummaryEntity::class,
    AppSettingsEntity::class,
    ShoppingListEntity::class,
    ShoppingItemEntity::class,
    ShoppingShopPriceEntity::class,
    InvestmentEntity::class,
    SyncEntityMapEntity::class,
    SyncOutboxEntity::class,
    SyncConflictEntity::class,
  ],
  version = 4,
  exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SkyPlusDatabase : RoomDatabase() {
  abstract fun transactionDao(): TransactionDao
  abstract fun categoryDao(): CategoryDao
  abstract fun budgetDao(): BudgetDao
  abstract fun loanDao(): LoanDao
  abstract fun savingsGoalDao(): SavingsGoalDao
  abstract fun accountDao(): AccountDao
  abstract fun currencyDao(): CurrencyDao
  abstract fun monthlySummaryDao(): MonthlySummaryDao
  abstract fun appSettingsDao(): AppSettingsDao
  abstract fun shoppingDao(): ShoppingDao
  abstract fun investmentDao(): InvestmentDao
  abstract fun syncDao(): SyncDao

  companion object {
    const val DATABASE_NAME = "skyplus_finance_encrypted.db"
  }
}
