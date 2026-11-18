package com.skyplus.finance.cloud

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.skyplus.finance.data.backup.GoogleAuthConfigStatus
import com.skyplus.finance.data.cloud.AuthenticatedCloudUser
import com.skyplus.finance.data.cloud.CloudSyncEngine
import com.skyplus.finance.data.cloud.CloudSyncMapper
import com.skyplus.finance.data.cloud.CloudSyncRemoteDataSource
import com.skyplus.finance.data.cloud.FirebaseAuthManager
import com.skyplus.finance.data.cloud.GuestToCloudMigrationManager
import com.skyplus.finance.data.cloud.OutboxMutationRecorder
import com.skyplus.finance.data.cloud.RemoteEncryptedDocument
import com.skyplus.finance.data.cloud.dto.FirestoreEncryptedEnvelope
import com.skyplus.finance.data.cloud.dto.FirestorePrivateProfileDto
import com.skyplus.finance.data.database.DatabaseInitializer
import com.skyplus.finance.data.database.Iso4217CurrencyCatalog
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.SyncConflictEntity
import com.skyplus.finance.data.entities.SyncConflictStatus
import com.skyplus.finance.data.entities.SyncEntityTypes
import com.skyplus.finance.data.entities.SyncOperationType
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.repository.OfflineFinanceRepository
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.CloudKeyManager
import com.skyplus.finance.data.security.InMemoryDekProtector
import com.skyplus.finance.data.security.WrappedRecoveryDekEnvelope
import com.skyplus.finance.designsystem.SkyPlusBrandTheme
import com.skyplus.finance.domain.calculator.BudgetAndSavingsCalculator
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.calculator.SafeToSpendCalculator
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.domain.model.SafeToSpendInput
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.AppStringsProvider
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.CloudSyncUiState
import com.skyplus.finance.presentation.profile.ProfileScreen
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
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
class CloudSyncAndConflictEngineTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var remote: FakeCloudSyncRemoteDataSource
  private lateinit var mapper: CloudSyncMapper

  // Device A components
  private lateinit var dbA: SkyPlusDatabase
  private lateinit var prefsA: AppPreferencesDataStore
  private lateinit var keyMgrA: CloudKeyManager
  private lateinit var authA: FirebaseAuthManager
  private lateinit var outboxA: OutboxMutationRecorder
  private lateinit var repoA: OfflineFinanceRepository
  private lateinit var engineA: CloudSyncEngine
  private lateinit var migrationA: GuestToCloudMigrationManager

  // Device B components
  private lateinit var dbB: SkyPlusDatabase
  private lateinit var prefsB: AppPreferencesDataStore
  private lateinit var keyMgrB: CloudKeyManager
  private lateinit var authB: FirebaseAuthManager
  private lateinit var outboxB: OutboxMutationRecorder
  private lateinit var repoB: OfflineFinanceRepository
  private lateinit var engineB: CloudSyncEngine

  private var activeUserA: AuthenticatedCloudUser? = null
  private var activeUserB: AuthenticatedCloudUser? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    remote = FakeCloudSyncRemoteDataSource()
    mapper = CloudSyncMapper()

    // Use isolated fast PBKDF2 iterations in unit/sync engine tests (CryptoV2Test already verifies 210k)
    dbA = Room.inMemoryDatabaseBuilder(context, SkyPlusDatabase::class.java).allowMainThreadQueries().build()
    prefsA = AppPreferencesDataStore(context)
    keyMgrA = CloudKeyManager.createForTests(InMemoryDekProtector(), pbkdf2Iterations = 10_000)
    authA = FirebaseAuthManager.createForTests(context) { activeUserA }
    outboxA = OutboxMutationRecorder(dbA, authA, prefsA, mapper)
    repoA = OfflineFinanceRepository(dbA, DatabaseInitializer(dbA), LoanEmiCalculator(), outboxA)
    engineA = CloudSyncEngine(dbA, remote, keyMgrA, prefsA, mapper, outboxA)
    migrationA = GuestToCloudMigrationManager(dbA, remote, keyMgrA, prefsA, mapper, engineA)

    dbB = Room.inMemoryDatabaseBuilder(context, SkyPlusDatabase::class.java).allowMainThreadQueries().build()
    prefsB = prefsA
    keyMgrB = CloudKeyManager.createForTests(InMemoryDekProtector(), pbkdf2Iterations = 10_000)
    authB = FirebaseAuthManager.createForTests(context) { activeUserB }
    outboxB = OutboxMutationRecorder(dbB, authB, prefsB, mapper)
    repoB = OfflineFinanceRepository(dbB, DatabaseInitializer(dbB), LoanEmiCalculator(), outboxB)
    engineB = CloudSyncEngine(dbB, remote, keyMgrB, prefsB, mapper, outboxB)
  }

  @After
  fun tearDown() {
    dbA.close()
    dbB.close()
  }

  @Test
  fun `option C independence guest mode and drive only signed in mode write zero outbox or firestore data`() = runTest {
    repoA.ensureInitialized()
    val today = 20_100L

    // 1. Guest Mode (not signed in): local CRUD works 100%, 0 outbox entries, 0 Firestore calls
    activeUserA = null
    repoA.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("420.00"),
        currencyCode = "INR",
        description = "Guest Offline Coffee",
        transactionDateEpochDay = today,
        deduplicationKey = "guest-tx-1",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
      ),
    ).getOrThrow()

    assertEquals(1, repoA.observeTransactions().first().size)
    assertEquals(0, dbA.syncDao().getAllOutboxItemsForOwner("uid-sync-test").size)
    assertEquals(0, remote.totalRecordsStored())

    // 2. Signed in WITH Firestore Sync OFF (Drive-Only user): still 0 outbox entries, 0 Firestore writes
    val uid = "uid-drive-only-1"
    activeUserA = AuthenticatedCloudUser(uid, "driveonly@example.com", "Drive User")
    assertFalse(prefsA.isFirestoreSyncEnabled(uid))

    repoA.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("880.00"),
        currencyCode = "INR",
        description = "Drive-Only Book Purchase",
        transactionDateEpochDay = today,
        deduplicationKey = "drive-only-tx-1",
        createdAtEpochMillis = 200L,
        updatedAtEpochMillis = 200L,
      ),
    ).getOrThrow()

    assertEquals(2, repoA.observeTransactions().first().size)
    assertEquals(0, dbA.syncDao().getAllOutboxItemsForOwner(uid).size)
    assertEquals(0, remote.totalRecordsStored())
  }

  @Test
  fun `multi device encrypted push pull quota limits and disable sync preserves local data`() = runTest {
    repoA.ensureInitialized()
    repoB.ensureInitialized()
    val uid = "uid-multidevice-1"
    val pass = "MultiDevicePass!2026".toCharArray()
    activeUserA = AuthenticatedCloudUser(uid, "multi@example.com", "Multi User")
    activeUserB = AuthenticatedCloudUser(uid, "multi@example.com", "Multi User")

    // Device A sets up recovery key & explicitly opts into Firestore Sync
    engineA.setupOrUnlockEncryption(uid, pass).getOrThrow()
    engineA.enableFirestoreSync(uid, "multi@example.com", "Multi User").getOrThrow()

    // Device A records a transaction while offline -> queued in outbox
    repoA.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("3999.00"),
        currencyCode = "INR",
        description = "Encrypted Cloud Groceries",
        transactionDateEpochDay = 20_110L,
        deduplicationKey = "sync-tx-100",
        createdAtEpochMillis = 1000L,
        updatedAtEpochMillis = 1000L,
      ),
    ).getOrThrow()

    assertEquals(1, dbA.syncDao().getAllOutboxItemsForOwner(uid).size)

    // Sync Device A -> pushes ciphertext to remote
    val syncSummaryA = engineA.syncNow(uid).getOrThrow()
    assertEquals(1, syncSummaryA.pushedCount)
    assertEquals(0, dbA.syncDao().getAllOutboxItemsForOwner(uid).size)

    // Verify zero plaintext leakage inside remote Firestore envelope
    val remoteDocs = remote.dumpAllDocs()
    assertTrue(remoteDocs.isNotEmpty())
    assertTrue(remoteDocs.all { !it.encryptedPayloadBase64.contains("Encrypted Cloud Groceries") })
    assertTrue(remoteDocs.all { !it.encryptedPayloadBase64.contains("3999") })

    // Verify quota-aware delta fetch limit is <= 500
    assertTrue(remote.recordedDeltaLimits.all { it <= CloudSyncEngine.DELTA_PAGE_LIMIT })

    // Device B signs in with SAME passphrase, opts in, and pulls remote deltas
    engineB.setupOrUnlockEncryption(uid, "MultiDevicePass!2026".toCharArray()).getOrThrow()
    engineB.enableFirestoreSync(uid, "multi@example.com", "Multi User").getOrThrow()
    val syncSummaryB = engineB.syncNow(uid).getOrThrow()
    assertEquals(1, syncSummaryB.pulledCount)

    val deviceBTxs = repoB.observeTransactions().first()
    assertEquals(1, deviceBTxs.size)
    assertEquals("Encrypted Cloud Groceries", deviceBTxs.first().description)
    assertEquals(0, deviceBTxs.first().amount.compareTo(BigDecimal("3999.00")))

    // Disabling Firestore Sync on Device A preserves ALL local financial records
    engineA.disableFirestoreSync(uid, "multi@example.com", "Multi User")
    assertFalse(prefsA.isFirestoreSyncEnabled(uid))
    assertEquals(1, repoA.observeTransactions().first().size)

    // Sign-out / account isolation clears local unlocked DEK
    keyMgrA.clearLocalDek(uid)
    assertFalse(keyMgrA.hasLocalUnlockedDek(uid))
  }

  @Test
  fun `concurrent edits trigger explicit conflict resolution keep local use cloud and keep both`() = runTest {
    repoA.ensureInitialized()
    repoB.ensureInitialized()
    val uid = "uid-conflict-test"
    val pass = "ConflictPass!2026".toCharArray()
    activeUserA = AuthenticatedCloudUser(uid, "conflict@example.com", "Conflict User")
    activeUserB = AuthenticatedCloudUser(uid, "conflict@example.com", "Conflict User")

    engineA.setupOrUnlockEncryption(uid, pass).getOrThrow()
    engineA.enableFirestoreSync(uid, "conflict@example.com", "Conflict User").getOrThrow()

    val txIdA = repoA.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("1000.00"),
        currencyCode = "INR",
        description = "Shared Dinner",
        transactionDateEpochDay = 20_120L,
        deduplicationKey = "shared-dinner-1",
        createdAtEpochMillis = 1000L,
        updatedAtEpochMillis = 1000L,
      ),
    ).getOrThrow()
    engineA.syncNow(uid).getOrThrow()

    // Device B pulls initial record (v1)
    engineB.setupOrUnlockEncryption(uid, "ConflictPass!2026".toCharArray()).getOrThrow()
    engineB.enableFirestoreSync(uid, "conflict@example.com", "Conflict User").getOrThrow()
    engineB.syncNow(uid).getOrThrow()

    // Device B edits its record and pushes v2 to cloud
    val txB = repoB.observeTransactions().first().first()
    val localMapB = dbB.syncDao().findMapByLocalId(uid, SyncEntityTypes.TRANSACTION, txB.id.toString())!!
    // Simulate a distinct device ID on B by editing & pushing directly
    repoB.updateTransaction(txB.copy(description = "Dinner From Device B", amount = BigDecimal("1500.00"), updatedAtEpochMillis = 3000L)).getOrThrow()
    // Ensure remote updatedByDeviceId differs from A's deviceId
    remote.forceOverrideDeviceIdOnNextWrite = "device-b-distinct"
    engineB.syncNow(uid).getOrThrow()

    // Meanwhile Device A edits the SAME record locally offline -> tries to sync -> MUST record PENDING conflict, NOT silently overwrite!
    val txA = dbA.transactionDao().getTransactionById(txIdA)!!
    repoA.updateTransaction(txA.copy(description = "Dinner From Device A", amount = BigDecimal("1250.00"), updatedAtEpochMillis = 2500L)).getOrThrow()
    val conflictSummary = engineA.syncNow(uid).getOrThrow()
    assertTrue(conflictSummary.conflictCount >= 1)

    val pendingConflicts = dbA.syncDao().getPendingConflicts(uid)
    assertEquals(1, pendingConflicts.size)
    val conflict = pendingConflicts.first()
    assertTrue(conflict.localSummary.contains("Dinner From Device A"))
    assertTrue(conflict.remoteSummary.contains("Dinner From Device B"))

    // Resolve with KEEP BOTH -> keeps local record AND creates second record from cloud!
    engineA.resolveConflict(conflict.id, SyncConflictStatus.RESOLVED_KEEP_BOTH).getOrThrow()
    assertEquals(0, dbA.syncDao().getPendingConflicts(uid).size)
    val txsAfterKeepBoth = dbA.transactionDao().getAllTransactionsOnce()
    assertEquals(2, txsAfterKeepBoth.size)
    assertTrue(txsAfterKeepBoth.any { it.description == "Dinner From Device A" })
    assertTrue(txsAfterKeepBoth.any { it.description == "Dinner From Device B" })
    assertEquals(localMapB.recordId, dbA.syncDao().findMapByLocalId(uid, SyncEntityTypes.TRANSACTION, txIdA.toString())?.recordId)
  }

  @Test
  fun `guest migration preview deduplicates matching cloud transaction and preserves local data`() = runTest {
    repoA.ensureInitialized()
    val uid = "uid-migration-1"

    // Pre-existing cloud transaction on account (inserted as if already synced previously)
    activeUserA = AuthenticatedCloudUser(uid, "mig@example.com", "Mig User")
    engineA.setupOrUnlockEncryption(uid, "MigrationPass!2026".toCharArray()).getOrThrow()
    val dupKey = "dup-rent-oct-2026"
    val preCloudJson = """{"recordId":"remote-rec-dup","ownerUid":"$uid","deviceId":"old-phone","categoryRecordId":"cat_sys_1","type":"EXPENSE","amount":"18000.00","currencyCode":"INR","description":"Apartment Rent","transactionDateEpochDay":20105,"deduplicationKey":"$dupKey","createdAtEpochMillis":500,"updatedAtEpochMillis":500}"""
    val aad = CloudSyncEngine.buildPayloadAad(uid, SyncEntityTypes.TRANSACTION, "remote-rec-dup", 1L)
    val enc = keyMgrA.encryptPayload(uid, preCloudJson, aad).getOrThrow()
    remote.upsertEncryptedRecord(
      SyncEntityTypes.firestoreCollectionFor(SyncEntityTypes.TRANSACTION),
      FirestoreEncryptedEnvelope(
        recordId = "remote-rec-dup",
        ownerUid = uid,
        entityType = SyncEntityTypes.TRANSACTION,
        encryptedPayloadBase64 = enc.ciphertextBase64,
        payloadNonceBase64 = enc.nonceBase64,
        schemaVersion = 3,
        syncVersion = 1L,
        updatedByDeviceId = "old-phone",
        isDeleted = false,
        clientUpdatedAtMillis = 500L,
      ),
    ).getOrThrow()

    // Create 2 Guest local transactions (1 matches dupKey, 1 is unique local record) before opting into sync
    activeUserA = null
    repoA.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("18000.00"),
        currencyCode = "INR",
        description = "Apartment Rent",
        transactionDateEpochDay = 20_105L,
        deduplicationKey = dupKey,
        createdAtEpochMillis = 600L,
        updatedAtEpochMillis = 600L,
      ),
    ).getOrThrow()
    repoA.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("540.00"),
        currencyCode = "INR",
        description = "New Local Utility",
        transactionDateEpochDay = 20_106L,
        deduplicationKey = "unique-guest-tx-2",
        createdAtEpochMillis = 700L,
        updatedAtEpochMillis = 700L,
      ),
    ).getOrThrow()

    // Sign in and preview migration
    activeUserA = AuthenticatedCloudUser(uid, "mig@example.com", "Mig User")
    assertTrue(migrationA.hasPendingGuestMigration(uid))
    val preview = migrationA.buildMigrationPreview(uid).getOrThrow()
    assertEquals(2, preview.transactionCount)
    assertTrue(preview.hasRemoteData)
    assertEquals(1, preview.estimatedDuplicateCount)

    // Execute confirmed migration with deduplication
    val outcome = migrationA.executeMigration(
      uid = uid,
      accountEmail = "mig@example.com",
      deduplicateWithCloud = true,
    ).getOrThrow()
    assertEquals(1, outcome.deduplicatedMatches)
    assertTrue(outcome.enqueuedRecords >= 1)
    // All local financial records preserved intact
    assertEquals(2, dbA.transactionDao().getAllTransactionsOnce().size)
    // Idempotent second call creates 0 duplicate entries
    assertFalse(migrationA.hasPendingGuestMigration(uid))
  }

  @Test
  fun `profile ui shows independent opt out toggles and opens conflict resolution dialog`() {
    val snapshot = minimalSnapshot()
    var resolvedPair: Pair<Long, SyncConflictStatus>? = null

    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          ProfileScreen(
            snapshot = snapshot,
            lastDriveBackupTimestamp = 0L,
            lastDriveBackupStatus = "Not backed up yet",
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
            cloudSync = CloudSyncUiState(
              uid = "uid-ui-1",
              email = "uiuser@gmail.com",
              firestoreSyncEnabled = false,
              driveAutoBackupEnabled = false,
              pendingConflicts = listOf(
                SyncConflictEntity(
                  id = 99L,
                  ownerUid = "uid-ui-1",
                  entityType = SyncEntityTypes.TRANSACTION,
                  localId = "1",
                  recordId = "rec-1",
                  localPayloadJson = "{}",
                  remotePayloadJson = "{}",
                  localSummary = "Local Cafe 250",
                  remoteSummary = "Cloud Cafe 300",
                  localUpdatedAtEpochMillis = 100L,
                  remoteUpdatedAtEpochMillis = 200L,
                  remoteSyncVersion = 2L,
                  remoteDeviceId = "other-phone",
                  status = SyncConflictStatus.PENDING.name,
                  createdAtEpochMillis = 200L,
                ),
              ),
            ),
            onResolveConflict = { id, status -> resolvedPair = id to status },
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("profile_firestore_sync_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("switch_firestore_sync").assertIsOff()
    composeTestRule.onNodeWithTag("resolve_conflicts_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("resolve_conflicts_banner").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Local Cafe 250", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("conflict_use_cloud_99").performClick()
    assertEquals(99L to SyncConflictStatus.RESOLVED_USE_CLOUD, resolvedPair)
  }

  @Test
  fun `guest profile renders both cloud sync and drive auto backup switches disabled and off by default`() {
    val snapshot = minimalSnapshot()
    composeTestRule.setContent {
      CompositionLocalProvider(LocalAppStrings provides AppStringsProvider.english) {
        SkyPlusBrandTheme {
          ProfileScreen(
            snapshot = snapshot,
            lastDriveBackupTimestamp = 0L,
            lastDriveBackupStatus = "Not backed up yet",
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
            cloudSync = CloudSyncUiState(),
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("switch_firestore_sync").assertIsOff()
    composeTestRule.onNodeWithTag("switch_firestore_sync").assertIsNotEnabled()
  }

  private fun minimalSnapshot(): DashboardSnapshot {
    val inr = Iso4217CurrencyCatalog.getAllOfficialCurrencies().first { it.code == "INR" }
    val safe = SafeToSpendCalculator().calculate(
      SafeToSpendInput(BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 10, "INR", 2),
    )
    return DashboardSnapshot(
      primaryCurrency = inr,
      primaryCurrencyCode = "INR",
      themeMode = AppThemeMode.SYSTEM,
      linkedGoogleEmail = null,
      settings = null,
      totalAssets = BigDecimal("1000"),
      totalLiabilities = BigDecimal.ZERO,
      totalNetWorth = BigDecimal("1000"),
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
      categoriesById = mapOf(1L to CategoryEntity(1L, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0)),
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
  }

  private class FakeCloudSyncRemoteDataSource : CloudSyncRemoteDataSource {
    private val profiles = ConcurrentHashMap<String, FirestorePrivateProfileDto>()
    private val recoveryMeta = ConcurrentHashMap<String, WrappedRecoveryDekEnvelope>()
    private val collections = ConcurrentHashMap<String, ConcurrentHashMap<String, RemoteEncryptedDocument>>()
    val recordedDeltaLimits = mutableListOf<Long>()
    var forceOverrideDeviceIdOnNextWrite: String? = null

    fun totalRecordsStored(): Int = collections.values.sumOf { it.size }
    fun dumpAllDocs(): List<RemoteEncryptedDocument> = collections.values.flatMap { it.values }

    override suspend fun upsertPrivateProfile(profile: FirestorePrivateProfileDto): Result<Unit> = runCatching {
      profiles[profile.uid] = profile
    }

    override suspend fun getPrivateProfile(uid: String): Result<FirestorePrivateProfileDto?> = runCatching {
      profiles[uid]
    }

    override suspend fun getRecoveryMetadata(uid: String): Result<WrappedRecoveryDekEnvelope?> = runCatching {
      recoveryMeta[uid]
    }

    override suspend fun upsertRecoveryMetadata(envelope: WrappedRecoveryDekEnvelope): Result<Unit> = runCatching {
      recoveryMeta[envelope.ownerUid] = envelope
    }

    override suspend fun getEncryptedRecord(
      uid: String,
      collectionName: String,
      recordId: String,
    ): Result<RemoteEncryptedDocument?> = runCatching {
      collections["$uid/$collectionName"]?.get(recordId)
    }

    override suspend fun upsertEncryptedRecord(
      collectionName: String,
      envelope: FirestoreEncryptedEnvelope,
    ): Result<Unit> = runCatching {
      val bucket = collections.getOrPut("${envelope.ownerUid}/$collectionName") { ConcurrentHashMap() }
      val deviceOverride = forceOverrideDeviceIdOnNextWrite?.also { forceOverrideDeviceIdOnNextWrite = null }
      bucket[envelope.recordId] = RemoteEncryptedDocument(
        recordId = envelope.recordId,
        ownerUid = envelope.ownerUid,
        entityType = envelope.entityType,
        encryptedPayloadBase64 = envelope.encryptedPayloadBase64,
        payloadNonceBase64 = envelope.payloadNonceBase64,
        schemaVersion = envelope.schemaVersion,
        syncVersion = envelope.syncVersion,
        updatedByDeviceId = deviceOverride ?: envelope.updatedByDeviceId,
        isDeleted = envelope.isDeleted,
        clientUpdatedAtMillis = envelope.clientUpdatedAtMillis,
        serverUpdatedAtMillis = envelope.clientUpdatedAtMillis,
      )
    }

    override suspend fun fetchEncryptedRecordsDelta(
      uid: String,
      collectionName: String,
      sinceClientUpdatedAtMillis: Long,
      limit: Long,
    ): Result<List<RemoteEncryptedDocument>> = runCatching {
      recordedDeltaLimits += limit
      val bucket = collections["$uid/$collectionName"] ?: return@runCatching emptyList()
      bucket.values
        .filter { sinceClientUpdatedAtMillis <= 0L || it.clientUpdatedAtMillis > sinceClientUpdatedAtMillis }
        .sortedBy { it.clientUpdatedAtMillis }
        .take(limit.toInt())
    }
  }
}
