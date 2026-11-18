package com.skyplus.finance.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.room.withTransaction
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.AccountEntity
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.InvestmentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.BackupCryptoManager
import com.skyplus.finance.data.security.BackupEnvelopeMetadata
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.repository.CloudBackupGateway
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupAccountDto(
  val id: Long,
  val accountName: String,
  val accountType: String = "LOCAL_WALLET",
  val primaryCurrencyCode: String,
  val openingBalance: String = "0",
  val isGuestLocal: Boolean = true,
  val createdAtEpochMillis: Long,
)

@Serializable
data class BackupCategoryDto(
  val id: Long,
  val nameKey: String,
  val displayNameEn: String,
  val displayNameTa: String = "",
  val type: CategoryType,
  val iconKey: String,
  val colorHex: Long,
  val isSystemDefault: Boolean = true,
  val sortOrder: Int = 0,
)

@Serializable
data class BackupCurrencyDto(
  val code: String,
  val isActiveInWallet: Boolean = false,
  val cachedRateToBase: String? = null,
  val rateSource: String? = null,
  val rateUpdatedAtEpochMillis: Long? = null,
)

@Serializable
data class BackupAppSettingsDto(
  val primaryCurrencyCode: String,
  val languageCode: String = "en",
  val themeMode: String = "SYSTEM",
  val reservedAmount: String = "0",
  val monthlySavingsAllocation: String = "0",
  val confirmedRemainingIncome: String = "0",
  val monthlyEssentialExpenses: String = "0",
  val dailySpendingSummaryEnabled: Boolean = false,
  val spendingSummaryHour: Int = 20,
  val spendingSummaryMinute: Int = 30,
  val budgetAlertsEnabled: Boolean = true,
  val billEmiRemindersEnabled: Boolean = true,
  val shoppingBudgetRemindersEnabled: Boolean = true,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupInvestmentDto(
  val id: Long,
  val investmentName: String,
  val investmentType: String,
  val amountInvested: String,
  val investmentDateEpochDay: Long,
  val currentValue: String? = null,
  val notes: String = "",
  val currencyCode: String,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupSyncMapDto(
  val entityType: String,
  val localId: String,
  val recordId: String,
  val syncVersion: Long,
)

@Serializable
data class BackupTransactionDto(
  val id: Long,
  val accountId: Long,
  val categoryId: Long,
  val type: TransactionType,
  val amount: String,
  val currencyCode: String,
  val exchangeRateToPrimary: String? = null,
  val exchangeRateSource: String? = null,
  val exchangeRateTimestamp: Long? = null,
  val convertedPrimaryAmount: String? = null,
  val primaryCurrencyAtEntry: String? = null,
  val description: String,
  val transactionDateEpochDay: Long,
  val isRecurring: Boolean,
  val recurrenceIntervalDays: Int? = null,
  val isUpcomingBill: Boolean,
  val isPaid: Boolean,
  val linkedGoalId: Long? = null,
  val linkedLoanId: Long? = null,
  val deduplicationKey: String? = null,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupBudgetDto(
  val id: Long,
  val categoryId: Long,
  val yearMonth: String,
  val limitAmount: String,
  val currencyCode: String,
  val rolloverEnabled: Boolean,
  val rolloverAmount: String,
  val warningThresholdPercent: Int,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupLoanDto(
  val id: Long,
  val loanName: String,
  val lender: String,
  val originalPrincipal: String,
  val outstandingPrincipal: String,
  val annualInterestRatePercent: String,
  val emiAmount: String,
  val currencyCode: String,
  val paymentDueDayOfMonth: Int,
  val nextDueDateEpochDay: Long,
  val remainingTenureMonths: Int,
  val startDateEpochDay: Long,
  val status: LoanStatus,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupEmiPaymentDto(
  val id: Long,
  val loanId: Long,
  val paymentDateEpochDay: Long,
  val totalPaidAmount: String,
  val principalComponent: String,
  val interestComponent: String,
  val extraPrepaymentAmount: String,
  val remainingBalanceAfter: String,
  val currencyCode: String,
  val note: String,
  val createdAtEpochMillis: Long,
)

@Serializable
data class BackupSavingsGoalDto(
  val id: Long,
  val goalName: String,
  val goalCategory: SavingsGoalType,
  val targetAmount: String,
  val currentSavedAmount: String,
  val currencyCode: String,
  val targetDateEpochDay: Long? = null,
  val isEmergencyFund: Boolean,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupShoppingListDto(
  val id: Long,
  val name: String,
  val budgetAmount: String,
  val currencyCode: String,
  val notes: String = "",
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupShoppingItemDto(
  val id: Long,
  val listId: Long,
  val name: String,
  val categoryName: String,
  val quantity: String,
  val unit: String,
  val estimatedUnitPrice: String,
  val actualUnitPrice: String? = null,
  val isPurchased: Boolean = false,
  val notes: String = "",
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupShoppingShopPriceDto(
  val id: Long,
  val itemNameNormalized: String,
  val productDisplayName: String,
  val shopName: String,
  val shopLocation: String,
  val unit: String,
  val price: String,
  val currencyCode: String,
  val priceSource: String = "Manual Entry",
  val isLiveFeedAvailable: Boolean = false,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class BackupSnapshotPayload(
  val schemaVersion: Int = 3,
  val exportedAtEpochMillis: Long,
  val primaryCurrencyCode: String,
  val languageCode: String,
  val accounts: List<BackupAccountDto> = emptyList(),
  val categories: List<BackupCategoryDto> = emptyList(),
  val currencies: List<BackupCurrencyDto> = emptyList(),
  val appSettings: BackupAppSettingsDto? = null,
  val transactions: List<BackupTransactionDto>,
  val budgets: List<BackupBudgetDto>,
  val loans: List<BackupLoanDto>,
  val emiPayments: List<BackupEmiPaymentDto>,
  val savingsGoals: List<BackupSavingsGoalDto>,
  val investments: List<BackupInvestmentDto> = emptyList(),
  val shoppingLists: List<BackupShoppingListDto> = emptyList(),
  val shoppingItems: List<BackupShoppingItemDto> = emptyList(),
  val shoppingShopPrices: List<BackupShoppingShopPriceDto> = emptyList(),
  val syncEntityMap: List<BackupSyncMapDto> = emptyList(),
)

@Singleton
class FinancialExportManager @Inject constructor(
  @ApplicationContext private val context: Context,
  private val database: SkyPlusDatabase,
  private val backupCryptoManager: BackupCryptoManager,
  private val preferencesDataStore: AppPreferencesDataStore,
) : CloudBackupGateway {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }
  private fun backupDir(): File = File(context.filesDir, "backups").apply { mkdirs() }

  suspend fun verifyPortfolioIntegrity(): Result<Unit> = runCatching {
    val accounts = database.accountDao().getAllAccountsOnce()
    val categories = database.categoryDao().getAllCategoriesOnce()
    val categoryIds = categories.map { it.id }.toSet()
    val accountIds = accounts.map { it.id }.toSet()
    val loans = database.loanDao().getAllLoansOnce()
    val loanIds = loans.map { it.id }.toSet()
    val emiPayments = database.loanDao().getAllEmiPaymentsOnce()
    val lists = database.shoppingDao().getAllShoppingListsOnce()
    val listIds = lists.map { it.id }.toSet()
    val items = database.shoppingDao().getAllShoppingItemsOnce()
    val transactions = database.transactionDao().getAllTransactionsOnce()

    check(emiPayments.all { it.loanId in loanIds }) {
      "Integrity check failed: orphan EMI payment references a missing loan."
    }
    check(items.all { it.listId in listIds }) {
      "Integrity check failed: orphan shopping item references a missing shopping list."
    }
    check(transactions.all { it.accountId in accountIds && it.categoryId in categoryIds }) {
      "Integrity check failed: transaction references missing account or category."
    }
  }

  fun inspectEncryptedEnvelope(encryptedEnvelopeJson: String): Result<BackupEnvelopeMetadata> =
    backupCryptoManager.inspectBackupHeader(encryptedEnvelopeJson)

  suspend fun exportTransactionsToCsv(): Result<File> = runCatching {
    val transactions = database.transactionDao().getAllTransactionsOnce()
    val categories = database.categoryDao().getAllCategoriesOnce().associateBy { it.id }
    val currencies = database.currencyDao().getAllCurrenciesOnce().associateBy { it.code }

    val file = File(
      exportDir(),
      "skyplus_transactions_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.csv",
    )

    val sb = StringBuilder()
    sb.appendLine("ID,Date,Type,Category,Amount,Currency,Paid,UpcomingBill,Recurring,Description,ConvertedPrimaryAmount,PrimaryCurrency,ExchangeRateSource")
    for (tx in transactions) {
      val dateStr = LocalDate.ofEpochDay(tx.transactionDateEpochDay).toString()
      val catName = categories[tx.categoryId]?.displayNameEn ?: "Other"
      val precision = currencies[tx.currencyCode]?.decimalPrecision ?: 2
      val scaledAmount = DecimalUtils.scaleToCurrency(tx.amount, precision).toPlainString()
      val convertedStr = tx.convertedPrimaryAmount?.toPlainString() ?: ""
      val primaryCur = tx.primaryCurrencyAtEntry ?: ""
      val rateSrc = tx.exchangeRateSource ?: ""
      sb.appendLine(
        listOf(
          tx.id.toString(),
          escapeCsv(dateStr),
          tx.type.name,
          escapeCsv(catName),
          scaledAmount,
          tx.currencyCode,
          tx.isPaid.toString(),
          tx.isUpcomingBill.toString(),
          tx.isRecurring.toString(),
          escapeCsv(tx.description),
          convertedStr,
          primaryCur,
          escapeCsv(rateSrc),
        ).joinToString(","),
      )
    }
    file.writeText(sb.toString())
    preferencesDataStore.recordLocalExportTimestamp(System.currentTimeMillis())
    file
  }

  suspend fun exportReportCsv(report: com.skyplus.finance.domain.report.FinancialReportData): Result<File> = runCatching {
    val fileSlug = report.periodLabel.lowercase().replace(" ", "_")
    val file = File(
      exportDir(),
      "skyplus_${fileSlug}_report_${report.generatedDateText}.csv",
    )
    val sb = StringBuilder()
    sb.appendLine("Section,MetricOrDate,CategoryOrLoan,DescriptionOrPeriod,Amount,ConvertedOrDetail")
    sb.appendLine("Summary,Period,,${escapeCsv(report.dateRangeText)},${escapeCsv(report.displayCurrencyCode)},${escapeCsv(report.periodLabel)}")
    sb.appendLine("Summary,Income,,,${escapeCsv(report.totalIncomeFormatted)},")
    sb.appendLine("Summary,Expenses,,,${escapeCsv(report.totalExpensesFormatted)},")
    sb.appendLine("Summary,Net Savings,,,${escapeCsv(report.netSavingsFormatted)},")
    sb.appendLine("Summary,Net Worth Start,,,${escapeCsv(report.netWorthStartFormatted)},")
    sb.appendLine("Summary,Net Worth End,,,${escapeCsv(report.netWorthEndFormatted)},")
    sb.appendLine("Summary,Net Worth Change,,,${escapeCsv(report.netWorthChangeFormatted)},")
    sb.appendLine("Health,Score,,,${escapeCsv(report.healthScore?.toString() ?: "N/A")},${escapeCsv(report.healthBandLabel)}")
    sb.appendLine("Loans,Period EMI Paid,,,${escapeCsv(report.totalEmiPaidInPeriodFormatted)},")
    report.categoryExpenses.forEach { c ->
      sb.appendLine("CategoryExpense,${escapeCsv(c.name)},,,${escapeCsv(c.formattedAmount)},${c.sharePercent}%")
    }
    report.emiPaymentRows.forEach { e ->
      sb.appendLine("EmiPayment,${escapeCsv(e.date)},${escapeCsv(e.loanName)},Principal:${escapeCsv(e.principalFormatted)} / Int:${escapeCsv(e.interestFormatted)},${escapeCsv(e.totalPaidFormatted)},")
    }
    report.transactionRows.forEach { tx ->
      sb.appendLine("Transaction,${escapeCsv(tx.date)},${escapeCsv(tx.categoryName)},${escapeCsv(tx.description)},${escapeCsv(tx.originalFormatted)},${escapeCsv(tx.convertedDisplayFormatted ?: "")}")
    }
    file.writeText(sb.toString())
    preferencesDataStore.recordLocalExportTimestamp(System.currentTimeMillis())
    file
  }

  suspend fun exportMonthlyPdfReport(yearMonth: YearMonth = YearMonth.now()): Result<File> = runCatching {
    val transactions = database.transactionDao().getAllTransactionsOnce()
    val categories = database.categoryDao().getAllCategoriesOnce().associateBy { it.id }
    val settings = database.appSettingsDao().getSettingsOnce()
    val primaryCurrency = settings?.primaryCurrencyCode ?: "INR"
    val currencies = database.currencyDao().getAllCurrenciesOnce().associateBy { it.code }
    val loans = database.loanDao().getAllLoansOnce()
    val goals = database.savingsGoalDao().getAllGoalsOnce()

    val startDay = yearMonth.atDay(1).toEpochDay()
    val endDay = yearMonth.atEndOfMonth().toEpochDay()
    val monthTxs = transactions.filter { it.transactionDateEpochDay in startDay..endDay && it.isPaid }

    val outFile = File(exportDir(), "skyplus_report_${yearMonth}.pdf")
    val pdfLines = buildList {
      add("SkyPlus Finance - Monthly Financial Report")
      add("Period: $yearMonth | Primary Currency: $primaryCurrency | Offline Local Ledger")
      add("")
      add("1. Cashflow Summary by Currency")
      val grouped = monthTxs.groupBy { it.currencyCode }
      if (grouped.isEmpty()) {
        add("  No paid transactions recorded for $yearMonth.")
      } else {
        for ((code, list) in grouped) {
          val cur = currencies[code]
          val inc = list.filter { it.type == TransactionType.INCOME }.fold(BigDecimal.ZERO) { a, t -> a.add(t.amount) }
          val exp = list.filter { it.type == TransactionType.EXPENSE }.fold(BigDecimal.ZERO) { a, t -> a.add(t.amount) }
          val sav = list.filter { it.type == TransactionType.SAVINGS_ALLOCATION }.fold(BigDecimal.ZERO) { a, t -> a.add(t.amount) }
          add("  $code | Income: ${CurrencyFormatter.formatWithEntity(inc, cur, code)} | Expense: ${CurrencyFormatter.formatWithEntity(exp, cur, code)} | Savings: ${CurrencyFormatter.formatWithEntity(sav, cur, code)}")
        }
      }
      add("")
      add("2. Active Loans & Debt Snapshot (${loans.count { it.status == LoanStatus.ACTIVE }})")
      if (loans.isEmpty()) {
        add("  No loans recorded.")
      } else {
        for (loan in loans.take(6)) {
          val cur = currencies[loan.currencyCode]
          add("  ${loan.loanName} (${loan.lender}) - Balance: ${CurrencyFormatter.formatWithEntity(loan.outstandingPrincipal, cur, loan.currencyCode)} | EMI: ${CurrencyFormatter.formatWithEntity(loan.emiAmount, cur, loan.currencyCode)}")
        }
      }
      add("")
      add("3. Savings Goals (${goals.size})")
      if (goals.isEmpty()) {
        add("  No savings goals configured.")
      } else {
        for (goal in goals.take(6)) {
          val cur = currencies[goal.currencyCode]
          val pct = DecimalUtils.percentage(goal.currentSavedAmount, goal.targetAmount)
          add("  ${goal.goalName} - Saved ${CurrencyFormatter.formatWithEntity(goal.currentSavedAmount, cur, goal.currencyCode)} / ${CurrencyFormatter.formatWithEntity(goal.targetAmount, cur, goal.currencyCode)} ($pct%)")
        }
      }
      add("")
      add("4. Recent Transactions in $yearMonth")
      for (tx in monthTxs.take(18)) {
        val cur = currencies[tx.currencyCode]
        val cat = categories[tx.categoryId]?.displayNameEn ?: "Other"
        val date = LocalDate.ofEpochDay(tx.transactionDateEpochDay)
        val desc = tx.description.ifBlank { cat }.take(34)
        add("  $date [$cat] $desc - ${CurrencyFormatter.formatWithEntity(tx.amount, cur, tx.currencyCode)} (${tx.type.name})")
      }
      add("")
      add("Generated locally on device by SkyPlus Finance (Offline-First, Zero Cloud Telemetry).")
    }

    val nativePdfSucceeded = runCatching {
      val document = PdfDocument()
      val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
      val page = document.startPage(pageInfo)
      val canvas: Canvas = page.canvas

      val titlePaint = Paint().apply {
        color = Color.rgb(15, 23, 42)
        textSize = 18f
        isFakeBoldText = true
      }
      val headingPaint = Paint().apply {
        color = Color.rgb(16, 185, 129)
        textSize = 13f
        isFakeBoldText = true
      }
      val bodyPaint = Paint().apply {
        color = Color.rgb(30, 41, 59)
        textSize = 10.5f
      }

      var y = 48f
      pdfLines.forEachIndexed { idx, line ->
        if (y <= 810f) {
          val paint = when {
            idx == 0 -> titlePaint
            line.startsWith("1.") || line.startsWith("2.") || line.startsWith("3.") || line.startsWith("4.") -> headingPaint
            else -> bodyPaint
          }
          canvas.drawText(line, 40f, y, paint)
          y += if (idx == 0) 24f else 15f
        }
      }
      document.finishPage(page)
      FileOutputStream(outFile).use { fos -> document.writeTo(fos) }
      document.close()
    }.isSuccess

    if (!nativePdfSucceeded) {
      writeMinimalPdf14(outFile, pdfLines)
    }

    preferencesDataStore.recordLocalExportTimestamp(System.currentTimeMillis())
    outFile
  }

  private fun writeMinimalPdf14(outFile: File, lines: List<String>) {
    val streamBuilder = StringBuilder()
    streamBuilder.append("BT\n/F1 11 Tf\n40 790 Td\n15 TL\n")
    lines.take(48).forEach { raw ->
      val safeAscii = raw
        .map { ch -> if (ch.code in 32..126) ch else '?' }
        .joinToString("")
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")
      streamBuilder.append("($safeAscii) Tj T*\n")
    }
    streamBuilder.append("ET\n")
    val contentStream = streamBuilder.toString()

    val objects = listOf(
      "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
      "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
      "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
      "4 0 obj\n<< /Length ${contentStream.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${contentStream}endstream\nendobj\n",
      "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
    )

    val pdf = StringBuilder("%PDF-1.4\n")
    val offsets = mutableListOf<Int>()
    for (obj in objects) {
      offsets.add(pdf.toString().toByteArray(Charsets.US_ASCII).size)
      pdf.append(obj)
    }
    val xrefOffset = pdf.toString().toByteArray(Charsets.US_ASCII).size
    pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    for (offset in offsets) {
      pdf.append(String.format(Locale.US, "%010d 00000 n \n", offset))
    }
    pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
    outFile.writeBytes(pdf.toString().toByteArray(Charsets.US_ASCII))
  }

  suspend fun exportEncryptedBackupFile(passphrase: CharArray): Result<File> = runCatching {
    val envelope = buildEncryptedBackupString(passphrase).getOrThrow()
    val outFile = File(
      backupDir(),
      "skyplus_backup_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.skyplusenc",
    )
    outFile.writeText(envelope)
    preferencesDataStore.recordLocalExportTimestamp(System.currentTimeMillis())
    outFile
  }

  suspend fun createPreRestoreSafetyBackup(passphrase: CharArray): Result<File> = runCatching {
    val envelope = buildEncryptedBackupString(passphrase).getOrThrow()
    val safetyDir = File(context.filesDir, "pre_restore_safety").apply { mkdirs() }
    val safetyFile = File(
      safetyDir,
      "pre_restore_safety_${System.currentTimeMillis()}.skyplusenc",
    )
    safetyFile.writeText(envelope)
    safetyFile
  }

  suspend fun buildEncryptedBackupString(passphrase: CharArray): Result<String> = runCatching {
    verifyPortfolioIntegrity().getOrThrow()

    val accounts = database.accountDao().getAllAccountsOnce()
    val categories = database.categoryDao().getAllCategoriesOnce()
    val currencies = database.currencyDao().getAllCurrenciesOnce()
      .filter { it.isActiveInWallet || it.cachedRateToBase != null }
    val transactions = database.transactionDao().getAllTransactionsOnce()
    val budgets = database.budgetDao().getAllBudgetsOnce()
    val loans = database.loanDao().getAllLoansOnce()
    val emiPayments = database.loanDao().getAllEmiPaymentsOnce()
    val goals = database.savingsGoalDao().getAllGoalsOnce()
    val investments = database.investmentDao().getAllInvestmentsOnce()
    val shoppingLists = database.shoppingDao().getAllShoppingListsOnce()
    val shoppingItems = database.shoppingDao().getAllShoppingItemsOnce()
    val shoppingShopPrices = database.shoppingDao().getAllShopPricesOnce()
    val settings = database.appSettingsDao().getSettingsOnce()
    val syncMaps = database.syncDao().getAllMapsOnce()
    val deviceId = preferencesDataStore.getOrCreateDeviceId()

    val payload = BackupSnapshotPayload(
      schemaVersion = 3,
      exportedAtEpochMillis = System.currentTimeMillis(),
      primaryCurrencyCode = settings?.primaryCurrencyCode ?: "INR",
      languageCode = "en",
      accounts = accounts.map {
        BackupAccountDto(
          id = it.id,
          accountName = it.accountName,
          accountType = it.accountType,
          primaryCurrencyCode = it.primaryCurrencyCode,
          openingBalance = DecimalUtils.toCanonicalString(it.openingBalance),
          isGuestLocal = it.isGuestLocal,
          createdAtEpochMillis = it.createdAtEpochMillis,
        )
      },
      categories = categories.map {
        BackupCategoryDto(
          id = it.id,
          nameKey = it.nameKey,
          displayNameEn = it.displayNameEn,
          displayNameTa = it.displayNameTa,
          type = it.type,
          iconKey = it.iconKey,
          colorHex = it.colorHex,
          isSystemDefault = it.isSystemDefault,
          sortOrder = it.sortOrder,
        )
      },
      currencies = currencies.map {
        BackupCurrencyDto(
          code = it.code,
          isActiveInWallet = it.isActiveInWallet,
          cachedRateToBase = it.cachedRateToBase?.let { r -> DecimalUtils.toCanonicalString(r) },
          rateSource = it.rateSource,
          rateUpdatedAtEpochMillis = it.rateUpdatedAtEpochMillis,
        )
      },
      appSettings = settings?.let {
        BackupAppSettingsDto(
          primaryCurrencyCode = it.primaryCurrencyCode,
          languageCode = "en",
          themeMode = it.themeMode,
          reservedAmount = DecimalUtils.toCanonicalString(it.reservedAmount),
          monthlySavingsAllocation = DecimalUtils.toCanonicalString(it.monthlySavingsAllocation),
          confirmedRemainingIncome = DecimalUtils.toCanonicalString(it.confirmedRemainingIncome),
          monthlyEssentialExpenses = DecimalUtils.toCanonicalString(it.monthlyEssentialExpenses),
          dailySpendingSummaryEnabled = it.dailySpendingSummaryEnabled,
          spendingSummaryHour = it.spendingSummaryHour,
          spendingSummaryMinute = it.spendingSummaryMinute,
          budgetAlertsEnabled = it.budgetAlertsEnabled,
          billEmiRemindersEnabled = it.billEmiRemindersEnabled,
          shoppingBudgetRemindersEnabled = it.shoppingBudgetRemindersEnabled,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      transactions = transactions.map {
        BackupTransactionDto(
          id = it.id,
          accountId = it.accountId,
          categoryId = it.categoryId,
          type = it.type,
          amount = DecimalUtils.toCanonicalString(it.amount),
          currencyCode = it.currencyCode,
          exchangeRateToPrimary = it.exchangeRateToPrimary?.let { r -> DecimalUtils.toCanonicalString(r) },
          exchangeRateSource = it.exchangeRateSource,
          exchangeRateTimestamp = it.exchangeRateTimestamp,
          convertedPrimaryAmount = it.convertedPrimaryAmount?.let { c -> DecimalUtils.toCanonicalString(c) },
          primaryCurrencyAtEntry = it.primaryCurrencyAtEntry,
          description = it.description,
          transactionDateEpochDay = it.transactionDateEpochDay,
          isRecurring = it.isRecurring,
          recurrenceIntervalDays = it.recurrenceIntervalDays,
          isUpcomingBill = it.isUpcomingBill,
          isPaid = it.isPaid,
          linkedGoalId = it.linkedGoalId,
          linkedLoanId = it.linkedLoanId,
          deduplicationKey = it.deduplicationKey,
          createdAtEpochMillis = it.createdAtEpochMillis,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      budgets = budgets.map {
        BackupBudgetDto(
          id = it.id,
          categoryId = it.categoryId,
          yearMonth = it.yearMonth,
          limitAmount = DecimalUtils.toCanonicalString(it.limitAmount),
          currencyCode = it.currencyCode,
          rolloverEnabled = it.rolloverEnabled,
          rolloverAmount = DecimalUtils.toCanonicalString(it.rolloverAmount),
          warningThresholdPercent = it.warningThresholdPercent,
          createdAtEpochMillis = it.createdAtEpochMillis,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      loans = loans.map {
        BackupLoanDto(
          id = it.id,
          loanName = it.loanName,
          lender = it.lender,
          originalPrincipal = DecimalUtils.toCanonicalString(it.originalPrincipal),
          outstandingPrincipal = DecimalUtils.toCanonicalString(it.outstandingPrincipal),
          annualInterestRatePercent = DecimalUtils.toCanonicalString(it.annualInterestRatePercent),
          emiAmount = DecimalUtils.toCanonicalString(it.emiAmount),
          currencyCode = it.currencyCode,
          paymentDueDayOfMonth = it.paymentDueDayOfMonth,
          nextDueDateEpochDay = it.nextDueDateEpochDay,
          remainingTenureMonths = it.remainingTenureMonths,
          startDateEpochDay = it.startDateEpochDay,
          status = it.status,
          createdAtEpochMillis = it.createdAtEpochMillis,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      emiPayments = emiPayments.map {
        BackupEmiPaymentDto(
          id = it.id,
          loanId = it.loanId,
          paymentDateEpochDay = it.paymentDateEpochDay,
          totalPaidAmount = DecimalUtils.toCanonicalString(it.totalPaidAmount),
          principalComponent = DecimalUtils.toCanonicalString(it.principalComponent),
          interestComponent = DecimalUtils.toCanonicalString(it.interestComponent),
          extraPrepaymentAmount = DecimalUtils.toCanonicalString(it.extraPrepaymentAmount),
          remainingBalanceAfter = DecimalUtils.toCanonicalString(it.remainingBalanceAfter),
          currencyCode = it.currencyCode,
          note = it.note,
          createdAtEpochMillis = it.createdAtEpochMillis,
        )
      },
      savingsGoals = goals.map {
        BackupSavingsGoalDto(
          id = it.id,
          goalName = it.goalName,
          goalCategory = it.goalCategory,
          targetAmount = DecimalUtils.toCanonicalString(it.targetAmount),
          currentSavedAmount = DecimalUtils.toCanonicalString(it.currentSavedAmount),
          currencyCode = it.currencyCode,
          targetDateEpochDay = it.targetDateEpochDay,
          isEmergencyFund = it.isEmergencyFund,
          createdAtEpochMillis = it.createdAtEpochMillis,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      investments = investments.map {
        BackupInvestmentDto(
          id = it.id,
          investmentName = it.investmentName,
          investmentType = it.investmentType,
          amountInvested = DecimalUtils.toCanonicalString(it.amountInvested),
          investmentDateEpochDay = it.investmentDateEpochDay,
          currentValue = it.currentValue?.let { v -> DecimalUtils.toCanonicalString(v) },
          notes = it.notes,
          currencyCode = it.currencyCode,
          createdAtEpochMillis = it.createdAtEpochMillis,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      shoppingLists = shoppingLists.map {
        BackupShoppingListDto(
          id = it.id,
          name = it.name,
          budgetAmount = DecimalUtils.toCanonicalString(it.budgetAmount),
          currencyCode = it.currencyCode,
          notes = it.notes,
          createdAtEpochMillis = it.createdAtEpochMillis,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      shoppingItems = shoppingItems.map {
        BackupShoppingItemDto(
          id = it.id,
          listId = it.listId,
          name = it.name,
          categoryName = it.categoryName,
          quantity = DecimalUtils.toCanonicalString(it.quantity),
          unit = it.unit,
          estimatedUnitPrice = DecimalUtils.toCanonicalString(it.estimatedUnitPrice),
          actualUnitPrice = it.actualUnitPrice?.let { a -> DecimalUtils.toCanonicalString(a) },
          isPurchased = it.isPurchased,
          notes = it.notes,
          createdAtEpochMillis = it.createdAtEpochMillis,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      shoppingShopPrices = shoppingShopPrices.map {
        BackupShoppingShopPriceDto(
          id = it.id,
          itemNameNormalized = it.itemNameNormalized,
          productDisplayName = it.productDisplayName,
          shopName = it.shopName,
          shopLocation = it.shopLocation,
          unit = it.unit,
          price = DecimalUtils.toCanonicalString(it.price),
          currencyCode = it.currencyCode,
          priceSource = it.priceSource,
          isLiveFeedAvailable = it.isLiveFeedAvailable,
          updatedAtEpochMillis = it.updatedAtEpochMillis,
        )
      },
      syncEntityMap = syncMaps.map {
        BackupSyncMapDto(
          entityType = it.entityType,
          localId = it.localId,
          recordId = it.recordId,
          syncVersion = it.syncVersion,
        )
      },
    )

    val plaintextJson = json.encodeToString(BackupSnapshotPayload.serializer(), payload)
    val count = transactions.size + budgets.size + loans.size + emiPayments.size + goals.size +
      investments.size + shoppingLists.size + shoppingItems.size + shoppingShopPrices.size
    backupCryptoManager.encryptBackupV2(
      plaintextJson = plaintextJson,
      passphrase = passphrase,
      recordCount = count,
      deviceId = deviceId,
      schemaVersion = 3,
      dbVersion = 4,
    )
  }

  override suspend fun isCloudAccountLinked(): Boolean {
    return database.accountDao().getPrimaryAccountOnce()?.googleAccountEmail != null
  }

  override suspend fun getLinkedAccountEmail(): String? {
    return database.accountDao().getPrimaryAccountOnce()?.googleAccountEmail
  }

  override suspend fun exportEncryptedPayloadToLocalFile(passphrase: CharArray): Result<String> {
    return exportEncryptedBackupFile(passphrase).map { it.absolutePath }
  }

  override suspend fun importEncryptedPayloadFromJson(
    encryptedEnvelopeJson: String,
    passphrase: CharArray,
    overwriteExisting: Boolean,
  ): Result<Int> = runCatching {
    // Step 1: Decrypt and verify GCM tag + AAD + SHA-256 BEFORE mutating database.
    val plaintextJson = backupCryptoManager.decryptBackup(encryptedEnvelopeJson, passphrase)
    val snapshot = json.decodeFromString(BackupSnapshotPayload.serializer(), plaintextJson)

    // Step 2: Create local encrypted pre-restore safety snapshot before Overwrite wipe.
    if (overwriteExisting) {
      createPreRestoreSafetyBackup(passphrase)
    }

    val existingLinkedEmail = database.accountDao().getPrimaryAccountOnce()?.googleAccountEmail

    if (overwriteExisting) {
      restoreAtomicOverwrite(snapshot, existingLinkedEmail)
    } else {
      restoreMergeRemapping(snapshot)
    }
  }

  private suspend fun restoreAtomicOverwrite(
    snapshot: BackupSnapshotPayload,
    preserveLinkedEmail: String?,
  ): Int {
    val categoryIdRemap = mutableMapOf<Long, Long>()
    val accountIdRemap = mutableMapOf<Long, Long>()
    val loanIdRemap = mutableMapOf<Long, Long>()
    val goalIdRemap = mutableMapOf<Long, Long>()
    val listIdRemap = mutableMapOf<Long, Long>()

    database.withTransaction {
      database.transactionDao().deleteAll()
      database.budgetDao().deleteAll()
      database.loanDao().deleteAllEmiPayments()
      database.loanDao().deleteAllLoans()
      database.savingsGoalDao().deleteAll()
      database.investmentDao().deleteAllInvestments()
      database.shoppingDao().deleteAllItems()
      database.shoppingDao().deleteAllLists()
      database.shoppingDao().deleteAllShopPrices()
      database.syncDao().deleteAllOutbox()
      database.syncDao().deleteAllConflicts()

      // 1. Categories: align existing defaults by nameKey+type, insert missing custom categories
      val existingCategories = database.categoryDao().getAllCategoriesOnce()
      snapshot.categories.forEach { dto ->
        val match = existingCategories.firstOrNull {
          it.nameKey == dto.nameKey && it.type == dto.type
        }
        val resolvedId = if (match != null) {
          match.id
        } else {
          database.categoryDao().upsert(
            CategoryEntity(
              id = 0L,
              nameKey = dto.nameKey,
              displayNameEn = dto.displayNameEn,
              displayNameTa = dto.displayNameTa,
              type = dto.type,
              iconKey = dto.iconKey,
              colorHex = dto.colorHex,
              isSystemDefault = dto.isSystemDefault,
              sortOrder = dto.sortOrder,
            ),
          )
        }
        categoryIdRemap[dto.id] = resolvedId
      }
      val fallbackCategoryId = database.categoryDao().getAllCategoriesOnce().firstOrNull()?.id ?: 1L

      // 2. Currencies
      snapshot.currencies.forEach { dto ->
        val cur = database.currencyDao().getCurrencyByCode(dto.code)
        if (cur != null) {
          database.currencyDao().upsert(
            cur.copy(
              isActiveInWallet = dto.isActiveInWallet,
              cachedRateToBase = dto.cachedRateToBase?.let { DecimalUtils.fromStringOrZero(it) },
              rateSource = dto.rateSource,
              rateUpdatedAtEpochMillis = dto.rateUpdatedAtEpochMillis,
            ),
          )
        }
      }

      // 3. Settings
      val currentSettings = database.appSettingsDao().getSettingsOnce()
      val restoredSettings = snapshot.appSettings?.let { s ->
        AppSettingsEntity(
          id = 1,
          primaryCurrencyCode = s.primaryCurrencyCode,
          languageCode = "en",
          themeMode = s.themeMode,
          reservedAmount = DecimalUtils.fromStringOrZero(s.reservedAmount),
          monthlySavingsAllocation = DecimalUtils.fromStringOrZero(s.monthlySavingsAllocation),
          confirmedRemainingIncome = DecimalUtils.fromStringOrZero(s.confirmedRemainingIncome),
          monthlyEssentialExpenses = DecimalUtils.fromStringOrZero(s.monthlyEssentialExpenses),
          appLockEnabled = currentSettings?.appLockEnabled ?: false,
          lockOnLaunch = currentSettings?.lockOnLaunch ?: true,
          lockOnBackgroundReturn = currentSettings?.lockOnBackgroundReturn ?: true,
          biometricPreferred = currentSettings?.biometricPreferred ?: true,
          guestProfileName = currentSettings?.guestProfileName ?: "Guest Mode",
          dailySpendingSummaryEnabled = s.dailySpendingSummaryEnabled,
          spendingSummaryHour = s.spendingSummaryHour,
          spendingSummaryMinute = s.spendingSummaryMinute,
          budgetAlertsEnabled = s.budgetAlertsEnabled,
          billEmiRemindersEnabled = s.billEmiRemindersEnabled,
          shoppingBudgetRemindersEnabled = s.shoppingBudgetRemindersEnabled,
          updatedAtEpochMillis = s.updatedAtEpochMillis,
        )
      } ?: currentSettings?.copy(primaryCurrencyCode = snapshot.primaryCurrencyCode)
      if (restoredSettings != null) {
        database.appSettingsDao().upsertSettings(restoredSettings)
      }

      // 4. Accounts
      if (snapshot.accounts.isNotEmpty()) {
        database.accountDao().deleteAll()
        snapshot.accounts.forEachIndexed { idx, dto ->
          val insertedId = database.accountDao().insertAccount(
            AccountEntity(
              id = dto.id,
              accountName = dto.accountName,
              accountType = dto.accountType,
              primaryCurrencyCode = dto.primaryCurrencyCode,
              openingBalance = DecimalUtils.fromStringOrZero(dto.openingBalance),
              isGuestLocal = dto.isGuestLocal,
              googleAccountEmail = if (idx == 0) preserveLinkedEmail else null,
              createdAtEpochMillis = dto.createdAtEpochMillis,
            ),
          )
          accountIdRemap[dto.id] = insertedId
        }
      }
      val primaryAccountId = database.accountDao().getPrimaryAccountOnce()?.id ?: 1L

      // 5. Loans & EMI Payments (remapped PKs for full integrity)
      snapshot.loans.forEach { dto ->
        val newLoanId = database.loanDao().insertLoan(
          LoanEntity(
            id = dto.id,
            loanName = dto.loanName,
            lender = dto.lender,
            originalPrincipal = DecimalUtils.fromStringOrZero(dto.originalPrincipal),
            outstandingPrincipal = DecimalUtils.fromStringOrZero(dto.outstandingPrincipal),
            annualInterestRatePercent = DecimalUtils.fromStringOrZero(dto.annualInterestRatePercent),
            emiAmount = DecimalUtils.fromStringOrZero(dto.emiAmount),
            currencyCode = dto.currencyCode,
            paymentDueDayOfMonth = dto.paymentDueDayOfMonth,
            nextDueDateEpochDay = dto.nextDueDateEpochDay,
            remainingTenureMonths = dto.remainingTenureMonths,
            startDateEpochDay = dto.startDateEpochDay,
            status = dto.status,
            createdAtEpochMillis = dto.createdAtEpochMillis,
            updatedAtEpochMillis = dto.updatedAtEpochMillis,
          ),
        )
        loanIdRemap[dto.id] = newLoanId
      }

      if (snapshot.emiPayments.isNotEmpty()) {
        database.loanDao().insertAllEmiPayments(
          snapshot.emiPayments.mapNotNull { dto ->
            val mappedLoanId = loanIdRemap[dto.loanId] ?: return@mapNotNull null
            EmiPaymentEntity(
              id = dto.id,
              loanId = mappedLoanId,
              paymentDateEpochDay = dto.paymentDateEpochDay,
              totalPaidAmount = DecimalUtils.fromStringOrZero(dto.totalPaidAmount),
              principalComponent = DecimalUtils.fromStringOrZero(dto.principalComponent),
              interestComponent = DecimalUtils.fromStringOrZero(dto.interestComponent),
              extraPrepaymentAmount = DecimalUtils.fromStringOrZero(dto.extraPrepaymentAmount),
              remainingBalanceAfter = DecimalUtils.fromStringOrZero(dto.remainingBalanceAfter),
              currencyCode = dto.currencyCode,
              note = dto.note,
              createdAtEpochMillis = dto.createdAtEpochMillis,
            )
          },
        )
      }

      // 6. Savings Goals
      snapshot.savingsGoals.forEach { dto ->
        val newGoalId = database.savingsGoalDao().insertGoal(
          SavingsGoalEntity(
            id = dto.id,
            goalName = dto.goalName,
            goalCategory = dto.goalCategory,
            targetAmount = DecimalUtils.fromStringOrZero(dto.targetAmount),
            currentSavedAmount = DecimalUtils.fromStringOrZero(dto.currentSavedAmount),
            currencyCode = dto.currencyCode,
            targetDateEpochDay = dto.targetDateEpochDay,
            isEmergencyFund = dto.isEmergencyFund,
            createdAtEpochMillis = dto.createdAtEpochMillis,
            updatedAtEpochMillis = dto.updatedAtEpochMillis,
          ),
        )
        goalIdRemap[dto.id] = newGoalId
      }

      // 7. Budgets
      if (snapshot.budgets.isNotEmpty()) {
        database.budgetDao().insertAll(
          snapshot.budgets.map { dto ->
            val resolvedCatId = categoryIdRemap[dto.categoryId] ?: dto.categoryId.takeIf { id ->
              existingCategories.any { it.id == id }
            } ?: fallbackCategoryId
            BudgetEntity(
              id = dto.id,
              categoryId = resolvedCatId,
              yearMonth = dto.yearMonth,
              limitAmount = DecimalUtils.fromStringOrZero(dto.limitAmount),
              currencyCode = dto.currencyCode,
              rolloverEnabled = dto.rolloverEnabled,
              rolloverAmount = DecimalUtils.fromStringOrZero(dto.rolloverAmount),
              warningThresholdPercent = dto.warningThresholdPercent,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            )
          },
        )
      }

      // 8. Investments
      if (snapshot.investments.isNotEmpty()) {
        database.investmentDao().insertAllInvestments(
          snapshot.investments.map { dto ->
            InvestmentEntity(
              id = dto.id,
              investmentName = dto.investmentName,
              investmentType = dto.investmentType,
              amountInvested = DecimalUtils.fromStringOrZero(dto.amountInvested),
              investmentDateEpochDay = dto.investmentDateEpochDay,
              currentValue = dto.currentValue?.let { DecimalUtils.fromStringOrZero(it) },
              notes = dto.notes,
              currencyCode = dto.currencyCode,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            )
          },
        )
      }

      // 9. Transactions
      if (snapshot.transactions.isNotEmpty()) {
        database.transactionDao().insertAll(
          snapshot.transactions.map { dto ->
            val resolvedAccountId = accountIdRemap[dto.accountId] ?: primaryAccountId
            val resolvedCategoryId = categoryIdRemap[dto.categoryId] ?: dto.categoryId.takeIf { id ->
              existingCategories.any { it.id == id }
            } ?: fallbackCategoryId
            TransactionEntity(
              id = dto.id,
              accountId = resolvedAccountId,
              categoryId = resolvedCategoryId,
              type = dto.type,
              amount = DecimalUtils.fromStringOrZero(dto.amount),
              currencyCode = dto.currencyCode,
              exchangeRateToPrimary = dto.exchangeRateToPrimary?.let { DecimalUtils.fromStringOrZero(it) },
              exchangeRateSource = dto.exchangeRateSource,
              exchangeRateTimestamp = dto.exchangeRateTimestamp,
              convertedPrimaryAmount = dto.convertedPrimaryAmount?.let { DecimalUtils.fromStringOrZero(it) },
              primaryCurrencyAtEntry = dto.primaryCurrencyAtEntry,
              description = dto.description,
              transactionDateEpochDay = dto.transactionDateEpochDay,
              isRecurring = dto.isRecurring,
              recurrenceIntervalDays = dto.recurrenceIntervalDays,
              isUpcomingBill = dto.isUpcomingBill,
              isPaid = dto.isPaid,
              linkedGoalId = dto.linkedGoalId?.let { goalIdRemap[it] ?: it },
              linkedLoanId = dto.linkedLoanId?.let { loanIdRemap[it] ?: it },
              deduplicationKey = dto.deduplicationKey,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            )
          },
        )
      }

      // 10. Shopping Lists & Items & Prices
      snapshot.shoppingLists.forEach { dto ->
        val newListId = database.shoppingDao().insertList(
          ShoppingListEntity(
            id = dto.id,
            name = dto.name,
            budgetAmount = DecimalUtils.fromStringOrZero(dto.budgetAmount),
            currencyCode = dto.currencyCode,
            notes = dto.notes,
            createdAtEpochMillis = dto.createdAtEpochMillis,
            updatedAtEpochMillis = dto.updatedAtEpochMillis,
          ),
        )
        listIdRemap[dto.id] = newListId
      }

      if (snapshot.shoppingItems.isNotEmpty()) {
        database.shoppingDao().insertAllItems(
          snapshot.shoppingItems.mapNotNull { dto ->
            val mappedListId = listIdRemap[dto.listId] ?: return@mapNotNull null
            ShoppingItemEntity(
              id = dto.id,
              listId = mappedListId,
              name = dto.name,
              categoryName = dto.categoryName,
              quantity = DecimalUtils.fromStringOrZero(dto.quantity),
              unit = dto.unit,
              estimatedUnitPrice = DecimalUtils.fromStringOrZero(dto.estimatedUnitPrice),
              actualUnitPrice = dto.actualUnitPrice?.let { DecimalUtils.fromStringOrZero(it) },
              isPurchased = dto.isPurchased,
              notes = dto.notes,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            )
          },
        )
      }

      if (snapshot.shoppingShopPrices.isNotEmpty()) {
        database.shoppingDao().insertAllShopPrices(
          snapshot.shoppingShopPrices.map { dto ->
            ShoppingShopPriceEntity(
              id = dto.id,
              itemNameNormalized = dto.itemNameNormalized,
              productDisplayName = dto.productDisplayName,
              shopName = dto.shopName,
              shopLocation = dto.shopLocation,
              unit = dto.unit,
              price = DecimalUtils.fromStringOrZero(dto.price),
              currencyCode = dto.currencyCode,
              priceSource = dto.priceSource,
              isLiveFeedAvailable = dto.isLiveFeedAvailable,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            )
          },
        )
      }
    }

    return snapshot.transactions.size + snapshot.budgets.size + snapshot.loans.size +
      snapshot.emiPayments.size + snapshot.savingsGoals.size + snapshot.investments.size +
      snapshot.shoppingLists.size + snapshot.shoppingItems.size + snapshot.shoppingShopPrices.size
  }

  private suspend fun restoreMergeRemapping(snapshot: BackupSnapshotPayload): Int {
    var importedOrMerged = 0
    val categoryIdRemap = mutableMapOf<Long, Long>()
    val loanIdRemap = mutableMapOf<Long, Long>()
    val goalIdRemap = mutableMapOf<Long, Long>()
    val listIdRemap = mutableMapOf<Long, Long>()

    database.withTransaction {
      val primaryAccountId = database.accountDao().getPrimaryAccountOnce()?.id ?: 1L
      val existingCategories = database.categoryDao().getAllCategoriesOnce()

      snapshot.categories.forEach { dto ->
        val match = existingCategories.firstOrNull { it.nameKey == dto.nameKey && it.type == dto.type }
        val resolvedId = if (match != null) {
          match.id
        } else {
          database.categoryDao().upsert(
            CategoryEntity(
              id = 0L,
              nameKey = dto.nameKey,
              displayNameEn = dto.displayNameEn,
              displayNameTa = dto.displayNameTa,
              type = dto.type,
              iconKey = dto.iconKey,
              colorHex = dto.colorHex,
              isSystemDefault = dto.isSystemDefault,
              sortOrder = dto.sortOrder,
            ),
          )
        }
        categoryIdRemap[dto.id] = resolvedId
      }
      val fallbackCategoryId = database.categoryDao().getAllCategoriesOnce().firstOrNull()?.id ?: 1L

      // Merge Loans and record PK remap for EMIs & Transactions
      val existingLoans = database.loanDao().getAllLoansOnce()
      snapshot.loans.forEach { dto ->
        val match = existingLoans.firstOrNull {
          it.loanName.equals(dto.loanName, ignoreCase = true) &&
            it.lender.equals(dto.lender, ignoreCase = true) &&
            it.startDateEpochDay == dto.startDateEpochDay &&
            it.originalPrincipal.compareTo(DecimalUtils.fromStringOrZero(dto.originalPrincipal)) == 0
        }
        if (match != null) {
          loanIdRemap[dto.id] = match.id
          if (dto.updatedAtEpochMillis > match.updatedAtEpochMillis) {
            database.loanDao().updateLoan(
              match.copy(
                outstandingPrincipal = DecimalUtils.fromStringOrZero(dto.outstandingPrincipal),
                remainingTenureMonths = dto.remainingTenureMonths,
                nextDueDateEpochDay = dto.nextDueDateEpochDay,
                status = dto.status,
                updatedAtEpochMillis = dto.updatedAtEpochMillis,
              ),
            )
            importedOrMerged++
          }
        } else {
          val newId = database.loanDao().insertLoan(
            LoanEntity(
              id = 0L,
              loanName = dto.loanName,
              lender = dto.lender,
              originalPrincipal = DecimalUtils.fromStringOrZero(dto.originalPrincipal),
              outstandingPrincipal = DecimalUtils.fromStringOrZero(dto.outstandingPrincipal),
              annualInterestRatePercent = DecimalUtils.fromStringOrZero(dto.annualInterestRatePercent),
              emiAmount = DecimalUtils.fromStringOrZero(dto.emiAmount),
              currencyCode = dto.currencyCode,
              paymentDueDayOfMonth = dto.paymentDueDayOfMonth,
              nextDueDateEpochDay = dto.nextDueDateEpochDay,
              remainingTenureMonths = dto.remainingTenureMonths,
              startDateEpochDay = dto.startDateEpochDay,
              status = dto.status,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          loanIdRemap[dto.id] = newId
          importedOrMerged++
        }
      }

      // Merge EMI Payments using remapped loanId
      val existingEmis = database.loanDao().getAllEmiPaymentsOnce()
      snapshot.emiPayments.forEach { dto ->
        val mappedLoanId = loanIdRemap[dto.loanId] ?: return@forEach
        val paidAmt = DecimalUtils.fromStringOrZero(dto.totalPaidAmount)
        val alreadyPresent = existingEmis.any {
          it.loanId == mappedLoanId &&
            it.paymentDateEpochDay == dto.paymentDateEpochDay &&
            it.totalPaidAmount.compareTo(paidAmt) == 0 &&
            it.createdAtEpochMillis == dto.createdAtEpochMillis
        }
        if (!alreadyPresent) {
          database.loanDao().insertEmiPayment(
            EmiPaymentEntity(
              id = 0L,
              loanId = mappedLoanId,
              paymentDateEpochDay = dto.paymentDateEpochDay,
              totalPaidAmount = paidAmt,
              principalComponent = DecimalUtils.fromStringOrZero(dto.principalComponent),
              interestComponent = DecimalUtils.fromStringOrZero(dto.interestComponent),
              extraPrepaymentAmount = DecimalUtils.fromStringOrZero(dto.extraPrepaymentAmount),
              remainingBalanceAfter = DecimalUtils.fromStringOrZero(dto.remainingBalanceAfter),
              currencyCode = dto.currencyCode,
              note = dto.note,
              createdAtEpochMillis = dto.createdAtEpochMillis,
            ),
          )
          importedOrMerged++
        }
      }

      // Merge Savings Goals
      val existingGoals = database.savingsGoalDao().getAllGoalsOnce()
      snapshot.savingsGoals.forEach { dto ->
        val target = DecimalUtils.fromStringOrZero(dto.targetAmount)
        val match = existingGoals.firstOrNull {
          it.goalName.equals(dto.goalName, ignoreCase = true) &&
            it.goalCategory == dto.goalCategory &&
            it.targetAmount.compareTo(target) == 0
        }
        if (match != null) {
          goalIdRemap[dto.id] = match.id
          if (dto.updatedAtEpochMillis > match.updatedAtEpochMillis) {
            database.savingsGoalDao().updateGoal(
              match.copy(
                currentSavedAmount = DecimalUtils.fromStringOrZero(dto.currentSavedAmount),
                targetDateEpochDay = dto.targetDateEpochDay,
                updatedAtEpochMillis = dto.updatedAtEpochMillis,
              ),
            )
            importedOrMerged++
          }
        } else {
          val newId = database.savingsGoalDao().insertGoal(
            SavingsGoalEntity(
              id = 0L,
              goalName = dto.goalName,
              goalCategory = dto.goalCategory,
              targetAmount = target,
              currentSavedAmount = DecimalUtils.fromStringOrZero(dto.currentSavedAmount),
              currencyCode = dto.currencyCode,
              targetDateEpochDay = dto.targetDateEpochDay,
              isEmergencyFund = dto.isEmergencyFund,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          goalIdRemap[dto.id] = newId
          importedOrMerged++
        }
      }

      // Merge Budgets by natural key (categoryId, yearMonth, currencyCode)
      snapshot.budgets.forEach { dto ->
        val resolvedCatId = categoryIdRemap[dto.categoryId] ?: dto.categoryId.takeIf { id ->
          existingCategories.any { it.id == id }
        } ?: fallbackCategoryId
        val existing = database.budgetDao().findBudget(resolvedCatId, dto.yearMonth, dto.currencyCode)
        if (existing == null) {
          database.budgetDao().upsert(
            BudgetEntity(
              id = 0L,
              categoryId = resolvedCatId,
              yearMonth = dto.yearMonth,
              limitAmount = DecimalUtils.fromStringOrZero(dto.limitAmount),
              currencyCode = dto.currencyCode,
              rolloverEnabled = dto.rolloverEnabled,
              rolloverAmount = DecimalUtils.fromStringOrZero(dto.rolloverAmount),
              warningThresholdPercent = dto.warningThresholdPercent,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          importedOrMerged++
        } else if (dto.updatedAtEpochMillis > existing.updatedAtEpochMillis) {
          database.budgetDao().upsert(
            existing.copy(
              limitAmount = DecimalUtils.fromStringOrZero(dto.limitAmount),
              rolloverEnabled = dto.rolloverEnabled,
              rolloverAmount = DecimalUtils.fromStringOrZero(dto.rolloverAmount),
              warningThresholdPercent = dto.warningThresholdPercent,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          importedOrMerged++
        }
      }

      // Merge Investments
      val existingInvestments = database.investmentDao().getAllInvestmentsOnce()
      snapshot.investments.forEach { dto ->
        val amt = DecimalUtils.fromStringOrZero(dto.amountInvested)
        val match = existingInvestments.firstOrNull {
          it.investmentName.equals(dto.investmentName, ignoreCase = true) &&
            it.investmentType.equals(dto.investmentType, ignoreCase = true) &&
            it.investmentDateEpochDay == dto.investmentDateEpochDay &&
            it.amountInvested.compareTo(amt) == 0
        }
        if (match == null) {
          database.investmentDao().insertInvestment(
            InvestmentEntity(
              id = 0L,
              investmentName = dto.investmentName,
              investmentType = dto.investmentType,
              amountInvested = amt,
              investmentDateEpochDay = dto.investmentDateEpochDay,
              currentValue = dto.currentValue?.let { DecimalUtils.fromStringOrZero(it) },
              notes = dto.notes,
              currencyCode = dto.currencyCode,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          importedOrMerged++
        } else if (dto.updatedAtEpochMillis > match.updatedAtEpochMillis) {
          database.investmentDao().insertInvestment(
            match.copy(
              currentValue = dto.currentValue?.let { DecimalUtils.fromStringOrZero(it) },
              notes = dto.notes,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          importedOrMerged++
        }
      }

      // Merge Transactions (deduplicationKey first, fallback content fingerprint)
      val existingTxs = database.transactionDao().getAllTransactionsOnce()
      snapshot.transactions.forEach { dto ->
        val amt = DecimalUtils.fromStringOrZero(dto.amount)
        val dupByKey = !dto.deduplicationKey.isNullOrBlank() &&
          existingTxs.any { it.deduplicationKey == dto.deduplicationKey }
        val dupByContent = existingTxs.any {
          it.transactionDateEpochDay == dto.transactionDateEpochDay &&
            it.type == dto.type &&
            it.currencyCode.equals(dto.currencyCode, ignoreCase = true) &&
            it.amount.compareTo(amt) == 0 &&
            it.description.trim().equals(dto.description.trim(), ignoreCase = true) &&
            it.createdAtEpochMillis == dto.createdAtEpochMillis
        }
        if (!dupByKey && !dupByContent) {
          val resolvedCategoryId = categoryIdRemap[dto.categoryId] ?: dto.categoryId.takeIf { id ->
            existingCategories.any { it.id == id }
          } ?: fallbackCategoryId
          database.transactionDao().insert(
            TransactionEntity(
              id = 0L,
              accountId = primaryAccountId,
              categoryId = resolvedCategoryId,
              type = dto.type,
              amount = amt,
              currencyCode = dto.currencyCode,
              exchangeRateToPrimary = dto.exchangeRateToPrimary?.let { DecimalUtils.fromStringOrZero(it) },
              exchangeRateSource = dto.exchangeRateSource,
              exchangeRateTimestamp = dto.exchangeRateTimestamp,
              convertedPrimaryAmount = dto.convertedPrimaryAmount?.let { DecimalUtils.fromStringOrZero(it) },
              primaryCurrencyAtEntry = dto.primaryCurrencyAtEntry,
              description = dto.description,
              transactionDateEpochDay = dto.transactionDateEpochDay,
              isRecurring = dto.isRecurring,
              recurrenceIntervalDays = dto.recurrenceIntervalDays,
              isUpcomingBill = dto.isUpcomingBill,
              isPaid = dto.isPaid,
              linkedGoalId = dto.linkedGoalId?.let { goalIdRemap[it] },
              linkedLoanId = dto.linkedLoanId?.let { loanIdRemap[it] },
              deduplicationKey = dto.deduplicationKey,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          importedOrMerged++
        }
      }

      // Merge Shopping Lists & Items & Prices
      val existingLists = database.shoppingDao().getAllShoppingListsOnce()
      snapshot.shoppingLists.forEach { dto ->
        val match = existingLists.firstOrNull {
          it.name.equals(dto.name, ignoreCase = true) &&
            it.createdAtEpochMillis == dto.createdAtEpochMillis
        }
        if (match != null) {
          listIdRemap[dto.id] = match.id
        } else {
          val newId = database.shoppingDao().insertList(
            ShoppingListEntity(
              id = 0L,
              name = dto.name,
              budgetAmount = DecimalUtils.fromStringOrZero(dto.budgetAmount),
              currencyCode = dto.currencyCode,
              notes = dto.notes,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          listIdRemap[dto.id] = newId
          importedOrMerged++
        }
      }

      val existingItems = database.shoppingDao().getAllShoppingItemsOnce()
      snapshot.shoppingItems.forEach { dto ->
        val mappedListId = listIdRemap[dto.listId] ?: return@forEach
        val exists = existingItems.any {
          it.listId == mappedListId &&
            it.name.equals(dto.name, ignoreCase = true) &&
            it.createdAtEpochMillis == dto.createdAtEpochMillis
        }
        if (!exists) {
          database.shoppingDao().insertItem(
            ShoppingItemEntity(
              id = 0L,
              listId = mappedListId,
              name = dto.name,
              categoryName = dto.categoryName,
              quantity = DecimalUtils.fromStringOrZero(dto.quantity),
              unit = dto.unit,
              estimatedUnitPrice = DecimalUtils.fromStringOrZero(dto.estimatedUnitPrice),
              actualUnitPrice = dto.actualUnitPrice?.let { DecimalUtils.fromStringOrZero(it) },
              isPurchased = dto.isPurchased,
              notes = dto.notes,
              createdAtEpochMillis = dto.createdAtEpochMillis,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          importedOrMerged++
        }
      }

      val existingPrices = database.shoppingDao().getAllShopPricesOnce()
      snapshot.shoppingShopPrices.forEach { dto ->
        val match = existingPrices.firstOrNull {
          it.itemNameNormalized == dto.itemNameNormalized &&
            it.shopName.equals(dto.shopName, ignoreCase = true) &&
            it.unit.equals(dto.unit, ignoreCase = true) &&
            it.currencyCode.equals(dto.currencyCode, ignoreCase = true)
        }
        if (match == null) {
          database.shoppingDao().insertShopPrice(
            ShoppingShopPriceEntity(
              id = 0L,
              itemNameNormalized = dto.itemNameNormalized,
              productDisplayName = dto.productDisplayName,
              shopName = dto.shopName,
              shopLocation = dto.shopLocation,
              unit = dto.unit,
              price = DecimalUtils.fromStringOrZero(dto.price),
              currencyCode = dto.currencyCode,
              priceSource = dto.priceSource,
              isLiveFeedAvailable = dto.isLiveFeedAvailable,
              updatedAtEpochMillis = dto.updatedAtEpochMillis,
            ),
          )
          importedOrMerged++
        }
      }
    }

    return importedOrMerged
  }

  private fun escapeCsv(value: String): String {
    val needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n")
    val escaped = value.replace("\"", "\"\"")
    return if (needsQuotes) "\"$escaped\"" else escaped
  }
}
