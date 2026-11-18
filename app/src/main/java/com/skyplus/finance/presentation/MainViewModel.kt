package com.skyplus.finance.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyplus.finance.data.backup.DriveBackupMetadataItem
import com.skyplus.finance.data.backup.GoogleAuthConfigStatus
import com.skyplus.finance.data.backup.GoogleDriveBackupManager
import com.skyplus.finance.data.cloud.AuthenticatedCloudUser
import com.skyplus.finance.data.cloud.CloudSyncEngine
import com.skyplus.finance.data.cloud.DriveAuthorizationManager
import com.skyplus.finance.data.cloud.DriveAuthorizationOutcome
import com.skyplus.finance.data.cloud.FirebaseAuthManager
import com.skyplus.finance.data.cloud.GuestMigrationPreview
import com.skyplus.finance.data.cloud.GuestToCloudMigrationManager
import com.skyplus.finance.data.cloud.RecoveryUnlockOutcome
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import com.skyplus.finance.data.entities.SyncConflictEntity
import com.skyplus.finance.data.entities.SyncConflictStatus
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.export.FinancialExportManager
import com.skyplus.finance.data.export.FinancialReportExporter
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.AutoBackupPassphraseStore
import com.skyplus.finance.data.security.BackupWorkScheduler
import com.skyplus.finance.data.security.CloudKeyManager
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.intelligence.BankTransactionDetector
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceEngine
import com.skyplus.finance.domain.intelligence.DetectionBatchResult
import com.skyplus.finance.domain.intelligence.ExtraEmiSimulationResult
import com.skyplus.finance.domain.intelligence.SnowballVsAvalancheComparison
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.BudgetStatusItem
import com.skyplus.finance.domain.model.EarlyRepaymentEstimate
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.monetization.OfflineFreeEntitlementGateway
import com.skyplus.finance.domain.monetization.SkyPlusOneStatus
import com.skyplus.finance.domain.report.FinancialReportData
import com.skyplus.finance.domain.report.ReportBuilderUseCase
import com.skyplus.finance.domain.report.ReportExportFormat
import com.skyplus.finance.domain.report.ReportPeriodType
import com.skyplus.finance.domain.report.ReportRequest
import com.skyplus.finance.domain.repository.FinanceRepository
import com.skyplus.finance.domain.shopping.ShopComparisonGroup
import com.skyplus.finance.domain.shopping.ShoppingBudgetStatus
import com.skyplus.finance.domain.shopping.ShoppingPlannerCalculator
import com.skyplus.finance.domain.shopping.ShoppingPlannerOverview
import com.skyplus.finance.domain.shopping.SmartProductSearchEngine
import com.skyplus.finance.domain.shopping.SmartProductSearchResult
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.domain.usecase.ObserveDashboardSnapshotUseCase
import com.skyplus.finance.domain.usecase.PortfolioChartBuilder
import com.skyplus.finance.domain.usecase.PortfolioChartsBundle
import com.skyplus.finance.util.DecimalUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloudSyncUiState(
  val uid: String? = null,
  val email: String? = null,
  val displayName: String? = null,
  val firestoreSyncEnabled: Boolean = false,
  val driveAutoBackupEnabled: Boolean = false,
  val driveWifiOnly: Boolean = true,
  val isRecoveryKeyUnlocked: Boolean = false,
  val hasRemoteRecoveryMetadata: Boolean = false,
  val lastSyncTimestamp: Long = 0L,
  val lastSyncStatus: String = "Guest Mode (Offline Only)",
  val pendingOutboxCount: Int = 0,
  val pendingConflicts: List<SyncConflictEntity> = emptyList(),
  val driveBackupsList: List<DriveBackupMetadataItem> = emptyList(),
  val guestMigrationPreview: GuestMigrationPreview? = null,
) {
  val isSignedIn: Boolean get() = !uid.isNullOrBlank()
}

sealed interface DrivePendingAction {
  data class BackupNow(val passphrase: String) : DrivePendingAction
  data object ListBackups : DrivePendingAction
  data class Restore(val fileId: String?, val overwriteExisting: Boolean, val passphrase: String) : DrivePendingAction
  data class EnableAutoBackup(val passphrase: String) : DrivePendingAction
}

sealed interface MainUiState {
  data object Loading : MainUiState
  data class Ready(
    val snapshot: DashboardSnapshot,
    val allBudgetHistory: List<BudgetStatusItem>,
    val isLocked: Boolean,
    val lastDriveBackupTimestamp: Long = 0L,
    val lastDriveBackupStatus: String = "Not backed up yet",
    val googleAuthStatus: GoogleAuthConfigStatus = GoogleAuthConfigStatus.Ready,
    val reportPreview: FinancialReportData? = null,
    val isBusy: Boolean = false,
    val shoppingLists: List<ShoppingListEntity> = emptyList(),
    val shoppingItems: List<ShoppingItemEntity> = emptyList(),
    val shopPrices: List<ShoppingShopPriceEntity> = emptyList(),
    val shoppingPlannerOverview: ShoppingPlannerOverview = ShoppingPlannerOverview(
      listSummaries = emptyList(),
      totalShoppingBudget = BigDecimal.ZERO,
      totalPlannedSpend = BigDecimal.ZERO,
      totalActualSpend = BigDecimal.ZERO,
      remainingOverallBudget = BigDecimal.ZERO,
      overallStatus = ShoppingBudgetStatus.NO_BUDGET_SET,
      categoryBreakdown = emptyList(),
    ),
    val shopComparisonGroups: List<ShopComparisonGroup> = emptyList(),
    val skyPlusOneStatus: SkyPlusOneStatus = OfflineFreeEntitlementGateway().currentStatus(),
    val cloudSync: CloudSyncUiState = CloudSyncUiState(),
  ) : MainUiState

  data class Error(val message: String) : MainUiState
}

sealed interface MainUiEvent {
  data class ShowMessage(val message: String) : MainUiEvent
  data class ShareFile(val uri: Uri, val mimeType: String, val title: String) : MainUiEvent
  data class SaveFileWithPicker(
    val sourceFile: File,
    val suggestedFileName: String,
    val mimeType: String,
  ) : MainUiEvent
  data class LaunchDriveConsent(val intentSenderRequest: IntentSenderRequest) : MainUiEvent
  data class PromptGuestMigration(val preview: GuestMigrationPreview) : MainUiEvent
}

