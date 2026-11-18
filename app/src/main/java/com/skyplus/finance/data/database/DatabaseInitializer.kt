package com.skyplus.finance.data.database

import androidx.room.withTransaction
import com.skyplus.finance.data.entities.AccountEntity
import com.skyplus.finance.data.entities.AppSettingsEntity
import java.math.BigDecimal
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DatabaseInitializer @Inject constructor(
  private val database: SkyPlusDatabase,
) {
  private val initMutex = Mutex()
  @Volatile
  private var initialized = false

  suspend fun ensureSeeded(locale: Locale = Locale.getDefault()) {
    if (initialized) return
    initMutex.withLock {
      if (initialized) return
      val now = System.currentTimeMillis()
      val suggestedCurrency = Iso4217CurrencyCatalog.suggestDefaultCurrencyCode(locale)

      database.withTransaction {
        val currencyDao = database.currencyDao()
        if (currencyDao.getCurrencyCount() == 0) {
          currencyDao.insertAllIgnore(Iso4217CurrencyCatalog.getAllOfficialCurrencies())
        }
        currencyDao.setWalletActive(suggestedCurrency, true)

        val categoryDao = database.categoryDao()
        if (categoryDao.getCategoryCount() == 0) {
          categoryDao.insertAll(DefaultCategoryCatalog.defaultCategories())
        }

        val accountDao = database.accountDao()
        if (accountDao.getPrimaryAccountOnce() == null) {
          accountDao.insertAccount(
            AccountEntity(
              accountName = "Primary Wallet",
              accountType = "LOCAL_WALLET",
              primaryCurrencyCode = suggestedCurrency,
              openingBalance = BigDecimal.ZERO,
              isGuestLocal = true,
              googleAccountEmail = null,
              createdAtEpochMillis = now,
            ),
          )
        }

        val settingsDao = database.appSettingsDao()
        if (settingsDao.getSettingsOnce() == null) {
          settingsDao.upsertSettings(
            AppSettingsEntity(
              id = 1,
              primaryCurrencyCode = suggestedCurrency,
              languageCode = "en",
              themeMode = "SYSTEM",
              reservedAmount = BigDecimal.ZERO,
              monthlySavingsAllocation = BigDecimal.ZERO,
              confirmedRemainingIncome = BigDecimal.ZERO,
              monthlyEssentialExpenses = BigDecimal.ZERO,
              appLockEnabled = false,
              lockOnLaunch = true,
              lockOnBackgroundReturn = true,
              biometricPreferred = true,
              guestProfileName = "Guest Mode",
              updatedAtEpochMillis = now,
            ),
          )
        }
      }
      initialized = true
    }
  }

  fun resetCachedFlag() {
    initialized = false
  }
}
