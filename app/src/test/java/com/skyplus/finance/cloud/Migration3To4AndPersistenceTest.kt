package com.skyplus.finance.cloud

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.skyplus.finance.data.database.DatabaseMigrations
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.SyncConflictEntity
import com.skyplus.finance.data.entities.SyncConflictStatus
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.SyncEntityTypes
import com.skyplus.finance.data.entities.SyncOperationType
import com.skyplus.finance.data.entities.SyncOutboxEntity
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Migration3To4AndPersistenceTest {

  private lateinit var context: Context
  private val dbName = "migration_v3_to_v4_test.db"
  private var migratedDb: SkyPlusDatabase? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.getDatabasePath(dbName).delete()
    File("${context.getDatabasePath(dbName).absolutePath}-wal").delete()
    File("${context.getDatabasePath(dbName).absolutePath}-shm").delete()
  }

  @After
  fun tearDown() {
    migratedDb?.close()
    context.getDatabasePath(dbName).delete()
  }

  @Test
  fun `migration 3 to 4 preserves all v3 financial records and validates room v4 schema`() = runTest {
    // 1. Create authentic Room v3 SQLite database on disk matching schema 3.json
    val v3Callback = object : SupportSQLiteOpenHelper.Callback(3) {
      override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountName` TEXT NOT NULL, `accountType` TEXT NOT NULL, `primaryCurrencyCode` TEXT NOT NULL, `openingBalance` TEXT NOT NULL, `isGuestLocal` INTEGER NOT NULL, `googleAccountEmail` TEXT, `createdAtEpochMillis` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nameKey` TEXT NOT NULL, `displayNameEn` TEXT NOT NULL, `displayNameTa` TEXT NOT NULL, `type` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `colorHex` INTEGER NOT NULL, `isSystemDefault` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_nameKey_type` ON `categories` (`nameKey`, `type`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `type` TEXT NOT NULL, `amount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `exchangeRateToPrimary` TEXT, `exchangeRateSource` TEXT, `exchangeRateTimestamp` INTEGER, `convertedPrimaryAmount` TEXT, `primaryCurrencyAtEntry` TEXT, `description` TEXT NOT NULL, `transactionDateEpochDay` INTEGER NOT NULL, `isRecurring` INTEGER NOT NULL, `recurrenceIntervalDays` INTEGER, `isUpcomingBill` INTEGER NOT NULL, `isPaid` INTEGER NOT NULL, `linkedGoalId` INTEGER, `linkedLoanId` INTEGER, `deduplicationKey` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_transactionDateEpochDay` ON `transactions` (`transactionDateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_type` ON `transactions` (`type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_currencyCode` ON `transactions` (`currencyCode`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_deduplicationKey` ON `transactions` (`deduplicationKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `categoryId` INTEGER NOT NULL, `yearMonth` TEXT NOT NULL, `limitAmount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `rolloverEnabled` INTEGER NOT NULL, `rolloverAmount` TEXT NOT NULL, `warningThresholdPercent` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_categoryId_yearMonth_currencyCode` ON `budgets` (`categoryId`, `yearMonth`, `currencyCode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_yearMonth` ON `budgets` (`yearMonth`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `loans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `loanName` TEXT NOT NULL, `lender` TEXT NOT NULL, `originalPrincipal` TEXT NOT NULL, `outstandingPrincipal` TEXT NOT NULL, `annualInterestRatePercent` TEXT NOT NULL, `emiAmount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `paymentDueDayOfMonth` INTEGER NOT NULL, `nextDueDateEpochDay` INTEGER NOT NULL, `remainingTenureMonths` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loans_status` ON `loans` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loans_nextDueDateEpochDay` ON `loans` (`nextDueDateEpochDay`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `emi_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `loanId` INTEGER NOT NULL, `paymentDateEpochDay` INTEGER NOT NULL, `totalPaidAmount` TEXT NOT NULL, `principalComponent` TEXT NOT NULL, `interestComponent` TEXT NOT NULL, `extraPrepaymentAmount` TEXT NOT NULL, `remainingBalanceAfter` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`loanId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_payments_loanId` ON `emi_payments` (`loanId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_payments_paymentDateEpochDay` ON `emi_payments` (`paymentDateEpochDay`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goalName` TEXT NOT NULL, `goalCategory` TEXT NOT NULL, `targetAmount` TEXT NOT NULL, `currentSavedAmount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `targetDateEpochDay` INTEGER, `isEmergencyFund` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_isEmergencyFund` ON `savings_goals` (`isEmergencyFund`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `currencies` (`code` TEXT NOT NULL, `numericCode` TEXT NOT NULL, `displayName` TEXT NOT NULL, `symbol` TEXT NOT NULL, `decimalPrecision` INTEGER NOT NULL, `isActiveInWallet` INTEGER NOT NULL, `cachedRateToBase` TEXT, `rateSource` TEXT, `rateUpdatedAtEpochMillis` INTEGER, PRIMARY KEY(`code`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `monthly_financial_summaries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `yearMonth` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `totalIncome` TEXT NOT NULL, `totalExpense` TEXT NOT NULL, `totalSavingsAllocated` TEXT NOT NULL, `totalEmiPaid` TEXT NOT NULL, `safeToSpendSnapshot` TEXT NOT NULL, `confirmedRemainingIncome` TEXT NOT NULL, `reservedAmount` TEXT NOT NULL, `monthlyEssentialExpenses` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_monthly_financial_summaries_yearMonth_currencyCode` ON `monthly_financial_summaries` (`yearMonth`, `currencyCode`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `primaryCurrencyCode` TEXT NOT NULL, `languageCode` TEXT NOT NULL, `themeMode` TEXT NOT NULL, `reservedAmount` TEXT NOT NULL, `monthlySavingsAllocation` TEXT NOT NULL, `confirmedRemainingIncome` TEXT NOT NULL, `monthlyEssentialExpenses` TEXT NOT NULL, `appLockEnabled` INTEGER NOT NULL, `lockOnLaunch` INTEGER NOT NULL, `lockOnBackgroundReturn` INTEGER NOT NULL, `biometricPreferred` INTEGER NOT NULL, `guestProfileName` TEXT NOT NULL, `dailySpendingSummaryEnabled` INTEGER NOT NULL, `spendingSummaryHour` INTEGER NOT NULL, `spendingSummaryMinute` INTEGER NOT NULL, `budgetAlertsEnabled` INTEGER NOT NULL, `billEmiRemindersEnabled` INTEGER NOT NULL, `shoppingBudgetRemindersEnabled` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `budgetAmount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `listId` INTEGER NOT NULL, `name` TEXT NOT NULL, `categoryName` TEXT NOT NULL, `quantity` TEXT NOT NULL, `unit` TEXT NOT NULL, `estimatedUnitPrice` TEXT NOT NULL, `actualUnitPrice` TEXT, `isPurchased` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`listId`) REFERENCES `shopping_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_listId` ON `shopping_items` (`listId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_shop_prices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemNameNormalized` TEXT NOT NULL, `productDisplayName` TEXT NOT NULL, `shopName` TEXT NOT NULL, `shopLocation` TEXT NOT NULL, `unit` TEXT NOT NULL, `price` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `priceSource` TEXT NOT NULL, `isLiveFeedAvailable` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_shop_prices_itemNameNormalized` ON `shopping_shop_prices` (`itemNameNormalized`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `investments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `investmentName` TEXT NOT NULL, `investmentType` TEXT NOT NULL, `amountInvested` TEXT NOT NULL, `investmentDateEpochDay` INTEGER NOT NULL, `currentValue` TEXT, `notes` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")

        // Seed realistic v3 financial data
        db.execSQL("INSERT INTO `accounts` VALUES (1, 'Guest Wallet', 'LOCAL_WALLET', 'INR', '15000.00', 1, NULL, 1000)")
        db.execSQL("INSERT INTO `categories` VALUES (1, 'groceries', 'Groceries', 'Groceries', 'EXPENSE', 'shopping_cart', 4280842448, 1, 1)")
        db.execSQL("INSERT INTO `currencies` VALUES ('INR', '356', 'Indian Rupee', '₹', 2, 1, '1', 'BASE', 1000)")
        db.execSQL("INSERT INTO `app_settings` VALUES (1, 'INR', 'en', 'SYSTEM', '5000', '3000', '20000', '10000', 0, 1, 1, 1, 'Guest Mode', 0, 20, 30, 1, 1, 1, 1000)")
        db.execSQL("INSERT INTO `transactions` VALUES (7, 1, 1, 'EXPENSE', '1245.50', 'INR', NULL, NULL, NULL, NULL, NULL, 'Weekly Superstore', 20100, 0, NULL, 0, 1, NULL, NULL, 'tx-v3-dedup', 1000, 1000)")
        db.execSQL("INSERT INTO `loans` VALUES (3, 'Car Loan', 'HDFC', '500000.00', '410000.00', '9.25', '12500.00', 'INR', 5, 20120, 36, 20000, 'ACTIVE', 1000, 1000)")
        db.execSQL("INSERT INTO `savings_goals` VALUES (4, 'Emergency Reserve', 'EMERGENCY_FUND', '100000.00', '42000.00', 'INR', NULL, 1, 1000, 1000)")
        db.execSQL("INSERT INTO `investments` VALUES (5, 'Nifty Index', 'INDEX_FUND', '50000.00', 20050, '56500.00', 'SIP', 'INR', 1000, 1000)")
        db.execSQL("INSERT INTO `shopping_lists` VALUES (6, 'Home Pantry', '3000.00', 'INR', 'Monthly', 1000, 1000)")
        db.execSQL("INSERT INTO `shopping_items` VALUES (8, 6, 'Olive Oil', 'Groceries', '1', 'litre', '850.00', '820.00', 1, '', 1000, 1000)")
      }

      override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    val openHelper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(v3Callback)
        .build(),
    )
    openHelper.writableDatabase.use { /* triggers onCreate */ }
    openHelper.close()

    // 2. Open with Room at v4 using MIGRATION_3_4; Room strictly validates every column/index
    val db = Room.databaseBuilder(context, SkyPlusDatabase::class.java, dbName)
      .addMigrations(*DatabaseMigrations.allMigrations())
      .allowMainThreadQueries()
      .build()
    migratedDb = db

    // Verify all v3 records survived untouched
    val txs = db.transactionDao().getAllTransactionsOnce()
    assertEquals(1, txs.size)
    assertEquals("Weekly Superstore", txs.first().description)
    assertEquals(0, txs.first().amount.compareTo(BigDecimal("1245.50")))

    val loans = db.loanDao().getAllLoansOnce()
    assertEquals(1, loans.size)
    assertEquals("Car Loan", loans.first().loanName)

    val goals = db.savingsGoalDao().getAllGoalsOnce()
    assertEquals(1, goals.size)
    assertEquals("Emergency Reserve", goals.first().goalName)

    val investments = db.investmentDao().getAllInvestmentsOnce()
    assertEquals(1, investments.size)
    assertEquals("Nifty Index", investments.first().investmentName)

    val shoppingLists = db.shoppingDao().getAllShoppingListsOnce()
    assertEquals(1, shoppingLists.size)
    val shoppingItems = db.shoppingDao().getAllShoppingItemsOnce()
    assertEquals(1, shoppingItems.size)
    assertEquals("Olive Oil", shoppingItems.first().name)

    // Verify v4 sync tables and DAOs work properly
    val syncDao = db.syncDao()
    syncDao.upsertMap(
      SyncEntityMapEntity(
        ownerUid = "uid-1",
        entityType = SyncEntityTypes.TRANSACTION,
        localId = "7",
        recordId = "rec-7",
        syncVersion = 1L,
        lastSyncedVersion = 0L,
        lastSyncedAtEpochMillis = 0L,
        isDirty = true,
        isDeleted = false,
        updatedByDeviceId = "dev-1",
        updatedAtEpochMillis = 2000L,
      ),
    )
    assertNotNull(syncDao.findMapByRecordId("uid-1", SyncEntityTypes.TRANSACTION, "rec-7"))

    // Verify idempotencyKey unique replacement updates instead of duplicating
    syncDao.upsertOutboxItem(
      SyncOutboxEntity(
        idempotencyKey = "uid-1:TRANSACTION:rec-7",
        ownerUid = "uid-1",
        entityType = SyncEntityTypes.TRANSACTION,
        localId = "7",
        recordId = "rec-7",
        operation = SyncOperationType.UPSERT.name,
        payloadJson = """{"v":1}""",
        baseSyncVersion = 0L,
        attemptCount = 0,
        nextRetryAtEpochMillis = 1000L,
        lastError = null,
        createdAtEpochMillis = 1000L,
        updatedAtEpochMillis = 1000L,
      ),
    )
    syncDao.upsertOutboxItem(
      SyncOutboxEntity(
        idempotencyKey = "uid-1:TRANSACTION:rec-7",
        ownerUid = "uid-1",
        entityType = SyncEntityTypes.TRANSACTION,
        localId = "7",
        recordId = "rec-7",
        operation = SyncOperationType.UPSERT.name,
        payloadJson = """{"v":2}""",
        baseSyncVersion = 0L,
        attemptCount = 0,
        nextRetryAtEpochMillis = 2000L,
        lastError = null,
        createdAtEpochMillis = 2000L,
        updatedAtEpochMillis = 2000L,
      ),
    )
    val outbox = syncDao.getAllOutboxItemsForOwner("uid-1")
    assertEquals(1, outbox.size)
    assertTrue(outbox.first().payloadJson.contains("2"))

    syncDao.upsertConflict(
      SyncConflictEntity(
        ownerUid = "uid-1",
        entityType = SyncEntityTypes.TRANSACTION,
        localId = "7",
        recordId = "rec-7",
        localPayloadJson = """{"a":1}""",
        remotePayloadJson = """{"a":2}""",
        localSummary = "Local 1",
        remoteSummary = "Cloud 2",
        localUpdatedAtEpochMillis = 1000L,
        remoteUpdatedAtEpochMillis = 2000L,
        remoteSyncVersion = 2L,
        remoteDeviceId = "other-dev",
        status = SyncConflictStatus.PENDING.name,
        createdAtEpochMillis = 2000L,
      ),
    )
    assertEquals(1, syncDao.getPendingConflicts("uid-1").size)
  }
}
