package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.skyplus.finance.data.entities.AccountEntity
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.MonthlyFinancialSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
  @Query("SELECT * FROM accounts ORDER BY id ASC")
  fun observeAccounts(): Flow<List<AccountEntity>>

  @Query("SELECT * FROM accounts ORDER BY id ASC")
  suspend fun getAllAccountsOnce(): List<AccountEntity>

  @Query("SELECT * FROM accounts ORDER BY id ASC LIMIT 1")
  suspend fun getPrimaryAccountOnce(): AccountEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAccount(account: AccountEntity): Long

  @Update
  suspend fun updateAccount(account: AccountEntity)

  @Query("DELETE FROM accounts")
  suspend fun deleteAll()
}

@Dao
interface CurrencyDao {
  @Query("SELECT * FROM currencies ORDER BY isActiveInWallet DESC, code ASC")
  fun observeAllCurrencies(): Flow<List<CurrencyEntity>>

  @Query("SELECT * FROM currencies ORDER BY code ASC")
  suspend fun getAllCurrenciesOnce(): List<CurrencyEntity>

  @Query("SELECT * FROM currencies WHERE code = :code LIMIT 1")
  suspend fun getCurrencyByCode(code: String): CurrencyEntity?

  @Query("SELECT COUNT(*) FROM currencies")
  suspend fun getCurrencyCount(): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAllIgnore(currencies: List<CurrencyEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(currency: CurrencyEntity)

  @Query("UPDATE currencies SET isActiveInWallet = :active WHERE code = :code")
  suspend fun setWalletActive(code: String, active: Boolean)
}

@Dao
interface MonthlySummaryDao {
  @Query("SELECT * FROM monthly_financial_summaries ORDER BY yearMonth DESC, currencyCode ASC")
  fun observeAllSummaries(): Flow<List<MonthlyFinancialSummaryEntity>>

  @Query("SELECT * FROM monthly_financial_summaries WHERE yearMonth = :yearMonth AND currencyCode = :currencyCode LIMIT 1")
  suspend fun getSummary(yearMonth: String, currencyCode: String): MonthlyFinancialSummaryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(summary: MonthlyFinancialSummaryEntity): Long

  @Query("DELETE FROM monthly_financial_summaries")
  suspend fun deleteAll()
}

@Dao
interface AppSettingsDao {
  @Query("SELECT * FROM app_settings WHERE id = 1")
  fun observeSettings(): Flow<AppSettingsEntity?>

  @Query("SELECT * FROM app_settings WHERE id = 1")
  suspend fun getSettingsOnce(): AppSettingsEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSettings(settings: AppSettingsEntity)

  @Query("DELETE FROM app_settings")
  suspend fun deleteAll()
}
