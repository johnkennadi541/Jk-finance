package com.skyplus.finance

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.skyplus.finance.data.backup.GoogleAuthConfigStatus
import com.skyplus.finance.data.database.DatabaseInitializer
import com.skyplus.finance.data.database.DatabaseMigrations
import com.skyplus.finance.data.database.Iso4217CurrencyCatalog
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.export.FinancialExportManager
import com.skyplus.finance.data.export.FinancialReportExporter
import com.skyplus.finance.data.repository.OfflineFinanceRepository
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.BackupCryptoManager
import com.skyplus.finance.designsystem.SkyPlusBrandTheme
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.calculator.SafeToSpendCalculator
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceEngine
import com.skyplus.finance.domain.intelligence.FinancialHealthScoreEngine
import com.skyplus.finance.domain.intelligence.ForecastAndRecommendationEngine
import com.skyplus.finance.domain.intelligence.SpendingIntelligenceEngine
import com.skyplus.finance.domain.usecase.PortfolioIntelligenceBundle
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.monetization.OfflineFreeEntitlementGateway
import com.skyplus.finance.domain.monetization.SkyPlusTier
import com.skyplus.finance.domain.report.ReportBuilderUseCase
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.domain.report.ReportRequest
import com.skyplus.finance.domain.shopping.SearchMatchType
import com.skyplus.finance.domain.shopping.ShoppingBudgetStatus
import com.skyplus.finance.domain.shopping.ShoppingPlannerCalculator
import com.skyplus.finance.domain.shopping.SmartProductSearchEngine
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.domain.usecase.PortfolioChartBuilder
import com.skyplus.finance.localization.AppStringsProvider
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.home.HomeScreen
import com.skyplus.finance.presentation.profile.ProfileScreen
import com.skyplus.finance.presentation.shopping.SmartShoppingScreen
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartShoppingAndPhase3Test {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: SkyPlusDatabase
  private lateinit var repository: OfflineFinanceRepository
  private lateinit var exportManager: FinancialExportManager
  private lateinit var reportExporter: FinancialReportExporter
  private val searchEngine = SmartProductSearchEngine()
  private val plannerCalculator = ShoppingPlannerCalculator()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, SkyPlusDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val initializer = DatabaseInitializer(db)
    repository = OfflineFinanceRepository(db, initializer, LoanEmiCalculator())
    val prefs = AppPreferencesDataStore(context)
    exportManager = FinancialExportManager(
      context = context,
      database = db,
      backupCryptoManager = BackupCryptoManager(),
      preferencesDataStore = prefs,
    )
    reportExporter = FinancialReportExporter(
      context = context,
      preferencesDataStore = prefs,
    )
  }

  @After
  fun tearDown() {
    db.close()
    context.deleteDatabase("phase3_v1_to_v2_test.db")
  }

  @Test
  fun `room v1 to v2 migration preserves all user financial records and supports smart shopping`() = runTest {
    val dbName = "phase3_v1_to_v2_test.db"
    context.deleteDatabase(dbName)

    val callback = object : SupportSQLiteOpenHelper.Callback(1) {
      override fun onCreate(sqlDb: SupportSQLiteDatabase) {
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountName` TEXT NOT NULL, `accountType` TEXT NOT NULL, `primaryCurrencyCode` TEXT NOT NULL, `openingBalance` TEXT NOT NULL, `isGuestLocal` INTEGER NOT NULL, `googleAccountEmail` TEXT, `createdAtEpochMillis` INTEGER NOT NULL)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nameKey` TEXT NOT NULL, `displayNameEn` TEXT NOT NULL, `displayNameTa` TEXT NOT NULL, `type` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `colorHex` INTEGER NOT NULL, `isSystemDefault` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)")
        sqlDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_nameKey_type` ON `categories` (`nameKey`, `type`)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `type` TEXT NOT NULL, `amount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `exchangeRateToPrimary` TEXT, `exchangeRateSource` TEXT, `exchangeRateTimestamp` INTEGER, `convertedPrimaryAmount` TEXT, `primaryCurrencyAtEntry` TEXT, `description` TEXT NOT NULL, `transactionDateEpochDay` INTEGER NOT NULL, `isRecurring` INTEGER NOT NULL, `recurrenceIntervalDays` INTEGER, `isUpcomingBill` INTEGER NOT NULL, `isPaid` INTEGER NOT NULL, `linkedGoalId` INTEGER, `linkedLoanId` INTEGER, `deduplicationKey` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_transactionDateEpochDay` ON `transactions` (`transactionDateEpochDay`)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_type` ON `transactions` (`type`)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_currencyCode` ON `transactions` (`currencyCode`)")
        sqlDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_deduplicationKey` ON `transactions` (`deduplicationKey`)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `categoryId` INTEGER NOT NULL, `yearMonth` TEXT NOT NULL, `limitAmount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `rolloverEnabled` INTEGER NOT NULL, `rolloverAmount` TEXT NOT NULL, `warningThresholdPercent` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        sqlDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_categoryId_yearMonth_currencyCode` ON `budgets` (`categoryId`, `yearMonth`, `currencyCode`)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_yearMonth` ON `budgets` (`yearMonth`)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `loans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `loanName` TEXT NOT NULL, `lender` TEXT NOT NULL, `originalPrincipal` TEXT NOT NULL, `outstandingPrincipal` TEXT NOT NULL, `annualInterestRatePercent` TEXT NOT NULL, `emiAmount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `paymentDueDayOfMonth` INTEGER NOT NULL, `nextDueDateEpochDay` INTEGER NOT NULL, `remainingTenureMonths` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_loans_status` ON `loans` (`status`)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_loans_nextDueDateEpochDay` ON `loans` (`nextDueDateEpochDay`)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `emi_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `loanId` INTEGER NOT NULL, `paymentDateEpochDay` INTEGER NOT NULL, `totalPaidAmount` TEXT NOT NULL, `principalComponent` TEXT NOT NULL, `interestComponent` TEXT NOT NULL, `extraPrepaymentAmount` TEXT NOT NULL, `remainingBalanceAfter` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`loanId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_payments_loanId` ON `emi_payments` (`loanId`)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_payments_paymentDateEpochDay` ON `emi_payments` (`paymentDateEpochDay`)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goalName` TEXT NOT NULL, `goalCategory` TEXT NOT NULL, `targetAmount` TEXT NOT NULL, `currentSavedAmount` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `targetDateEpochDay` INTEGER, `isEmergencyFund` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        sqlDb.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_goals_isEmergencyFund` ON `savings_goals` (`isEmergencyFund`)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `currencies` (`code` TEXT NOT NULL, `numericCode` TEXT NOT NULL, `displayName` TEXT NOT NULL, `symbol` TEXT NOT NULL, `decimalPrecision` INTEGER NOT NULL, `isActiveInWallet` INTEGER NOT NULL, `cachedRateToBase` TEXT, `rateSource` TEXT, `rateUpdatedAtEpochMillis` INTEGER, PRIMARY KEY(`code`))")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `monthly_financial_summaries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `yearMonth` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `totalIncome` TEXT NOT NULL, `totalExpense` TEXT NOT NULL, `totalSavingsAllocated` TEXT NOT NULL, `totalEmiPaid` TEXT NOT NULL, `safeToSpendSnapshot` TEXT NOT NULL, `confirmedRemainingIncome` TEXT NOT NULL, `reservedAmount` TEXT NOT NULL, `monthlyEssentialExpenses` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
        sqlDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_monthly_financial_summaries_yearMonth_currencyCode` ON `monthly_financial_summaries` (`yearMonth`, `currencyCode`)")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `primaryCurrencyCode` TEXT NOT NULL, `languageCode` TEXT NOT NULL, `themeMode` TEXT NOT NULL, `reservedAmount` TEXT NOT NULL, `monthlySavingsAllocation` TEXT NOT NULL, `confirmedRemainingIncome` TEXT NOT NULL, `monthlyEssentialExpenses` TEXT NOT NULL, `appLockEnabled` INTEGER NOT NULL, `lockOnLaunch` INTEGER NOT NULL, `lockOnBackgroundReturn` INTEGER NOT NULL, `biometricPreferred` INTEGER NOT NULL, `guestProfileName` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        sqlDb.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        sqlDb.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '7bc7087474c432a40002f77d9e54a4da')")

        // Seed v1 user records
        sqlDb.execSQL("INSERT INTO accounts VALUES (1, 'Primary', 'PERSONAL', 'INR', '0', 1, NULL, 1000)")
        sqlDb.execSQL("INSERT INTO categories VALUES (1, 'groceries', 'Groceries', 'மளிகை', 'EXPENSE', 'shopping_cart', 4280842448, 1, 1)")
        sqlDb.execSQL("INSERT INTO transactions VALUES (1, 1, 1, 'EXPENSE', '1250.50', 'INR', NULL, NULL, NULL, NULL, NULL, 'Preserved V1 Record', 20000, 0, NULL, 0, 1, NULL, NULL, 'v1-key-1', 1000, 1000)")
        sqlDb.execSQL("INSERT INTO app_settings VALUES (1, 'INR', 'en', 'SYSTEM', '1000', '2000', '0', '10000', 0, 1, 1, 1, 'Local Guest', 1000)")
      }

      override fun onUpgrade(sqlDb: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    val helper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(callback)
        .build(),
    )
    helper.writableDatabase.close()
    helper.close()

    val migratedDb = Room.databaseBuilder(context, SkyPlusDatabase::class.java, dbName)
      .addMigrations(*DatabaseMigrations.allMigrations())
      .allowMainThreadQueries()
      .build()

    try {
      val preservedTxs = migratedDb.transactionDao().getAllTransactionsOnce()
      assertEquals(1, preservedTxs.size)
      assertEquals("Preserved V1 Record", preservedTxs.first().description)
      assertEquals(0, BigDecimal("1250.50").compareTo(preservedTxs.first().amount))

      val listId = migratedDb.shoppingDao().insertList(
        ShoppingListEntity(
          name = "Sunday Market",
          budgetAmount = BigDecimal("1500.00"),
          currencyCode = "INR",
          createdAtEpochMillis = 2000L,
          updatedAtEpochMillis = 2000L,
        ),
      )
      assertTrue(listId > 0L)
      assertEquals(1, migratedDb.shoppingDao().getAllShoppingListsOnce().size)
    } finally {
      migratedDb.close()
    }
  }

  @Test
  fun `smart product search returns no preloaded catalog and searches only user saved items`() {
    // Without user-saved items or shop prices, nothing preloaded (like Tomato/Onion/Potato) is shown
    assertTrue(searchEngine.search("").isEmpty())
    assertTrue(searchEngine.search("Tomato").isEmpty())
    assertTrue(searchEngine.search("Onion").isEmpty())

    val userItems = listOf(
      ShoppingItemEntity(
        id = 1L,
        listId = 1L,
        name = "Basmati Rice",
        categoryName = "Groceries",
        quantity = BigDecimal("5"),
        unit = "kg",
        estimatedUnitPrice = BigDecimal("110.00"),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
      ),
      ShoppingItemEntity(
        id = 2L,
        listId = 1L,
        name = "பசும்பால்",
        categoryName = "Dairy Products",
        quantity = BigDecimal("1"),
        unit = "litre",
        estimatedUnitPrice = BigDecimal.ZERO,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
      ),
    )
    val userPrices = listOf(
      ShoppingShopPriceEntity(
        id = 1L,
        itemNameNormalized = "basmati_rice",
        productDisplayName = "Basmati Rice",
        shopName = "Fresh Mart",
        shopLocation = "Main Road",
        unit = "kg",
        price = BigDecimal("105.00"),
        currencyCode = "INR",
        priceSource = "Google Search",
        isLiveFeedAvailable = false,
        updatedAtEpochMillis = 1000L,
      ),
    )

    val english = searchEngine.search("Basmati", userItems, userPrices)
    assertTrue(english.any { it.displayEn == "Basmati Rice" && it.matchType == SearchMatchType.EXACT_OR_CONTAINS })

    val tamil = searchEngine.search("பசும்பால்", userItems, userPrices)
    assertTrue(tamil.any { it.displayEn == "பசும்பால்" && it.matchType == SearchMatchType.TAMIL_SCRIPT })

    val typo = searchEngine.search("Basamti", userItems, userPrices)
    assertTrue(typo.any { it.displayEn == "Basmati Rice" })
  }

  @Test
  fun `shopping planner and price comparison compute exact bigdecimal totals and never fake live feeds`() {
    val lists = listOf(
      ShoppingListEntity(id = 1L, name = "KWD Mart", budgetAmount = BigDecimal("25.000"), currencyCode = "KWD", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L),
    )
    val items = listOf(
      ShoppingItemEntity(
        id = 10L,
        listId = 1L,
        name = "Tomato",
        categoryName = "Vegetables",
        quantity = BigDecimal("2.5"),
        unit = "kg",
        estimatedUnitPrice = BigDecimal("1.200"),
        actualUnitPrice = BigDecimal("1.100"),
        isPurchased = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
      ),
      ShoppingItemEntity(
        id = 11L,
        listId = 1L,
        name = "Rice",
        categoryName = "Grains",
        quantity = BigDecimal("1"),
        unit = "kg",
        estimatedUnitPrice = BigDecimal("20.000"),
        actualUnitPrice = null,
        isPurchased = false,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
      ),
    )
    val overview = plannerCalculator.computePlannerOverview(
      lists = lists,
      items = items,
      currencyPrecisionMap = mapOf("KWD" to 3, "INR" to 2),
      defaultPrecision = 3,
    )
    assertEquals(BigDecimal("23.000"), overview.totalPlannedSpend)
    assertEquals(BigDecimal("2.750"), overview.totalActualSpend)
    assertEquals(BigDecimal("2.000"), overview.remainingOverallBudget)
    assertEquals(ShoppingBudgetStatus.OVER_BUDGET.let { ShoppingBudgetStatus.APPROACHING }, overview.overallStatus)
    assertEquals(2, overview.categoryBreakdown.size)

    val prices = listOf(
      ShoppingShopPriceEntity(1L, "tomato", "Tomato", "SuperMart", "T. Nagar", "kg", BigDecimal("48.00"), "INR", "Manual Entry", false, 1000L),
      ShoppingShopPriceEntity(2L, "tomato", "Tomato", "Fresh Stall", "Koyambedu", "kg", BigDecimal("35.00"), "INR", "Manual Entry", false, 2000L),
    )
    val groups = plannerCalculator.groupShopPrices(prices, mapOf("INR" to 2))
    assertEquals(1, groups.size)
    assertEquals("Fresh Stall", groups.first().entriesSortedLowestFirst.first().shopName)
    assertEquals(BigDecimal("13.00"), groups.first().potentialSavings)
    assertFalse(groups.first().hasLivePrices)
  }

  @Test
  fun `encrypted backup and restore preserves phase 3 shopping lists items and shop prices`() = runTest {
    repository.ensureInitialized()
    val listId = repository.upsertShoppingList(
      ShoppingListEntity(name = "Home Pantry", budgetAmount = BigDecimal("4500.00"), currencyCode = "INR", createdAtEpochMillis = 10L, updatedAtEpochMillis = 10L),
    ).getOrThrow()
    repository.upsertShoppingItem(
      ShoppingItemEntity(listId = listId, name = "Basmati Rice", categoryName = "Grains", quantity = BigDecimal("5"), unit = "kg", estimatedUnitPrice = BigDecimal("110.00"), createdAtEpochMillis = 10L, updatedAtEpochMillis = 10L),
    ).getOrThrow()
    repository.upsertShopPrice(
      ShoppingShopPriceEntity(itemNameNormalized = "rice", productDisplayName = "Basmati Rice", shopName = "City Store", shopLocation = "Velachery", unit = "kg", price = BigDecimal("105.00"), currencyCode = "INR", updatedAtEpochMillis = 10L),
    ).getOrThrow()

    val pass = "Phase3SecurePass!".toCharArray()
    val envelope = exportManager.buildEncryptedBackupString(pass).getOrThrow()
    repository.eraseAllFinancialData().getOrThrow()
    assertTrue(repository.observeShoppingLists().first().isEmpty())

    exportManager.importEncryptedPayloadFromJson(envelope, "Phase3SecurePass!".toCharArray(), overwriteExisting = true).getOrThrow()
    val restoredLists = repository.observeShoppingLists().first()
    val restoredItems = repository.observeAllShoppingItems().first()
    val restoredPrices = repository.observeAllShopPrices().first()
    assertEquals(1, restoredLists.size)
    assertEquals("Home Pantry", restoredLists.first().name)
    assertEquals(1, restoredItems.size)
    assertEquals("Basmati Rice", restoredItems.first().name)
    assertEquals(1, restoredPrices.size)
    assertEquals(0, BigDecimal("105.00").compareTo(restoredPrices.first().price))
  }

  @Test
  fun `daily and phase 3 advanced report exports include emi net worth health metrics across pdf docx csv`() = runTest {
    val inr = Iso4217CurrencyCatalog.getAllOfficialCurrencies().first { it.code == "INR" }
    val today = LocalDate.of(2026, 11, 16)
    val txs = listOf(
      TransactionEntity(1, 0, 10, TransactionType.INCOME, BigDecimal("60000.00"), "INR", description = "Salary", transactionDateEpochDay = today.minusDays(5).toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 0, 1, TransactionType.EXPENSE, BigDecimal("4500.00"), "INR", description = "Daily Spend", transactionDateEpochDay = today.toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
      TransactionEntity(3, 0, 1, TransactionType.EXPENSE, BigDecimal("1200.00"), "INR", description = "Utilities", transactionDateEpochDay = today.minusDays(2).toEpochDay(), createdAtEpochMillis = 3, updatedAtEpochMillis = 3),
    )
    val loans = listOf(
      LoanEntity(1L, "Car Loan", "HDFC", BigDecimal("300000.00"), BigDecimal("220000.00"), BigDecimal("9.5"), BigDecimal("7500.00"), "INR", 10, today.plusDays(5).toEpochDay(), 30, today.minusMonths(6).toEpochDay(), LoanStatus.ACTIVE, 1L, 1L),
    )
    val emis = listOf(
      EmiPaymentEntity(1L, 1L, today.toEpochDay(), BigDecimal("7500.00"), BigDecimal("5758.33"), BigDecimal("1741.67"), BigDecimal.ZERO, BigDecimal("214241.67"), "INR", "Nov EMI", 1L),
    )
    val categories = mapOf(
      1L to CategoryEntity(1, "food", "Food", "உணவு", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
      10L to CategoryEntity(10, "salary", "Salary", "சம்பளம்", CategoryType.INCOME, "payments", 0xFF00BFA6),
    )
    val budgetCalc = BudgetAndSavingsCalculator()
    val precisionMap = mapOf("INR" to 2)
    val health = FinancialHealthScoreEngine(budgetCalc).evaluate(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = loans,
      savingsGoals = emptyList(),
      settings = null,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    val spend = SpendingIntelligenceEngine(budgetCalc).analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = loans,
      emiPayments = emis,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    val forecast = ForecastAndRecommendationEngine().analyze(
      transactions = txs,
      budgets = emptyList(),
      categoriesById = categories,
      loans = loans,
      emiPayments = emis,
      savingsGoals = emptyList(),
      settings = null,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    val debtReport = DebtPayoffIntelligenceEngine(LoanEmiCalculator()).analyze(
      loans = loans,
      primaryCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      realizedMonthlyIncome = health.metrics.realizedIncome.takeIf { it > BigDecimal.ZERO },
      today = today,
    )
    val intel = PortfolioIntelligenceBundle(health, spend, forecast, debtReport)

    val snapshot = DashboardSnapshot(
      primaryCurrency = inr,
      primaryCurrencyCode = "INR",
      themeMode = AppThemeMode.LIGHT,
      linkedGoogleEmail = null,
      settings = null,
      totalAssets = BigDecimal("55500.00"),
      totalLiabilities = BigDecimal("220000.00"),
      totalNetWorth = BigDecimal("-164500.00"),
      monthlyIncome = BigDecimal("60000.00"),
      monthlyExpenses = BigDecimal("4500.00"),
      monthlyNetSavings = BigDecimal("55500.00"),
      safeToSpend = SafeToSpendCalculator().calculate(SafeToSpendInput(BigDecimal("55500.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 15, "INR", 2)),
      activeLoanCount = 1,
      totalOutstandingDebt = BigDecimal("220000.00"),
      totalMonthlyEmi = BigDecimal("7500.00"),
      nextEmiDueEpochDay = today.plusDays(5).toEpochDay(),
      estimatedDebtFreeEpochDay = today.plusMonths(30).toEpochDay(),
      emergencyFund = BudgetAndSavingsCalculator().computeEmergencyFund(emptyList(), BigDecimal("10000.00"), "INR", 2),
      recentTransactions = txs,
      allTransactions = txs,
      categoriesById = categories,
      currenciesByCode = mapOf("INR" to inr),
      allCurrencies = listOf(inr),
      budgetStatuses = emptyList(),
      loans = loans,
      emiPayments = emis,
      savingsGoals = emptyList(),
      currencyBreakdowns = emptyList(),
      snowballPlan = LoanEmiCalculator().calculateDebtStrategyPlan(loans, DebtPayoffStrategy.SNOWBALL),
      avalanchePlan = LoanEmiCalculator().calculateDebtStrategyPlan(loans, DebtPayoffStrategy.AVALANCHE),
      intelligence = intel,
    )

    val report = ReportBuilderUseCase(BudgetAndSavingsCalculator()).build(
      snapshot = snapshot,
      request = ReportRequest(ReportPeriodType.DAILY, today.toEpochDay(), today.toEpochDay(), "INR"),
      today = today,
    )
    assertEquals(1, report.emiPaymentRows.size)
    assertTrue(report.totalEmiPaidInPeriodFormatted.contains("7,500") || report.totalEmiPaidInPeriodFormatted.contains("7500"))
    assertTrue(report.healthScore != null)
    assertTrue(report.netWorthChangePositive)

    val pdf = reportExporter.exportPdf(report).getOrThrow()
    assertTrue(pdf.exists() && pdf.length() > 200L)

    val docx = reportExporter.exportDocx(report).getOrThrow()
    ZipFile(docx).use { zip ->
      val xml = zip.getInputStream(zip.getEntry("word/document.xml")!!).bufferedReader().readText()
      assertTrue(xml.contains("Net Worth"))
      assertTrue(xml.contains("Financial Health"))
      assertTrue(xml.contains("Car Loan"))
    }

    val csv = exportManager.exportReportCsv(report).getOrThrow()
    val csvContent = csv.readText()
    assertTrue(csvContent.contains("Section,MetricOrDate"))
    assertTrue(csvContent.contains("Net Worth Change"))
    assertTrue(csvContent.contains("Daily Spend"))
  }

  @Test
  fun `skyplus one monetization contract stays strictly free zero paywall`() {
    val status = OfflineFreeEntitlementGateway().currentStatus()
    assertEquals(SkyPlusTier.FREE_CORE, status.activeTier)
    assertFalse(status.isUpgradeRequiredNow)
  }

  @Test
  fun `smart shopping ui tabs search google integration unpriced item and planner render cleanly in dark theme`() {
    val list = ShoppingListEntity(1L, "Weekly Kitchen", BigDecimal("2000.00"), "INR", "", 1L, 1L)
    val pricedItem = ShoppingItemEntity(10L, 1L, "Basmati Rice", "Groceries", BigDecimal("2"), "kg", BigDecimal("120.00"), null, false, "", 1L, 1L)
    val unpricedItem = ShoppingItemEntity(11L, 1L, "Olive Oil", "Groceries", BigDecimal("1"), "litre", BigDecimal.ZERO, null, false, "", 1L, 1L)
    val price = ShoppingShopPriceEntity(1L, "basmati_rice", "Basmati Rice", "Local Market", "Adyar", "kg", BigDecimal("112.00"), "INR", "Google Search", false, 1000L)
    val overview = plannerCalculator.computePlannerOverview(listOf(list), listOf(pricedItem, unpricedItem), mapOf("INR" to 2), 2)
    val groups = plannerCalculator.groupShopPrices(listOf(price), mapOf("INR" to 2))

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme(themeMode = AppThemeMode.DARK) {
          SmartShoppingScreen(
            shoppingLists = listOf(list),
            shoppingItems = listOf(pricedItem, unpricedItem),
            shopComparisonGroups = groups,
            plannerOverview = overview,
            primaryCurrency = null,
            primaryCurrencyCode = "INR",
            onUpsertList = { _, _, _, _, _ -> },
            onDeleteList = {},
            onUpsertItem = { _, _, _, _, _, _, _, _ -> },
            onToggleItemPurchased = {},
            onDeleteItem = {},
            onUpsertShopPrice = { _, _, _, _, _, _, _ -> },
            onDeleteShopPrice = {},
            onSearchProducts = { q -> searchEngine.search(q, listOf(pricedItem, unpricedItem), listOf(price)) },
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("shopping_screen_title").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Basmati Rice"))
    composeTestRule.onNodeWithText("Basmati Rice").assertIsDisplayed()
    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Olive Oil"))
    composeTestRule.onNodeWithText("Olive Oil").assertIsDisplayed()
    composeTestRule.onNodeWithText("Price unavailable", substring = true).assertIsDisplayed()

    // Ensure duplicate "Smart Shop" badge and old "Compare" tab are gone
    composeTestRule.onNodeWithText("Smart Shop").assertDoesNotExist()
    composeTestRule.onNodeWithTag("shopping_tab_compare").assertDoesNotExist()

    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("shopping_tab_search"))
    composeTestRule.onNodeWithTag("shopping_tab_search").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Search anything...").assertIsDisplayed()
    composeTestRule.onNodeWithTag("google_search_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("search_add_to_list_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("shopping_search_input").performTextInput("Basmati")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("shopping_search_result_basmati_rice").assertIsDisplayed()

    // Ensure Planner tab is completely removed
    composeTestRule.onNodeWithTag("shopping_tab_planner").assertDoesNotExist()
  }

  @Test
  fun `numeric typography uses tabular lining features and shopping options match spec`() {
    val style = com.skyplus.finance.designsystem.SkyPlusFonts.numericStyle(
      androidx.compose.ui.text.TextStyle.Default,
    )
    assertEquals("tnum, lnum", style.fontFeatureSettings)
    assertEquals(0f, style.letterSpacing.value)

    val units = listOf("kg", "g", "litre", "ml", "piece", "packet", "dozen", "box")
    assertEquals(units, com.skyplus.finance.presentation.shopping.SHOPPING_UNITS)
    assertEquals(18, com.skyplus.finance.presentation.shopping.SHOPPING_CATEGORIES.size)
    assertTrue(com.skyplus.finance.presentation.shopping.SHOPPING_CATEGORIES.containsAll(
      listOf(
        "Groceries", "Vegetables", "Fruits", "Dairy Products", "Meat & Seafood",
        "Bakery", "Beverages", "Household Items", "Personal Care", "Medicines & Health",
        "Electronics", "Clothing", "Transport", "Education", "Rent",
        "Bills & Utilities", "Entertainment", "Other",
      ),
    ))
  }

  @Test
  fun `english only profile renders simplified about skyplus without language switcher or skyplus one ready`() {
    val safe = SafeToSpendCalculator().calculate(
      SafeToSpendInput(BigDecimal("10000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 10, "INR", 2),
    )
    val snapshot = DashboardSnapshot(
      primaryCurrency = null,
      primaryCurrencyCode = "INR",
      themeMode = AppThemeMode.LIGHT,
      linkedGoogleEmail = null,
      settings = AppSettingsEntity(primaryCurrencyCode = "INR", languageCode = "ta", updatedAtEpochMillis = 1L),
      totalAssets = BigDecimal.ZERO,
      totalLiabilities = BigDecimal.ZERO,
      totalNetWorth = BigDecimal.ZERO,
      monthlyIncome = BigDecimal.ZERO,
      monthlyExpenses = BigDecimal.ZERO,
      monthlyNetSavings = BigDecimal.ZERO,
      safeToSpend = safe,
      activeLoanCount = 0,
      totalOutstandingDebt = BigDecimal.ZERO,
      totalMonthlyEmi = BigDecimal.ZERO,
      nextEmiDueEpochDay = null,
      estimatedDebtFreeEpochDay = null,
      emergencyFund = BudgetAndSavingsCalculator().computeEmergencyFund(emptyList(), BigDecimal.ZERO, "INR", 2),
      recentTransactions = emptyList(),
      allTransactions = emptyList(),
      categoriesById = emptyMap(),
      currenciesByCode = emptyMap(),
      allCurrencies = emptyList(),
      budgetStatuses = emptyList(),
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = emptyList(),
      currencyBreakdowns = emptyList(),
      snowballPlan = LoanEmiCalculator().calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.SNOWBALL),
      avalanchePlan = LoanEmiCalculator().calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.AVALANCHE),
    )

    composeTestRule.setContent {
      // Even if legacy setting had "ta", provider enforces English
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.forLanguage("ta")) {
        SkyPlusBrandTheme {
          ProfileScreen(
            snapshot = snapshot,
            lastDriveBackupTimestamp = 0L,
            lastDriveBackupStatus = "Never",
            googleAuthStatus = GoogleAuthConfigStatus.Ready,
            reportPreview = null,
            onSignInWithGoogle = {},
            onSignOutGoogle = {},
            onBackupToGoogleDrive = { _, _ -> },
            onRestoreFromGoogleDrive = { _, _ -> },
            onPreviewReport = {},
            onDismissPreview = {},
            onExportReport = { _, _, _ -> },
            onSetThemeMode = {},
            onSetPrimaryCurrency = {},
            onUpdateAppLock = { _, _, _, _ -> },
            onOpenPrivacySecurity = {},
            onEraseAllData = {},
            onSetLanguageCode = {},
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_screen_title").assertIsDisplayed()
    composeTestRule.onNodeWithText("Profile").assertIsDisplayed()
    composeTestRule.onNodeWithTag("language_en").assertDoesNotExist()
    composeTestRule.onNodeWithTag("language_ta").assertDoesNotExist()
    composeTestRule.onNodeWithText("SKYPLUS ONE Ready", substring = true).assertDoesNotExist()

    composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("profile_about_card"))
    composeTestRule.onNodeWithText("• Track income, expenses and transactions.").assertIsDisplayed()
    composeTestRule.onNodeWithText("• Manage budgets, loans, savings and shopping.").assertIsDisplayed()
    composeTestRule.onNodeWithText("• Monitor financial health and plan your future.").assertIsDisplayed()
  }

  @Test
  fun `auto detect sheet analyzes pasted bank alerts ignores otp and confirms transaction save`() {
    val detector = com.skyplus.finance.domain.intelligence.BankTransactionDetector()
    val cats = listOf(
      CategoryEntity(1L, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
      CategoryEntity(10L, "salary", "Salary", "Salary", CategoryType.INCOME, "payments", 0xFF00BFA6),
    )
    var confirmedCount = 0

    composeTestRule.setContent {
      SkyPlusBrandTheme {
        com.skyplus.finance.presentation.transactions.AutoDetectTransactionSheet(
          categories = cats,
          currenciesByCode = emptyMap(),
          onAnalyze = { raw, src ->
            detector.detectBatch(raw, emptyList(), cats, "INR", mapOf("INR" to 2), src)
          },
          onConfirmSave = { items -> confirmedCount = items.size },
          onDismiss = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("autodetect_message_input").performTextInput(
      "INR 850.00 debited from HDFC A/c XX4521 at Swiggy UTR 55119922.\n\nYour OTP is 123456.",
    )
    composeTestRule.onNodeWithTag("autodetect_analyze_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Detected: 1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Promo/OTP: 1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("autodetect_confirm_save_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, confirmedCount)
  }
}