data class ConfirmedDetectedTransaction(
  val type: TransactionType,
  val amount: BigDecimal,
  val currencyCode: String,
  val categoryId: Long,
  val dateEpochDay: Long,
  val description: String,
  val deduplicationKey: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val repository: FinanceRepository,
  private val observeDashboardSnapshot: ObserveDashboardSnapshotUseCase,
  private val loanEmiCalculator: LoanEmiCalculator,
  private val debtPayoffIntelligenceEngine: DebtPayoffIntelligenceEngine,
  private val budgetAndSavingsCalculator: BudgetAndSavingsCalculator,
  private val portfolioChartBuilder: PortfolioChartBuilder,
  private val reportBuilderUseCase: ReportBuilderUseCase,
  private val reportExporter: FinancialReportExporter,
  private val exportManager: FinancialExportManager,
  private val googleDriveBackupManager: GoogleDriveBackupManager,
  private val preferencesDataStore: AppPreferencesDataStore,
  private val shoppingPlannerCalculator: ShoppingPlannerCalculator,
  private val smartProductSearchEngine: SmartProductSearchEngine,
  private val bankTransactionDetector: BankTransactionDetector,
  private val firebaseAuthManager: FirebaseAuthManager,
  private val driveAuthorizationManager: DriveAuthorizationManager,
  private val cloudKeyManager: CloudKeyManager,
  private val cloudSyncEngine: CloudSyncEngine,
  private val guestToCloudMigrationManager: GuestToCloudMigrationManager,
  private val backupWorkScheduler: BackupWorkScheduler,
  private val autoBackupPassphraseStore: AutoBackupPassphraseStore,
) : ViewModel() {

  private val entitlementGateway = OfflineFreeEntitlementGateway()

  private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  private val _events = Channel<MainUiEvent>(Channel.BUFFERED)
  val events = _events.receiveAsFlow()

  private var launchLockChecked = false
  private val _isLocked = MutableStateFlow(false)
  private val _reportPreview = MutableStateFlow<FinancialReportData?>(null)
  private val _isBusy = MutableStateFlow(false)
  private val _hasRemoteRecoveryMeta = MutableStateFlow(false)
  private val _recoveryUnlockedVersion = MutableStateFlow(0L)
  private val _driveBackupsList = MutableStateFlow<List<DriveBackupMetadataItem>>(emptyList())
  private val _guestMigrationPreview = MutableStateFlow<GuestMigrationPreview?>(null)
  private var pendingDriveAction: DrivePendingAction? = null

  init {
    viewModelScope.launch {
      try {
        repository.ensureInitialized()
      } catch (e: Exception) {
        _uiState.value = MainUiState.Error(e.message ?: "Failed to initialize encrypted database.")
        return@launch
      }

      val baseFlow = combine(
        observeDashboardSnapshot(),
        repository.observeBudgets(),
        _isLocked,
        preferencesDataStore.lastDriveBackupTimestamp,
        preferencesDataStore.lastDriveBackupStatus,
      ) { snapshot, allBudgets, locked, driveTs, driveStatus ->
        BaseStateBundle(snapshot, allBudgets, locked, driveTs, driveStatus)
      }

      val shoppingFlow = combine(
        repository.observeShoppingLists(),
        repository.observeAllShoppingItems(),
        repository.observeAllShopPrices(),
      ) { lists, items, prices ->
        ShoppingStateBundle(lists, items, prices)
      }

      val authScopedSyncFlow = firebaseAuthManager.authStateFlow().flatMapLatest { user: AuthenticatedCloudUser? ->
        if (user == null) {
          flowOf(CloudSyncUiState())
        } else {
          val togglesFlow = combine(
            preferencesDataStore.observeFirestoreSyncEnabled(user.uid),
            preferencesDataStore.observeDriveAutoBackupEnabled(user.uid),
            preferencesDataStore.observeDriveWifiOnly(user.uid),
            preferencesDataStore.observeLastSyncTimestamp(user.uid),
            preferencesDataStore.observeLastSyncStatus(user.uid),
          ) { syncEnabled, driveEnabled, wifiOnly, syncTs, syncStatus ->
            CloudTogglesBundle(syncEnabled, driveEnabled, wifiOnly, syncTs, syncStatus)
          }

          val metaFlow = combine(
            cloudSyncEngine.observePendingOutboxCount(user.uid),
            cloudSyncEngine.observePendingConflicts(user.uid),
            _hasRemoteRecoveryMeta,
            _recoveryUnlockedVersion,
            _driveBackupsList,
          ) { outboxCount, conflicts, hasRemoteMeta, _, backups ->
            CloudMetaBundle(outboxCount, conflicts, hasRemoteMeta, backups)
          }

          combine(togglesFlow, metaFlow, _guestMigrationPreview) { toggles, meta, migPreview ->
            CloudSyncUiState(
              uid = user.uid,
              email = user.email,
              displayName = user.displayName,
              firestoreSyncEnabled = toggles.syncEnabled,
              driveAutoBackupEnabled = toggles.driveEnabled,
              driveWifiOnly = toggles.wifiOnly,
              isRecoveryKeyUnlocked = cloudKeyManager.hasLocalUnlockedDek(user.uid),
              hasRemoteRecoveryMetadata = meta.hasRemoteMeta,
              lastSyncTimestamp = toggles.syncTs,
              lastSyncStatus = toggles.syncStatus,
              pendingOutboxCount = meta.outboxCount,
              pendingConflicts = meta.conflicts,
              driveBackupsList = meta.backups,
              guestMigrationPreview = migPreview,
            )
          }
        }
      }

      combine(
        baseFlow,
        shoppingFlow,
        authScopedSyncFlow,
        _reportPreview,
        _isBusy,
      ) { base, shopping, cloudSync, preview, busy ->
        val snapshot = base.snapshot
        if (!launchLockChecked) {
          launchLockChecked = true
          val settings = snapshot.settings
          if (settings != null && settings.appLockEnabled && settings.lockOnLaunch) {
            _isLocked.value = true
          }
        }

        val precisionMap = snapshot.allCurrencies.associate { it.code.uppercase() to it.decimalPrecision }
        val defaultPrecision = snapshot.primaryCurrency?.decimalPrecision ?: 2
        val allBudgetHistory = budgetAndSavingsCalculator.computeBudgetStatuses(
          budgets = base.allBudgets,
          categories = snapshot.categoriesById,
          transactions = snapshot.allTransactions,
          currencyPrecisionMap = precisionMap,
        )
        val plannerOverview = shoppingPlannerCalculator.computePlannerOverview(
          lists = shopping.lists,
          items = shopping.items,
          currencyPrecisionMap = precisionMap,
          defaultPrecision = defaultPrecision,
        )
        val comparisonGroups = shoppingPlannerCalculator.groupShopPrices(
          prices = shopping.prices,
          currencyPrecisionMap = precisionMap,
        )

        val effectiveGoogleEmail = cloudSync.email ?: snapshot.linkedGoogleEmail
        val effectiveSnapshot = if (effectiveGoogleEmail != snapshot.linkedGoogleEmail) {
          snapshot.copy(linkedGoogleEmail = effectiveGoogleEmail)
        } else {
          snapshot
        }

        MainUiState.Ready(
          snapshot = effectiveSnapshot,
          allBudgetHistory = allBudgetHistory,
          isLocked = _isLocked.value,
          lastDriveBackupTimestamp = base.driveTs,
          lastDriveBackupStatus = base.driveStatus,
          googleAuthStatus = googleDriveBackupManager.checkGoogleOAuthConfiguration(),
          reportPreview = preview,
          isBusy = busy,
          shoppingLists = shopping.lists,
          shoppingItems = shopping.items,
          shopPrices = shopping.prices,
          shoppingPlannerOverview = plannerOverview,
          shopComparisonGroups = comparisonGroups,
          skyPlusOneStatus = entitlementGateway.currentStatus(),
          cloudSync = cloudSync,
        )
      }
        .catch { err ->
          _uiState.value = MainUiState.Error(err.message ?: "Unable to read portfolio storage.")
        }
        .collect { state ->
          _uiState.value = state
        }
    }
  }

  fun onAppBackgrounded() {
    viewModelScope.launch {
      preferencesDataStore.recordBackgroundTimestamp(System.currentTimeMillis())
    }
  }

  fun onAppResumed() {
    val current = _uiState.value as? MainUiState.Ready ?: return
    val settings = current.snapshot.settings ?: return
    if (settings.appLockEnabled && settings.lockOnBackgroundReturn) {
      viewModelScope.launch {
        val lastBg = preferencesDataStore.getLastBackgroundTimestamp()
        if (lastBg > 0L && System.currentTimeMillis() - lastBg > 1500L) {
          _isLocked.value = true
        }
      }
    }
  }

  fun unlockLedger() {
    _isLocked.value = false
  }

  fun lockLedgerNow() {
    _isLocked.value = true
  }

  fun buildChartsForPeriod(
    snapshot: DashboardSnapshot,
    periodType: ReportPeriodType,
    customStartEpochDay: Long? = null,
    customEndEpochDay: Long? = null,
  ): PortfolioChartsBundle {
    return portfolioChartBuilder.buildCharts(
      transactions = snapshot.allTransactions,
      categoriesById = snapshot.categoriesById,
      currenciesByCode = snapshot.currenciesByCode,
      primaryCurrencyCode = snapshot.primaryCurrencyCode,
      periodType = periodType,
      customStartEpochDay = customStartEpochDay,
      customEndEpochDay = customEndEpochDay,
    )
  }

  fun defaultReportDates(period: ReportPeriodType): Pair<LocalDate, LocalDate> {
    return reportBuilderUseCase.defaultDatesForPeriod(period)
  }

  fun generateReportPreview(request: ReportRequest) {
    val ready = _uiState.value as? MainUiState.Ready ?: return
    _reportPreview.value = reportBuilderUseCase.build(ready.snapshot, request)
  }

  fun dismissReportPreview() {
    _reportPreview.value = null
  }

  fun exportReport(
    request: ReportRequest,
    format: ReportExportFormat,
    saveToDevice: Boolean,
  ) {
    val ready = _uiState.value as? MainUiState.Ready ?: return
    viewModelScope.launch {
      _isBusy.value = true
      try {
        val report = reportBuilderUseCase.build(ready.snapshot, request)
        val result: Result<File> = when (format) {
          ReportExportFormat.CSV -> exportManager.exportReportCsv(report)
          ReportExportFormat.PDF -> reportExporter.exportPdf(report)
          ReportExportFormat.DOCX -> reportExporter.exportDocx(report)
        }

        result
          .onSuccess { file ->
            if (saveToDevice) {
              _events.send(
                MainUiEvent.SaveFileWithPicker(
                  sourceFile = file,
                  suggestedFileName = file.name,
                  mimeType = format.mimeType,
                ),
              )
            } else {
              shareExportedFile(file, format.mimeType, "Share ${format.label}")
            }
          }
          .onFailure {
            emitMessage(it.message ?: "Export failed.")
          }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun copyExportedFileToUri(sourceFile: File, destinationUri: Uri) {
    viewModelScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(destinationUri)?.use { out ->
            sourceFile.inputStream().use { input -> input.copyTo(out) }
          } ?: error("Unable to open destination file.")
        }
      }.onSuccess {
        emitMessage("Saved ${sourceFile.name} to device storage.")
      }.onFailure {
        emitMessage(it.message ?: "Failed to save file.")
      }
    }
  }

  fun saveTransaction(
    existingId: Long?,
    type: TransactionType,
    amountText: String,
    categoryId: Long,
    currencyCode: String,
    dateEpochDay: Long,
    description: String,
    isUpcomingBill: Boolean,
    isRecurring: Boolean,
    submissionToken: String,
  ) {
    val amount = DecimalUtils.parsePositiveOrNull(amountText)
    if (amount == null) {
      emitMessage("Amount must be greater than zero.")
      return
    }
    val now = System.currentTimeMillis()
    viewModelScope.launch {
      val result = if (existingId != null && existingId > 0L) {
        val existing = (uiState.value as? MainUiState.Ready)?.snapshot?.allTransactions
          ?.firstOrNull { it.id == existingId }
        if (existing == null) {
          Result.failure(IllegalStateException("Transaction not found"))
        } else {
          repository.updateTransaction(
            existing.copy(
              type = type,
              amount = amount,
              categoryId = categoryId,
              currencyCode = currencyCode,
              description = description.trim(),
              transactionDateEpochDay = dateEpochDay,
              isUpcomingBill = isUpcomingBill,
              isPaid = !isUpcomingBill,
              isRecurring = isRecurring,
              recurrenceIntervalDays = if (isRecurring) 30 else null,
              updatedAtEpochMillis = now,
            ),
          )
        }
      } else {
        repository.addTransaction(
          TransactionEntity(
            accountId = 0L,
            categoryId = categoryId,
            type = type,
            amount = amount,
            currencyCode = currencyCode,
            description = description.trim(),
            transactionDateEpochDay = dateEpochDay,
            isUpcomingBill = isUpcomingBill,
            isPaid = !isUpcomingBill,
            isRecurring = isRecurring,
            recurrenceIntervalDays = if (isRecurring) 30 else null,
            deduplicationKey = submissionToken.ifBlank { UUID.randomUUID().toString() },
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
          ),
        ).map { Unit }
      }
      result.onFailure { emitMessage(it.message ?: "Failed to save transaction.") }
    }
  }

  fun analyzeTransactionMessages(
    rawText: String,
    sourceLabel: String = "Bank Message Import",
  ): DetectionBatchResult {
    val snapshot = (uiState.value as? MainUiState.Ready)?.snapshot
    val precisionMap = snapshot?.allCurrencies?.associate { it.code.uppercase() to it.decimalPrecision }.orEmpty()
    return bankTransactionDetector.detectBatch(
      rawInput = rawText,
      existingTransactions = snapshot?.allTransactions.orEmpty(),
      categories = snapshot?.categoriesById?.values?.toList().orEmpty(),
      defaultCurrencyCode = snapshot?.primaryCurrencyCode ?: "INR",
      currencyPrecisionMap = precisionMap,
      detectionSource = sourceLabel,
    )
  }

  fun saveDetectedTransactions(items: List<ConfirmedDetectedTransaction>) {
    if (items.isEmpty()) return
    val now = System.currentTimeMillis()
    viewModelScope.launch {
      var savedCount = 0
      var failedCount = 0
      items.forEach { item ->
        val result = repository.addTransaction(
          TransactionEntity(
            accountId = 0L,
            categoryId = item.categoryId,
            type = item.type,
            amount = item.amount,
            currencyCode = item.currencyCode.uppercase(),
            description = item.description.trim(),
            transactionDateEpochDay = item.dateEpochDay,
            isUpcomingBill = false,
            isPaid = true,
            isRecurring = false,
            recurrenceIntervalDays = null,
            deduplicationKey = item.deduplicationKey.ifBlank { UUID.randomUUID().toString() },
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
          ),
        )
        if (result.isSuccess) savedCount++ else failedCount++
      }
      if (savedCount > 0) {
        emitMessage("Saved $savedCount detected transaction(s).")
      } else if (failedCount > 0) {
        emitMessage("Unable to save detected transactions.")
      }
    }
  }

  fun deleteTransaction(id: Long) {
    viewModelScope.launch {
      repository.deleteTransaction(id)
        .onFailure { emitMessage(it.message ?: "Failed to delete transaction.") }
    }
  }

  fun markBillPaid(id: Long) {
    viewModelScope.launch {
      repository.markBillPaid(id)
        .onFailure { emitMessage(it.message ?: "Failed to mark bill paid.") }
    }
  }

  fun updateSafeToSpendSettings(
    reservedText: String,
    savingsTargetText: String,
    confirmedIncomeText: String,
    essentialExpensesText: String,
  ) {
    val reserved = DecimalUtils.parseNonNegativeOrNull(reservedText)
    val savings = DecimalUtils.parseNonNegativeOrNull(savingsTargetText)
    val confirmedIncome = DecimalUtils.parseNonNegativeOrNull(confirmedIncomeText)
    val essentials = DecimalUtils.parseNonNegativeOrNull(essentialExpensesText)
    if (reserved == null || savings == null || confirmedIncome == null || essentials == null) {
      emitMessage("Please enter valid non-negative numeric amounts.")
      return
    }
    viewModelScope.launch {
      repository.updateSettings { s ->
        s.copy(
          reservedAmount = reserved,
          monthlySavingsAllocation = savings,
          confirmedRemainingIncome = confirmedIncome,
          monthlyEssentialExpenses = essentials,
        )
      }.onSuccess {
        emitMessage("Targets and buffers updated.")
      }.onFailure {
        emitMessage(it.message ?: "Failed to update targets.")
      }
    }
  }

  fun upsertBudget(
    categoryId: Long,
    yearMonth: String,
    limitText: String,
    currencyCode: String,
    rolloverEnabled: Boolean,
    rolloverText: String,
  ) {
    val limit = DecimalUtils.parsePositiveOrNull(limitText)
    val rollover = DecimalUtils.parseNonNegativeOrNull(rolloverText) ?: BigDecimal.ZERO
    if (limit == null) {
      emitMessage("Budget limit must be greater than zero.")
      return
    }
    val now = System.currentTimeMillis()
    viewModelScope.launch {
      repository.upsertBudget(
        BudgetEntity(
          categoryId = categoryId,
          yearMonth = yearMonth,
          limitAmount = limit,
          currencyCode = currencyCode,
          rolloverEnabled = rolloverEnabled,
          rolloverAmount = rollover,
          warningThresholdPercent = 80,
          createdAtEpochMillis = now,
          updatedAtEpochMillis = now,
        ),
      ).onFailure { emitMessage(it.message ?: "Failed to save budget.") }
    }
  }

  fun deleteBudget(budgetId: Long) {
    viewModelScope.launch {
      repository.deleteBudget(budgetId)
        .onFailure { emitMessage(it.message ?: "Failed to delete budget.") }
    }
  }

  fun addLoan(
    loanName: String,
    lender: String,
    principalText: String,
    outstandingText: String,
    annualRateText: String,
    emiAmountText: String,
    currencyCode: String,
    dueDay: Int,
    remainingMonths: Int,
  ) {
    val principal = DecimalUtils.parsePositiveOrNull(principalText)
    val outstanding = DecimalUtils.parseNonNegativeOrNull(outstandingText) ?: principal
    val rate = DecimalUtils.parseNonNegativeOrNull(annualRateText) ?: BigDecimal.ZERO
    if (loanName.isBlank() || principal == null || outstanding == null || remainingMonths <= 0) {
      emitMessage("Enter valid loan name, principal, and remaining months.")
      return
    }

    val precision = (uiState.value as? MainUiState.Ready)?.snapshot?.currenciesByCode
      ?.get(currencyCode.uppercase())?.decimalPrecision ?: 2

    val computedEmi = DecimalUtils.parsePositiveOrNull(emiAmountText)
      ?: loanEmiCalculator.calculateMonthlyEmi(outstanding, rate, remainingMonths, precision)

    val safeDay = dueDay.coerceIn(1, 28)
    val today = LocalDate.now()
    val candidateDue = today.withDayOfMonth(safeDay.coerceAtMost(today.lengthOfMonth()))
    val nextDue = if (candidateDue.isBefore(today)) candidateDue.plusMonths(1) else candidateDue
    val now = System.currentTimeMillis()

    viewModelScope.launch {
      repository.addLoan(
        LoanEntity(
          loanName = loanName.trim(),
          lender = lender.trim().ifBlank { "Bank / Lender" },
          originalPrincipal = principal,
          outstandingPrincipal = outstanding,
          annualInterestRatePercent = rate,
          emiAmount = computedEmi,
          currencyCode = currencyCode,
          paymentDueDayOfMonth = safeDay,
          nextDueDateEpochDay = nextDue.toEpochDay(),
          remainingTenureMonths = remainingMonths,
          startDateEpochDay = today.toEpochDay(),
          status = if (outstanding <= BigDecimal.ZERO) LoanStatus.PAID_OFF else LoanStatus.ACTIVE,
          createdAtEpochMillis = now,
          updatedAtEpochMillis = now,
        ),
      ).onFailure { emitMessage(it.message ?: "Failed to add loan.") }
    }
  }

  fun deleteLoan(loan: LoanEntity) {
    viewModelScope.launch {
      repository.deleteLoan(loan)
        .onFailure { emitMessage(it.message ?: "Failed to delete loan.") }
    }
  }

  fun recordEmiPayment(loan: LoanEntity, paymentText: String, extraText: String, note: String) {
    val payment = DecimalUtils.parsePositiveOrNull(paymentText)
    val extra = DecimalUtils.parseNonNegativeOrNull(extraText) ?: BigDecimal.ZERO
    if (payment == null) {
      emitMessage("Enter a valid EMI payment amount.")
      return
    }
    viewModelScope.launch {
      repository.recordEmiPayment(
        loanId = loan.id,
        paymentAmount = payment,
        extraPrepayment = extra,
        paymentDateEpochDay = LocalDate.now().toEpochDay(),
        note = note.trim(),
      ).onSuccess {
        emitMessage("EMI payment recorded.")
      }.onFailure {
        emitMessage(it.message ?: "Failed to record EMI payment.")
      }
    }
  }

  fun estimateEarlyRepayment(loan: LoanEntity, extraMonthlyText: String): EarlyRepaymentEstimate {
    val extra = DecimalUtils.parseNonNegativeOrNull(extraMonthlyText) ?: BigDecimal.ZERO
    val precision = (uiState.value as? MainUiState.Ready)?.snapshot?.currenciesByCode
      ?.get(loan.currencyCode.uppercase())?.decimalPrecision ?: 2
    return loanEmiCalculator.estimateEarlyRepayment(loan, extra, precision)
  }

  fun simulateExtraEmiForLoan(loan: LoanEntity, extraMonthlyText: String): ExtraEmiSimulationResult {
    val rawParsed = extraMonthlyText.trim().replace(",", "").let {
      if (it.isEmpty()) BigDecimal.ZERO else it.toBigDecimalOrNull() ?: BigDecimal("-1")
    }
    val precision = (uiState.value as? MainUiState.Ready)?.snapshot?.currenciesByCode
      ?.get(loan.currencyCode.uppercase())?.decimalPrecision ?: 2
    return debtPayoffIntelligenceEngine.simulateExtraEmiPayment(
      loan = loan,
      extraMonthlyPayment = rawParsed,
      decimalPrecision = precision,
    )
  }

  fun compareDebtStrategyWithExtraBudget(
    snapshot: DashboardSnapshot,
    extraMonthlyBudgetText: String,
  ): SnowballVsAvalancheComparison {
    val extra = DecimalUtils.parseNonNegativeOrNull(extraMonthlyBudgetText) ?: BigDecimal.ZERO
    val precision = snapshot.primaryCurrency?.decimalPrecision ?: 2
    return debtPayoffIntelligenceEngine.compareStrategies(
      loans = snapshot.loans,
      primaryCurrencyCode = snapshot.primaryCurrencyCode,
      scale = precision,
      extraMonthlyBudget = extra,
    )
  }

  fun createSavingsGoal(
    goalName: String,
    goalType: SavingsGoalType,
    targetText: String,
    initialSavedText: String,
    currencyCode: String,
    targetMonthsAhead: Int?,
    isEmergencyFund: Boolean,
  ) {
    val target = DecimalUtils.parsePositiveOrNull(targetText)
    val saved = DecimalUtils.parseNonNegativeOrNull(initialSavedText) ?: BigDecimal.ZERO
    if (goalName.isBlank() || target == null) {
      emitMessage("Enter a goal name and a target amount greater than zero.")
      return
    }
    val now = System.currentTimeMillis()
    val targetDay = targetMonthsAhead?.takeIf { it > 0 }?.let {
      LocalDate.now().plusMonths(it.toLong()).toEpochDay()
    }
    viewModelScope.launch {
      repository.upsertSavingsGoal(
        SavingsGoalEntity(
          goalName = goalName.trim(),
          goalCategory = if (isEmergencyFund) SavingsGoalType.EMERGENCY_FUND else goalType,
          targetAmount = target,
          currentSavedAmount = saved,
          currencyCode = currencyCode,
          targetDateEpochDay = targetDay,
          isEmergencyFund = isEmergencyFund,
          createdAtEpochMillis = now,
          updatedAtEpochMillis = now,
        ),
      ).onFailure { emitMessage(it.message ?: "Failed to save goal.") }
    }
  }

  fun contributeToSavingsGoal(goal: SavingsGoalEntity, amountText: String, note: String) {
    val amount = DecimalUtils.parsePositiveOrNull(amountText)
    if (amount == null) {
      emitMessage("Contribution must be greater than zero.")
      return
    }
    viewModelScope.launch {
      repository.contributeToSavingsGoal(
        goalId = goal.id,
        amount = amount,
        currencyCode = goal.currencyCode,
        dateEpochDay = LocalDate.now().toEpochDay(),
        note = note.trim(),
      ).onSuccess {
        emitMessage("Added funds to ${goal.goalName}.")
      }.onFailure {
        emitMessage(it.message ?: "Failed to add savings.")
      }
    }
  }

  fun deleteSavingsGoal(goal: SavingsGoalEntity) {
    viewModelScope.launch {
      repository.deleteSavingsGoal(goal)
        .onFailure { emitMessage(it.message ?: "Failed to delete savings goal.") }
    }
  }

  fun setThemeMode(mode: AppThemeMode) {
    viewModelScope.launch {
      repository.updateSettings { it.copy(themeMode = mode.name) }
    }
  }

  fun setLanguageCode(@Suppress("UNUSED_PARAMETER") languageCode: String) {
    viewModelScope.launch {
      repository.updateSettings { it.copy(languageCode = "en") }
    }
  }

  fun setPrimaryCurrency(currencyCode: String) {
    viewModelScope.launch {
      repository.updateSettings { it.copy(primaryCurrencyCode = currencyCode.uppercase()) }
      repository.setCurrencyWalletActive(currencyCode.uppercase(), true)
    }
  }

  // Phase 3 Smart Shopping actions
  fun upsertShoppingList(
    existingId: Long = 0L,
    name: String,
    budgetText: String,
    currencyCode: String,
    notes: String = "",
  ) {
    val trimmed = name.trim()
    val budget = DecimalUtils.parseNonNegativeOrNull(budgetText)
    if (trimmed.isEmpty() || budget == null) {
      emitMessage("Enter a shopping list name and valid non-negative budget.")
      return
    }
    val now = System.currentTimeMillis()
    val existing = (uiState.value as? MainUiState.Ready)?.shoppingLists?.firstOrNull { it.id == existingId }
    viewModelScope.launch {
      repository.upsertShoppingList(
        ShoppingListEntity(
          id = existingId,
          name = trimmed,
          budgetAmount = budget,
          currencyCode = currencyCode.uppercase(),
          notes = notes.trim(),
          createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
          updatedAtEpochMillis = now,
        ),
      ).onFailure { emitMessage(it.message ?: "Failed to save shopping list.") }
    }
  }

  fun deleteShoppingList(listId: Long) {
    viewModelScope.launch {
      repository.deleteShoppingList(listId)
        .onFailure { emitMessage(it.message ?: "Failed to delete shopping list.") }
    }
  }

  fun upsertShoppingItem(
    existingId: Long = 0L,
    listId: Long,
    name: String,
    categoryName: String,
    quantityText: String,
    unit: String,
    estimatedUnitPriceText: String,
    actualUnitPriceText: String = "",
    isPurchased: Boolean = false,
    notes: String = "",
  ) {
    val trimmedName = name.trim()
    val qty = DecimalUtils.parsePositiveOrNull(quantityText)
    val estPrice = DecimalUtils.parseNonNegativeOrNull(estimatedUnitPriceText)
    val actPrice = actualUnitPriceText.trim().takeIf { it.isNotEmpty() }?.let {
      DecimalUtils.parseNonNegativeOrNull(it)
    }
    if (trimmedName.isEmpty() || qty == null || estPrice == null) {
      emitMessage("Enter item name, quantity > 0, and valid unit price.")
      return
    }
    val now = System.currentTimeMillis()
    val existing = (uiState.value as? MainUiState.Ready)?.shoppingItems?.firstOrNull { it.id == existingId }
    viewModelScope.launch {
      repository.upsertShoppingItem(
        ShoppingItemEntity(
          id = existingId,
          listId = listId,
          name = trimmedName,
          categoryName = categoryName.trim().ifBlank { "Groceries" },
          quantity = qty,
          unit = unit.trim().ifBlank { "pcs" },
          estimatedUnitPrice = estPrice,
          actualUnitPrice = actPrice ?: existing?.actualUnitPrice,
          isPurchased = isPurchased,
          notes = notes.trim(),
          createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
          updatedAtEpochMillis = now,
        ),
      ).onFailure { emitMessage(it.message ?: "Failed to save shopping item.") }
    }
  }

  fun toggleShoppingItemPurchased(item: ShoppingItemEntity, actualUnitPriceText: String? = null) {
    val parsedActual = actualUnitPriceText?.trim()?.takeIf { it.isNotEmpty() }?.let {
      DecimalUtils.parseNonNegativeOrNull(it)
    }
    val newPurchased = !item.isPurchased
    viewModelScope.launch {
      repository.upsertShoppingItem(
        item.copy(
          isPurchased = newPurchased,
          actualUnitPrice = if (newPurchased) (parsedActual ?: item.actualUnitPrice ?: item.estimatedUnitPrice) else item.actualUnitPrice,
          updatedAtEpochMillis = System.currentTimeMillis(),
        ),
      ).onFailure { emitMessage(it.message ?: "Failed to update shopping item.") }
    }
  }

  fun deleteShoppingItem(itemId: Long) {
    viewModelScope.launch {
      repository.deleteShoppingItem(itemId)
        .onFailure { emitMessage(it.message ?: "Failed to delete shopping item.") }
    }
  }

  fun upsertShopPrice(
    existingId: Long = 0L,
    productName: String,
    shopName: String,
    shopLocation: String,
    unit: String,
    priceText: String,
    currencyCode: String,
    priceSource: String = "Manual Entry",
  ) {
    val trimmedProduct = productName.trim()
    val trimmedShop = shopName.trim()
    val parsedPrice = DecimalUtils.parsePositiveOrNull(priceText)
    if (trimmedProduct.isEmpty() || trimmedShop.isEmpty() || parsedPrice == null) {
      emitMessage("Enter product name, shop name, and a valid positive price.")
      return
    }
    val normalizedKey = smartProductSearchEngine.normalizeProductKey(trimmedProduct)
    viewModelScope.launch {
      repository.upsertShopPrice(
        ShoppingShopPriceEntity(
          id = existingId,
          itemNameNormalized = normalizedKey,
          productDisplayName = trimmedProduct,
          shopName = trimmedShop,
          shopLocation = shopLocation.trim().ifBlank { "Manual Location" },
          unit = unit.trim().ifBlank { "piece" },
          price = parsedPrice,
          currencyCode = currencyCode.uppercase(),
          priceSource = priceSource.trim().ifBlank { "Manual Entry" },
          isLiveFeedAvailable = false,
          updatedAtEpochMillis = System.currentTimeMillis(),
        ),
      ).onFailure { emitMessage(it.message ?: "Failed to save shop price.") }
    }
  }

  fun deleteShopPrice(priceId: Long) {
    viewModelScope.launch {
      repository.deleteShopPrice(priceId)
        .onFailure { emitMessage(it.message ?: "Failed to delete shop price.") }
    }
  }

  fun searchShoppingProducts(query: String): List<SmartProductSearchResult> {
    val ready = uiState.value as? MainUiState.Ready
    return smartProductSearchEngine.search(
      query = query,
      existingItems = ready?.shoppingItems.orEmpty(),
      savedShopPrices = ready?.shopPrices.orEmpty(),
    )
  }

  fun updateAppLockSettings(
    enabled: Boolean? = null,
    lockOnLaunch: Boolean? = null,
    lockOnBackgroundReturn: Boolean? = null,
    biometricPreferred: Boolean? = null,
  ) {
    viewModelScope.launch {
      repository.updateSettings { current ->
        current.copy(
          appLockEnabled = enabled ?: current.appLockEnabled,
          lockOnLaunch = lockOnLaunch ?: current.lockOnLaunch,
          lockOnBackgroundReturn = lockOnBackgroundReturn ?: current.lockOnBackgroundReturn,
          biometricPreferred = biometricPreferred ?: current.biometricPreferred,
        )
      }
    }
  }

  fun signInWithGoogle(activityContext: Context) {
    viewModelScope.launch {
      _isBusy.value = true
      try {
        firebaseAuthManager.signInWithGoogle(activityContext)
          .onSuccess { user ->
            val label = user.email ?: user.uid
            googleDriveBackupManager.linkAccountEmailWithoutOverwritingData(label)
            cloudSyncEngine.checkRemoteRecoveryMetadataExists(user.uid)
              .onSuccess { exists -> _hasRemoteRecoveryMeta.value = exists }
            guestToCloudMigrationManager.buildMigrationPreview(user.uid)
              .onSuccess { preview -> _guestMigrationPreview.value = preview }
            emitMessage("Signed in as $label. Cloud Sync & Drive Backup stay optional until you enable them.")
          }
          .onFailure { emitMessage(it.message ?: "Google Sign-In unavailable.") }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun signOutPreservingLocalData() {
    viewModelScope.launch {
      val previousUid = firebaseAuthManager.currentUser()?.uid
      if (!previousUid.isNullOrBlank()) {
        cloudKeyManager.clearLocalDek(previousUid)
        autoBackupPassphraseStore.clearPassphrase(previousUid)
      }
      backupWorkScheduler.cancelFirestoreSync(context)
      backupWorkScheduler.cancelPeriodicDriveBackup(context)
      _driveBackupsList.value = emptyList()
      _guestMigrationPreview.value = null
      _hasRemoteRecoveryMeta.value = false
      googleDriveBackupManager.signOutPreservingLocalData()
        .onSuccess { emitMessage("Signed out of Google. All local financial data remains intact.") }
        .onFailure { emitMessage(it.message ?: "Unable to sign out.") }
    }
  }

  fun refreshCloudEncryptionAndMigrationStatus() {
    val user = firebaseAuthManager.currentUser() ?: return
    viewModelScope.launch {
      cloudSyncEngine.checkRemoteRecoveryMetadataExists(user.uid)
        .onSuccess { _hasRemoteRecoveryMeta.value = it }
      guestToCloudMigrationManager.buildMigrationPreview(user.uid)
        .onSuccess { _guestMigrationPreview.value = it }
    }
  }

  fun setupOrUnlockCloudEncryption(
    passphrase: String,
    enableSyncAfterUnlock: Boolean = false,
  ) {
    val user = firebaseAuthManager.currentUser()
    if (user == null) {
      emitMessage("Sign in with Google first.")
      return
    }
    if (passphrase.length < CloudKeyManager.MIN_PASSPHRASE_LENGTH) {
      emitMessage("Recovery passphrase must be at least ${CloudKeyManager.MIN_PASSPHRASE_LENGTH} characters.")
      return
    }
    viewModelScope.launch {
      _isBusy.value = true
      try {
        cloudSyncEngine.setupOrUnlockEncryption(user.uid, passphrase.toCharArray())
          .onSuccess { outcome ->
            _hasRemoteRecoveryMeta.value = true
            _recoveryUnlockedVersion.value++
            val label = when (outcome) {
              RecoveryUnlockOutcome.INITIALIZED_NEW -> "Created 256-bit recovery encryption key."
              RecoveryUnlockOutcome.UNLOCKED_EXISTING -> "Unlocked cloud encryption key on this device."
            }
            if (enableSyncAfterUnlock) {
              cloudSyncEngine.enableFirestoreSync(user.uid, user.email, user.displayName)
                .onSuccess {
                  backupWorkScheduler.scheduleFirestoreSync(context, triggerImmediate = true)
                  val hasPending = guestToCloudMigrationManager.hasPendingGuestMigration(user.uid)
                  if (hasPending) {
                    val preview = guestToCloudMigrationManager.buildMigrationPreview(user.uid).getOrNull()
                    if (preview != null && preview.totalRecordsToMigrate > 0) {
                      _guestMigrationPreview.value = preview
                      _events.send(MainUiEvent.PromptGuestMigration(preview))
                    }
                  } else {
                    cloudSyncEngine.syncNow(user.uid)
                  }
                  emitMessage("$label Cloud Sync enabled.")
                }
                .onFailure { emitMessage(it.message ?: "Failed to enable Cloud Sync.") }
            } else {
              emitMessage(label)
            }
          }
          .onFailure { emitMessage(it.message ?: "Unable to verify Recovery Passphrase.") }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun rotateRecoveryPassphrase(oldPassphrase: String, newPassphrase: String) {
    val user = firebaseAuthManager.currentUser() ?: return
    viewModelScope.launch {
      _isBusy.value = true
      try {
        cloudSyncEngine.rotateRecoveryPassphrase(
          uid = user.uid,
          oldPassphrase = oldPassphrase.toCharArray(),
          newPassphrase = newPassphrase.toCharArray(),
        )
          .onSuccess { emitMessage("Recovery passphrase updated for all future device recoveries.") }
          .onFailure { emitMessage(it.message ?: "Failed to rotate passphrase.") }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun setFirestoreCloudSyncEnabled(enabled: Boolean) {
    val user = firebaseAuthManager.currentUser()
    if (user == null) {
      emitMessage("Sign in with Google to use optional Cloud Sync.")
      return
    }
    viewModelScope.launch {
      if (!enabled) {
        cloudSyncEngine.disableFirestoreSync(user.uid, user.email, user.displayName)
        backupWorkScheduler.cancelFirestoreSync(context)
        emitMessage("Cloud Sync disabled. All local financial data remains safely on this device.")
      } else {
        if (!cloudKeyManager.hasLocalUnlockedDek(user.uid)) {
          emitMessage("Enter your Recovery Passphrase to unlock Cloud Sync.")
          return@launch
        }
        _isBusy.value = true
        try {
          cloudSyncEngine.enableFirestoreSync(user.uid, user.email, user.displayName)
            .onSuccess {
              backupWorkScheduler.scheduleFirestoreSync(context, triggerImmediate = true)
              val hasPending = guestToCloudMigrationManager.hasPendingGuestMigration(user.uid)
              if (hasPending) {
                val preview = guestToCloudMigrationManager.buildMigrationPreview(user.uid).getOrNull()
                if (preview != null && preview.totalRecordsToMigrate > 0) {
                  _guestMigrationPreview.value = preview
                  _events.send(MainUiEvent.PromptGuestMigration(preview))
                }
              } else {
                cloudSyncEngine.syncNow(user.uid)
              }
              emitMessage("Firestore Cloud Sync enabled (end-to-end encrypted).")
            }
            .onFailure { emitMessage(it.message ?: "Failed to enable Firestore Cloud Sync.") }
        } finally {
          _isBusy.value = false
        }
      }
    }
  }

  fun syncFirestoreNow() {
    val user = firebaseAuthManager.currentUser()
    if (user == null) {
      emitMessage("Sign in with Google first.")
      return
    }
    viewModelScope.launch {
      _isBusy.value = true
      try {
        cloudSyncEngine.syncNow(user.uid)
          .onSuccess { summary ->
            emitMessage(
              "Cloud sync complete: ${summary.pushedCount} uploaded, ${summary.pulledCount} downloaded" +
                if (summary.conflictCount > 0) ", ${summary.conflictCount} conflict(s) need review." else ".",
            )
          }
          .onFailure { emitMessage(it.message ?: "Cloud sync failed.") }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun openGuestMigrationPreview() {
    val user = firebaseAuthManager.currentUser() ?: return
    viewModelScope.launch {
      guestToCloudMigrationManager.buildMigrationPreview(user.uid)
        .onSuccess { preview ->
          _guestMigrationPreview.value = preview
          _events.send(MainUiEvent.PromptGuestMigration(preview))
        }
        .onFailure { emitMessage(it.message ?: "Unable to inspect local records.") }
    }
  }

  fun confirmGuestMigration(deduplicateWithCloud: Boolean) {
    val user = firebaseAuthManager.currentUser() ?: return
    viewModelScope.launch {
      _isBusy.value = true
      try {
        guestToCloudMigrationManager.executeMigration(
          uid = user.uid,
          accountEmail = user.email,
          deduplicateWithCloud = deduplicateWithCloud,
        ).onSuccess { outcome ->
          _guestMigrationPreview.value = guestToCloudMigrationManager.buildMigrationPreview(user.uid).getOrNull()
          emitMessage(
            "Migrated ${outcome.enqueuedRecords} records (${outcome.deduplicatedMatches} merged, ${outcome.syncedNowCount} synced).",
          )
        }.onFailure {
          emitMessage(it.message ?: "Guest data migration failed.")
        }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun resolveSyncConflict(conflictId: Long, resolution: SyncConflictStatus) {
    viewModelScope.launch {
      _isBusy.value = true
      try {
        cloudSyncEngine.resolveConflict(conflictId, resolution)
          .onSuccess {
            val uid = firebaseAuthManager.currentUser()?.uid
            if (uid != null && preferencesDataStore.isFirestoreSyncEnabled(uid)) {
              cloudSyncEngine.syncNow(uid)
            }
            emitMessage("Conflict resolved and ledger updated.")
          }
          .onFailure { emitMessage(it.message ?: "Unable to resolve conflict.") }
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun backupToGoogleDrive(oauthAccessToken: String?, passphrase: String) {
    if (passphrase.length < CloudKeyManager.MIN_PASSPHRASE_LENGTH) {
      emitMessage("Backup passphrase must be at least ${CloudKeyManager.MIN_PASSPHRASE_LENGTH} characters.")
      return
    }
    if (!oauthAccessToken.isNullOrBlank()) {
      viewModelScope.launch {
        runDriveUploadWithToken(oauthAccessToken, passphrase)
      }
      return
    }
    requestDriveConsentOrRun(DrivePendingAction.BackupNow(passphrase))
  }

  fun refreshDriveBackupsList() {
    requestDriveConsentOrRun(DrivePendingAction.ListBackups)
  }

  fun restoreFromGoogleDrive(oauthAccessToken: String?, passphrase: String) {
    restoreFromGoogleDrive(
      fileId = null,
      overwriteExisting = true,
      passphrase = passphrase,
      oauthAccessToken = oauthAccessToken,
    )
  }

  fun restoreFromGoogleDrive(
    fileId: String?,
    overwriteExisting: Boolean,
    passphrase: String,
    oauthAccessToken: String? = null,
  ) {
    if (passphrase.length < CloudKeyManager.MIN_PASSPHRASE_LENGTH) {
      emitMessage("Enter the backup passphrase (at least ${CloudKeyManager.MIN_PASSPHRASE_LENGTH} characters).")
      return
    }
    if (!oauthAccessToken.isNullOrBlank()) {
      viewModelScope.launch {
        runDriveRestoreWithToken(oauthAccessToken, fileId, overwriteExisting, passphrase)
      }
      return
    }
    requestDriveConsentOrRun(DrivePendingAction.Restore(fileId, overwriteExisting, passphrase))
  }

  fun setDriveAutoBackupEnabled(enabled: Boolean, passphrase: String? = null) {
    val user = firebaseAuthManager.currentUser()
    if (user == null) {
      emitMessage("Sign in with Google before enabling automatic Drive backups.")
      return
    }
    viewModelScope.launch {
      if (!enabled) {
        preferencesDataStore.setDriveAutoBackupEnabled(user.uid, false)
        autoBackupPassphraseStore.clearPassphrase(user.uid)
        backupWorkScheduler.cancelPeriodicDriveBackup(context)
        emitMessage("Automatic Drive Backup disabled. Existing backups in Google Drive remain intact.")
      } else {
        val pass = passphrase.orEmpty()
        if (pass.length < CloudKeyManager.MIN_PASSPHRASE_LENGTH) {
          emitMessage("Enter your backup passphrase to enable Automatic Drive Backup.")
          return@launch
        }
        requestDriveConsentOrRun(DrivePendingAction.EnableAutoBackup(pass))
      }
    }
  }

  fun setDriveWifiOnly(wifiOnly: Boolean) {
    val uid = firebaseAuthManager.currentUser()?.uid ?: "GUEST"
    viewModelScope.launch {
      preferencesDataStore.setDriveWifiOnly(uid, wifiOnly)
      if (uid != "GUEST" && preferencesDataStore.isDriveAutoBackupEnabled(uid)) {
        backupWorkScheduler.schedulePeriodicDriveBackup(context, wifiOnly)
      }
      emitMessage(if (wifiOnly) "Drive auto-backups restricted to Wi-Fi." else "Drive auto-backups allowed on cellular data.")
    }
  }

  fun onDriveConsentActivityResult(intentData: Intent?) {
    val action = pendingDriveAction
    pendingDriveAction = null
    val tokenResult = driveAuthorizationManager.extractAccessTokenFromResult(intentData)
    tokenResult.onFailure { err ->
      emitMessage(err.message ?: "Google Drive permission not granted.")
    }.onSuccess { token ->
      viewModelScope.launch {
        when (action) {
          is DrivePendingAction.BackupNow -> runDriveUploadWithToken(token, action.passphrase)
          DrivePendingAction.ListBackups -> runListDriveBackupsWithToken(token)
          is DrivePendingAction.Restore -> runDriveRestoreWithToken(
            token,
            action.fileId,
            action.overwriteExisting,
            action.passphrase,
          )
          is DrivePendingAction.EnableAutoBackup -> completeEnableAutoBackup(token, action.passphrase)
          null -> runListDriveBackupsWithToken(token)
        }
      }
    }
  }

  private fun requestDriveConsentOrRun(action: DrivePendingAction) {
    viewModelScope.launch {
      _isBusy.value = true
      try {
        when (val outcome = driveAuthorizationManager.authorizeDriveFileAccess()) {
          is DriveAuthorizationOutcome.Authorized -> {
            when (action) {
              is DrivePendingAction.BackupNow -> runDriveUploadWithToken(outcome.accessToken, action.passphrase)
              DrivePendingAction.ListBackups -> runListDriveBackupsWithToken(outcome.accessToken)
              is DrivePendingAction.Restore -> runDriveRestoreWithToken(
                outcome.accessToken,
                action.fileId,
                action.overwriteExisting,
                action.passphrase,
              )
              is DrivePendingAction.EnableAutoBackup -> completeEnableAutoBackup(outcome.accessToken, action.passphrase)
            }
          }
          is DriveAuthorizationOutcome.RequiresUserConsent -> {
            pendingDriveAction = action
            _events.send(MainUiEvent.LaunchDriveConsent(outcome.intentSenderRequest))
          }
          is DriveAuthorizationOutcome.Unavailable -> {
            emitMessage(outcome.reason)
          }
        }
      } finally {
        _isBusy.value = false
      }
    }
  }

  private suspend fun runDriveUploadWithToken(token: String, passphrase: String) {
    _isBusy.value = true
    try {
      val user = firebaseAuthManager.currentUser()
      googleDriveBackupManager.uploadEncryptedBackupToDrive(
        oauthAccessToken = token,
        recoveryPassphrase = passphrase.toCharArray(),
        uid = user?.uid ?: "GUEST",
        expectedAccountEmail = user?.email,
      ).onSuccess { msg ->
        if (user != null && preferencesDataStore.isDriveAutoBackupEnabled(user.uid)) {
          autoBackupPassphraseStore.savePassphrase(user.uid, passphrase.toCharArray())
        }
        runListDriveBackupsWithToken(token)
        emitMessage(msg)
      }.onFailure { emitMessage(it.message ?: "Google Drive backup failed.") }
    } finally {
      _isBusy.value = false
    }
  }

  private suspend fun runListDriveBackupsWithToken(token: String) {
    val user = firebaseAuthManager.currentUser()
    googleDriveBackupManager.listBackupsInDrive(
      oauthAccessToken = token,
      uid = user?.uid ?: "GUEST",
      expectedAccountEmail = user?.email,
    ).onSuccess { list ->
      _driveBackupsList.value = list
    }.onFailure { err ->
      emitMessage(err.message ?: "Unable to list Google Drive backups.")
    }
  }

  private suspend fun runDriveRestoreWithToken(
    token: String,
    fileId: String?,
    overwriteExisting: Boolean,
    passphrase: String,
  ) {
    _isBusy.value = true
    try {
      val user = firebaseAuthManager.currentUser()
      googleDriveBackupManager.restoreBackupFromDrive(
        oauthAccessToken = token,
        recoveryPassphrase = passphrase.toCharArray(),
        overwriteExisting = overwriteExisting,
        targetFileId = fileId,
        uid = user?.uid ?: "GUEST",
        expectedAccountEmail = user?.email,
      )
        .onSuccess { count -> emitMessage("Restored $count records from Google Drive.") }
        .onFailure { emitMessage(it.message ?: "Google Drive restore failed.") }
    } finally {
      _isBusy.value = false
    }
  }

  private suspend fun completeEnableAutoBackup(token: String, passphrase: String) {
    val user = firebaseAuthManager.currentUser() ?: return
    val verified = googleDriveBackupManager.verifyDriveAccountOwnership(token, user.email)
    verified.onFailure { err ->
      emitMessage(err.message ?: "Drive ownership verification failed.")
      return
    }
    autoBackupPassphraseStore.savePassphrase(user.uid, passphrase.toCharArray())
    preferencesDataStore.setDriveAutoBackupEnabled(user.uid, true)
    val wifiOnly = preferencesDataStore.isDriveWifiOnly(user.uid)
    backupWorkScheduler.schedulePeriodicDriveBackup(context, wifiOnly)
    emitMessage("Daily encrypted Google Drive Auto-Backup enabled.")
  }

  fun eraseAllFinancialData() {
    viewModelScope.launch {
      repository.eraseAllFinancialData()
        .onSuccess { emitMessage("All financial records permanently erased.") }
        .onFailure { emitMessage(it.message ?: "Failed to erase data.") }
    }
  }

  private fun shareExportedFile(file: File, mimeType: String, title: String) {
    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file,
    )
    viewModelScope.launch {
      _events.send(MainUiEvent.ShareFile(uri = uri, mimeType = mimeType, title = title))
    }
  }

  private fun emitMessage(message: String) {
    viewModelScope.launch {
      _events.send(MainUiEvent.ShowMessage(message))
    }
  }

  private data class BaseStateBundle(
    val snapshot: DashboardSnapshot,
    val allBudgets: List<BudgetEntity>,
    val locked: Boolean,
    val driveTs: Long,
    val driveStatus: String,
  )

  private data class ShoppingStateBundle(
    val lists: List<ShoppingListEntity>,
    val items: List<ShoppingItemEntity>,
    val prices: List<ShoppingShopPriceEntity>,
  )

  private data class CloudTogglesBundle(
    val syncEnabled: Boolean,
    val driveEnabled: Boolean,
    val wifiOnly: Boolean,
    val syncTs: Long,
    val syncStatus: String,
  )

  private data class CloudMetaBundle(
    val outboxCount: Int,
    val conflicts: List<SyncConflictEntity>,
    val hasRemoteMeta: Boolean,
    val backups: List<DriveBackupMetadataItem>,
  )
}
