package com.skyplus.finance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.skyplus.finance.data.backup.GoogleAuthConfigStatus
import com.skyplus.finance.data.backup.GoogleDriveBackupManager
import com.skyplus.finance.data.database.DatabaseInitializer
import com.skyplus.finance.data.database.DatabaseMigrations
import com.skyplus.finance.data.database.Iso4217CurrencyCatalog
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.export.FinancialExportManager
import com.skyplus.finance.data.export.FinancialReportExporter
import com.skyplus.finance.data.repository.OfflineFinanceRepository
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.BackupCryptoManager
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.calculator.SafeToSpendCalculator
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.report.ReportBuilderUseCase
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.domain.report.ReportRequest
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseAndExportIntegrationTest {

  private lateinit var context: Context
  private lateinit var db: SkyPlusDatabase
  private lateinit var repository: OfflineFinanceRepository
  private lateinit var exportManager: FinancialExportManager
  private lateinit var reportExporter: FinancialReportExporter
  private lateinit var preferencesDataStore: AppPreferencesDataStore
  private lateinit var googleDriveBackupManager: GoogleDriveBackupManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, SkyPlusDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val initializer = DatabaseInitializer(db)
    repository = OfflineFinanceRepository(db, initializer, LoanEmiCalculator())
    preferencesDataStore = AppPreferencesDataStore(context)
    exportManager = FinancialExportManager(
      context = context,
      database = db,
      backupCryptoManager = BackupCryptoManager(),
      preferencesDataStore = preferencesDataStore,
    )
    reportExporter = FinancialReportExporter(
      context = context,
      preferencesDataStore = preferencesDataStore,
    )
    googleDriveBackupManager = GoogleDriveBackupManager(
      context = context,
      database = db,
      exportManager = exportManager,
      preferencesDataStore = preferencesDataStore,
    )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `manifest declares least privilege permissions without broad storage access`() {
    val info = context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.GET_PERMISSIONS,
    )
    val requested = info.requestedPermissions?.toSet() ?: emptySet()
    assertTrue(requested.contains(Manifest.permission.USE_BIOMETRIC))
    assertTrue(requested.contains(Manifest.permission.INTERNET))
    assertTrue(requested.contains(Manifest.permission.ACCESS_NETWORK_STATE))
    assertTrue(requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    assertFalse(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    assertFalse(requested.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    assertFalse(requested.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
    assertFalse(requested.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
  }

  @Test
  fun `transaction insertion deduplication retrieval emi split and savings separation`() = runTest {
    repository.ensureInitialized()
    val today = LocalDate.now().toEpochDay()

    val tx1 = TransactionEntity(
      accountId = 0L,
      categoryId = 10L,
      type = TransactionType.INCOME,
      amount = BigDecimal("5000.00"),
      currencyCode = "INR",
      description = "Monthly Salary",
      transactionDateEpochDay = today,
      deduplicationKey = "dup-key-1",
      createdAtEpochMillis = 100L,
      updatedAtEpochMillis = 100L,
    )
    val id1 = repository.addTransaction(tx1).getOrThrow()
    val idDup = repository.addTransaction(tx1).getOrThrow()
    assertEquals(id1, idDup)
    assertEquals(1, repository.observeTransactions().first().size)

    val loanId = repository.addLoan(
      LoanEntity(
        loanName = "Home Loan",
        lender = "SBI",
        originalPrincipal = BigDecimal("100000.00"),
        outstandingPrincipal = BigDecimal("100000.00"),
        annualInterestRatePercent = BigDecimal("12.0"),
        emiAmount = BigDecimal("8884.88"),
        currencyCode = "INR",
        paymentDueDayOfMonth = 5,
        nextDueDateEpochDay = today,
        remainingTenureMonths = 12,
        startDateEpochDay = today,
        status = LoanStatus.ACTIVE,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
      ),
    ).getOrThrow()

    repository.recordEmiPayment(
      loanId = loanId,
      paymentAmount = BigDecimal("8884.88"),
      extraPrepayment = BigDecimal.ZERO,
      paymentDateEpochDay = today,
      note = "First EMI",
    ).getOrThrow()

    val updatedLoan = db.loanDao().getLoanById(loanId)!!
    assertEquals(BigDecimal("92115.12"), updatedLoan.outstandingPrincipal)
    assertEquals(11, updatedLoan.remainingTenureMonths)

    val goalId = repository.upsertSavingsGoal(
      SavingsGoalEntity(
        goalName = "Emergency Fund",
        goalCategory = SavingsGoalType.EMERGENCY_FUND,
        targetAmount = BigDecimal("50000.00"),
        currentSavedAmount = BigDecimal.ZERO,
        currencyCode = "INR",
        isEmergencyFund = true,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
      ),
    ).getOrThrow()

    repository.contributeToSavingsGoal(goalId, BigDecimal("1500.00"), "INR", today, "EF Deposit").getOrThrow()
    val allTxs = repository.observeTransactions().first()
    assertTrue(allTxs.any { it.type == TransactionType.SAVINGS_ALLOCATION && it.linkedGoalId == goalId })
  }

  @Test
  fun `weekly monthly yearly and custom pdf and docx report exports generate valid files`() = runTest {
    val builder = ReportBuilderUseCase(BudgetAndSavingsCalculator())
    val inr = Iso4217CurrencyCatalog.getAllOfficialCurrencies().first { it.code == "INR" }
    val today = LocalDate.of(2026, 11, 15)
    val txs = listOf(
      TransactionEntity(1, 0, 10, TransactionType.INCOME, BigDecimal("45000.00"), "INR", description = "Monthly Salary", transactionDateEpochDay = today.toEpochDay(), createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
      TransactionEntity(2, 0, 1, TransactionType.EXPENSE, BigDecimal("3250.50"), "INR", description = "Groceries Supermart", transactionDateEpochDay = today.toEpochDay(), createdAtEpochMillis = 2, updatedAtEpochMillis = 2),
    )
    val safe = SafeToSpendCalculator().calculate(
      SafeToSpendInput(BigDecimal("41749.50"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 16, "INR", 2),
    )
    val snapshot = DashboardSnapshot(
      primaryCurrency = inr,
      primaryCurrencyCode = "INR",
      themeMode = AppThemeMode.LIGHT,
      linkedGoogleEmail = null,
      settings = null,
      totalAssets = BigDecimal("41749.50"),
      totalLiabilities = BigDecimal.ZERO,
      totalNetWorth = BigDecimal("41749.50"),
      monthlyIncome = BigDecimal("45000.00"),
      monthlyExpenses = BigDecimal("3250.50"),
      monthlyNetSavings = BigDecimal("41749.50"),
      safeToSpend = safe,
      activeLoanCount = 0,
      totalOutstandingDebt = BigDecimal.ZERO,
      totalMonthlyEmi = BigDecimal.ZERO,
      nextEmiDueEpochDay = null,
      estimatedDebtFreeEpochDay = null,
      emergencyFund = BudgetAndSavingsCalculator().computeEmergencyFund(emptyList(), BigDecimal.ZERO, "INR", 2),
      recentTransactions = txs,
      allTransactions = txs,
      categoriesById = mapOf(
        1L to CategoryEntity(1, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
        10L to CategoryEntity(10, "salary", "Salary", "Salary", CategoryType.INCOME, "payments", 0xFF00BFA6),
      ),
      currenciesByCode = mapOf("INR" to inr),
      allCurrencies = listOf(inr),
      budgetStatuses = emptyList(),
      loans = emptyList(),
      emiPayments = emptyList(),
      savingsGoals = emptyList(),
      currencyBreakdowns = emptyList(),
      snowballPlan = LoanEmiCalculator().calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.SNOWBALL),
      avalanchePlan = LoanEmiCalculator().calculateDebtStrategyPlan(emptyList(), DebtPayoffStrategy.AVALANCHE),
    )

    for (period in ReportPeriodType.entries) {
      val (start, end) = builder.defaultDatesForPeriod(period, today)
      val reportData = builder.build(
        snapshot = snapshot,
        request = ReportRequest(
          periodType = period,
          startEpochDay = start.toEpochDay(),
          endEpochDay = end.toEpochDay(),
          displayCurrencyCode = "INR",
        ),
        today = today,
      )

      val pdfFile = reportExporter.exportPdf(reportData).getOrThrow()
      assertTrue("${period.name} PDF should exist", pdfFile.exists() && pdfFile.length() > 150L)
      val pdfHeader = pdfFile.readBytes().toString(Charsets.US_ASCII)
      assertTrue("${period.name} PDF must start with %PDF-1.4", pdfHeader.startsWith("%PDF-1.4"))
      assertTrue("${period.name} PDF must end with %%EOF", pdfHeader.trimEnd().endsWith("%%EOF"))

      val docxFile = reportExporter.exportDocx(reportData).getOrThrow()
      assertTrue("${period.name} DOCX should exist", docxFile.exists() && docxFile.length() > 500L)
      ZipFile(docxFile).use { zip ->
        assertTrue(zip.getEntry("[Content_Types].xml") != null)
        assertTrue(zip.getEntry("_rels/.rels") != null)
        assertTrue(zip.getEntry("word/styles.xml") != null)
        val docEntry = zip.getEntry("word/document.xml")!!
        val docXml = zip.getInputStream(docEntry).bufferedReader().readText()
        assertTrue(docXml.contains("SKYPLUS FINANCE"))
        assertTrue(docXml.contains("Groceries Supermart"))
        assertTrue(docXml.contains("Monthly Salary"))
      }
    }

    // Empty-date-range edge case exports cleanly without crashing
    val emptyReport = builder.build(
      snapshot = snapshot,
      request = ReportRequest(
        periodType = ReportPeriodType.CUSTOM,
        startEpochDay = LocalDate.of(2020, 1, 1).toEpochDay(),
        endEpochDay = LocalDate.of(2020, 1, 31).toEpochDay(),
        displayCurrencyCode = "INR",
      ),
      today = today,
    )
    assertTrue(reportExporter.exportPdf(emptyReport).getOrThrow().exists())
    assertTrue(reportExporter.exportDocx(emptyReport).getOrThrow().exists())
  }

  @Test
  fun `google oauth blocker reported honestly and sign out preserves local data`() = runTest {
    repository.ensureInitialized()
    val today = LocalDate.now().toEpochDay()
    repository.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("12.500"),
        currencyCode = "KWD",
        description = "Coffee",
        transactionDateEpochDay = today,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
      ),
    ).getOrThrow()

    val clientId = com.example.BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()
    val hasValidClientId = clientId.isNotEmpty() &&
      !clientId.startsWith("YOUR_", ignoreCase = true) &&
      !clientId.contains("PLACEHOLDER", ignoreCase = true) &&
      clientId.endsWith(".apps.googleusercontent.com", ignoreCase = true)
    val status = googleDriveBackupManager.checkGoogleOAuthConfiguration()
    if (hasValidClientId) {
      assertTrue(status is GoogleAuthConfigStatus.Ready)
    } else {
      assertTrue(status is GoogleAuthConfigStatus.Blocker)
    }
    val uploadRes = googleDriveBackupManager.uploadEncryptedBackupToAppDataFolder(
      oauthAccessToken = null,
      recoveryPassphrase = "Passphrase123".toCharArray(),
    )
    assertTrue(uploadRes.isFailure)

    // Linking then signing out must NEVER delete local financial data
    googleDriveBackupManager.linkAccountEmailWithoutOverwritingData("user@example.com")
    assertEquals("user@example.com", db.accountDao().getPrimaryAccountOnce()?.googleAccountEmail)

    googleDriveBackupManager.signOutPreservingLocalData().getOrThrow()
    assertNull(db.accountDao().getPrimaryAccountOnce()?.googleAccountEmail)
    assertEquals(1, repository.observeTransactions().first().size)
  }

  @Test
  fun `csv and encrypted payload round trip preserves decimal precision`() = runTest {
    repository.ensureInitialized()
    val today = LocalDate.now().toEpochDay()
    repository.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("12.500"),
        currencyCode = "KWD",
        description = "Groceries",
        transactionDateEpochDay = today,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
      ),
    ).getOrThrow()

    val csvFile = exportManager.exportTransactionsToCsv().getOrThrow()
    val csvText = csvFile.readText()
    assertTrue(csvText.contains("Groceries"))
    assertTrue(csvText.contains("12.500"))
    assertTrue(csvText.contains("KWD"))

    val passphrase = "ExportPassphrase123".toCharArray()
    val encryptedContent = exportManager.buildEncryptedBackupString(passphrase).getOrThrow()
    assertFalse(encryptedContent.contains("Groceries"))

    repository.eraseAllFinancialData().getOrThrow()
    assertEquals(0, repository.observeTransactions().first().size)

    val restoredCount = exportManager.importEncryptedPayloadFromJson(
      encryptedEnvelopeJson = encryptedContent,
      passphrase = "ExportPassphrase123".toCharArray(),
      overwriteExisting = true,
    ).getOrThrow()
    assertTrue(restoredCount >= 1)
    val restoredTxs = repository.observeTransactions().first()
    assertEquals(1, restoredTxs.size)
    assertEquals("Groceries", restoredTxs.first().description)
    assertEquals(0, restoredTxs.first().amount.compareTo(BigDecimal("12.500")))
  }

  @Test
  fun `database migration 1 to 2 executes cleanly without data loss`() {
    val callback = object : SupportSQLiteOpenHelper.Callback(1) {
      override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS test_records (id INTEGER PRIMARY KEY, note TEXT)")
        db.execSQL("INSERT INTO test_records (id, note) VALUES (1, 'preserved')")
      }

      override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
    val config = SupportSQLiteOpenHelper.Configuration.builder(context)
      .name("migration_test.db")
      .callback(callback)
      .build()
    val helper = FrameworkSQLiteOpenHelperFactory().create(config)
    val rawDb = helper.writableDatabase
    DatabaseMigrations.MIGRATION_1_2.migrate(rawDb)

    rawDb.query("SELECT note FROM test_records WHERE id = 1").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("preserved", cursor.getString(0))
    }
    rawDb.query("SELECT version FROM schema_audit_log WHERE version = 2").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(2, cursor.getInt(0))
    }
    helper.close()
  }

  @Test
  fun `stabilization preserves room v2 migration guard and writes ascii pdf xref under arabic locale`() = runTest {
    // SkyPlusDatabase has MIGRATION_1_2, MIGRATION_2_3, and MIGRATION_3_4 registered
    assertEquals(3, DatabaseMigrations.allMigrations().size)

    // Condition 4: PDF xref offsets stay ASCII digits even when device Locale defaults to Arabic-Indic numerals
    val previousLocale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-EG"))
      repository.ensureInitialized()
      val today = LocalDate.now().toEpochDay()
      repository.addTransaction(
        TransactionEntity(
          accountId = 0L,
          categoryId = 1L,
          type = TransactionType.EXPENSE,
          amount = BigDecimal("250.00"),
          currencyCode = "INR",
          description = "Locale Xref Check",
          transactionDateEpochDay = today,
          createdAtEpochMillis = 10L,
          updatedAtEpochMillis = 10L,
        ),
      ).getOrThrow()

      val pdfFile = exportManager.exportMonthlyPdfReport().getOrThrow()
      val pdfText = pdfFile.readBytes().toString(Charsets.US_ASCII)
      assertTrue(pdfText.contains("0000000000 65535 f"))
      assertTrue(Regex("""\d{10} 00000 n""").containsMatchIn(pdfText))
    } finally {
      Locale.setDefault(previousLocale)
    }
  }
}
