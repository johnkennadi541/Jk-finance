package com.skyplus.finance.data.cloud

import androidx.room.withTransaction
import com.skyplus.finance.data.cloud.dto.CloudTransactionPayload
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.SyncEntityTypes
import com.skyplus.finance.data.entities.SyncOperationType
import com.skyplus.finance.data.entities.SyncOutboxEntity
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.CloudKeyManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

data class GuestMigrationPreview(
  val transactionCount: Int,
  val budgetCount: Int,
  val loanCount: Int,
  val savingsGoalCount: Int,
  val investmentCount: Int,
  val shoppingListCount: Int,
  val estimatedDuplicateCount: Int,
  val hasRemoteData: Boolean,
  val totalRecordsToMigrate: Int,
)

data class GuestMigrationOutcome(
  val enqueuedRecords: Int,
  val deduplicatedMatches: Int,
  val syncedNowCount: Int,
)

/**
 * Handles explicit, preview-confirmed migration of offline Guest Mode records when a user signs in
 * with Google and opts into Cloud Sync.
 * - Never overwrites local or cloud data silently.
 * - Preserves all existing financial records.
 * - Idempotent and resumable: already-mapped records are skipped cleanly.
 */
@Singleton
class GuestToCloudMigrationManager @Inject constructor(
  private val database: SkyPlusDatabase,
  private val remoteDataSource: CloudSyncRemoteDataSource,
  private val cloudKeyManager: CloudKeyManager,
  private val preferencesDataStore: AppPreferencesDataStore,
  private val mapper: CloudSyncMapper,
  private val cloudSyncEngine: CloudSyncEngine,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun hasPendingGuestMigration(uid: String): Boolean {
    if (uid.isBlank() || uid == "GUEST") return false
    if (preferencesDataStore.isGuestMigrationCompleted(uid)) return false
    val unmapped = countUnmappedLocalUserRecords(uid)
    return unmapped > 0
  }

  private suspend fun countUnmappedLocalUserRecords(uid: String): Int {
    val existingMaps = database.syncDao().getAllMapsForOwner(uid)
      .groupBy { it.entityType }
      .mapValues { (_, list) -> list.map { it.localId }.toSet() }

    val txs = database.transactionDao().getAllTransactionsOnce()
      .count { it.id.toString() !in existingMaps[SyncEntityTypes.TRANSACTION].orEmpty() }
    val budgets = database.budgetDao().getAllBudgetsOnce()
      .count { it.id.toString() !in existingMaps[SyncEntityTypes.BUDGET].orEmpty() }
    val loans = database.loanDao().getAllLoansOnce()
      .count { it.id.toString() !in existingMaps[SyncEntityTypes.LOAN].orEmpty() }
    val goals = database.savingsGoalDao().getAllGoalsOnce()
      .count { it.id.toString() !in existingMaps[SyncEntityTypes.SAVINGS_GOAL].orEmpty() }
    val investments = database.investmentDao().getAllInvestmentsOnce()
      .count { it.id.toString() !in existingMaps[SyncEntityTypes.INVESTMENT].orEmpty() }
    val shoppingLists = database.shoppingDao().getAllShoppingListsOnce()
      .count { it.id.toString() !in existingMaps[SyncEntityTypes.SHOPPING_LIST].orEmpty() }
    return txs + budgets + loans + goals + investments + shoppingLists
  }

  suspend fun buildMigrationPreview(uid: String): Result<GuestMigrationPreview> = runCatching {
    require(uid.isNotBlank() && uid != "GUEST") { "Sign in with Google to inspect migration." }
    val existingMaps = database.syncDao().getAllMapsForOwner(uid)
      .groupBy { it.entityType }
      .mapValues { (_, list) -> list.map { it.localId }.toSet() }

    val unmappedTxs = database.transactionDao().getAllTransactionsOnce()
      .filter { it.id.toString() !in existingMaps[SyncEntityTypes.TRANSACTION].orEmpty() }
    val unmappedBudgets = database.budgetDao().getAllBudgetsOnce()
      .filter { it.id.toString() !in existingMaps[SyncEntityTypes.BUDGET].orEmpty() }
    val unmappedLoans = database.loanDao().getAllLoansOnce()
      .filter { it.id.toString() !in existingMaps[SyncEntityTypes.LOAN].orEmpty() }
    val unmappedGoals = database.savingsGoalDao().getAllGoalsOnce()
      .filter { it.id.toString() !in existingMaps[SyncEntityTypes.SAVINGS_GOAL].orEmpty() }
    val unmappedInvestments = database.investmentDao().getAllInvestmentsOnce()
      .filter { it.id.toString() !in existingMaps[SyncEntityTypes.INVESTMENT].orEmpty() }
    val unmappedLists = database.shoppingDao().getAllShoppingListsOnce()
      .filter { it.id.toString() !in existingMaps[SyncEntityTypes.SHOPPING_LIST].orEmpty() }

    var hasRemote = false
    var estDuplicates = 0
    if (cloudKeyManager.hasLocalUnlockedDek(uid)) {
      val remoteTxs = remoteDataSource.fetchEncryptedRecordsDelta(
        uid = uid,
        collectionName = SyncEntityTypes.firestoreCollectionFor(SyncEntityTypes.TRANSACTION),
        sinceClientUpdatedAtMillis = 0L,
        limit = 200L,
      ).getOrNull().orEmpty()
      hasRemote = remoteTxs.isNotEmpty()
      if (remoteTxs.isNotEmpty()) {
        val localDedupKeys = unmappedTxs.mapNotNull { it.deduplicationKey?.takeIf { k -> k.isNotBlank() } }.toSet()
        for (doc in remoteTxs) {
          val aad = CloudSyncEngine.buildPayloadAad(uid, doc.entityType, doc.recordId, doc.syncVersion)
          val plain = cloudKeyManager.decryptPayload(uid, doc.encryptedPayloadBase64, doc.payloadNonceBase64, aad).getOrNull()
          val remotePayload = plain?.let {
            runCatching { json.decodeFromString(CloudTransactionPayload.serializer(), it) }.getOrNull()
          }
          if (remotePayload?.deduplicationKey != null && remotePayload.deduplicationKey in localDedupKeys) {
            estDuplicates++
          }
        }
      }
    }

    val total = unmappedTxs.size + unmappedBudgets.size + unmappedLoans.size +
      unmappedGoals.size + unmappedInvestments.size + unmappedLists.size

    GuestMigrationPreview(
      transactionCount = unmappedTxs.size,
      budgetCount = unmappedBudgets.size,
      loanCount = unmappedLoans.size,
      savingsGoalCount = unmappedGoals.size,
      investmentCount = unmappedInvestments.size,
      shoppingListCount = unmappedLists.size,
      estimatedDuplicateCount = estDuplicates,
      hasRemoteData = hasRemote,
      totalRecordsToMigrate = total,
    )
  }

  suspend fun executeMigration(
    uid: String,
    accountEmail: String?,
    deduplicateWithCloud: Boolean,
  ): Result<GuestMigrationOutcome> = runCatching {
    require(uid.isNotBlank() && uid != "GUEST") { "Sign in with Google first." }
    require(cloudKeyManager.hasLocalUnlockedDek(uid)) {
      "Unlock your Recovery Passphrase before migrating local data to encrypted Cloud Sync."
    }

    val deviceId = preferencesDataStore.getOrCreateDeviceId()
    val now = System.currentTimeMillis()
    val remoteDedupMap = mutableMapOf<String, Pair<String, Long>>() // dedupKey -> (recordId, syncVersion)

    if (deduplicateWithCloud) {
      val remoteTxs = remoteDataSource.fetchEncryptedRecordsDelta(
        uid = uid,
        collectionName = SyncEntityTypes.firestoreCollectionFor(SyncEntityTypes.TRANSACTION),
        sinceClientUpdatedAtMillis = 0L,
        limit = CloudSyncEngine.DELTA_PAGE_LIMIT,
      ).getOrNull().orEmpty()
      for (doc in remoteTxs) {
        if (doc.isDeleted) continue
        val aad = CloudSyncEngine.buildPayloadAad(uid, doc.entityType, doc.recordId, doc.syncVersion)
        val plain = cloudKeyManager.decryptPayload(uid, doc.encryptedPayloadBase64, doc.payloadNonceBase64, aad).getOrNull()
        val parsed = plain?.let {
          runCatching { json.decodeFromString(CloudTransactionPayload.serializer(), it) }.getOrNull()
        }
        val dKey = parsed?.deduplicationKey
        if (!dKey.isNullOrBlank()) {
          remoteDedupMap[dKey] = doc.recordId to doc.syncVersion
        }
      }
    }

    var enqueuedCount = 0
    var deduplicatedCount = 0

    database.withTransaction {
      val syncDao = database.syncDao()

      suspend fun enqueueIfUnmapped(entityType: String, localId: String, txDedupKey: String? = null) {
        val existingMap = syncDao.findMapByLocalId(uid, entityType, localId)
        if (existingMap != null) return

        // If a transaction shares an exact deduplicationKey with an already-synced cloud record, bind instead of duplicating
        val dedupMatch = if (deduplicateWithCloud && !txDedupKey.isNullOrBlank()) {
          remoteDedupMap[txDedupKey]
        } else null

        if (dedupMatch != null) {
          val (remoteRecordId, remoteVersion) = dedupMatch
          syncDao.upsertMap(
            SyncEntityMapEntity(
              ownerUid = uid,
              entityType = entityType,
              localId = localId,
              recordId = remoteRecordId,
              syncVersion = remoteVersion,
              lastSyncedVersion = remoteVersion,
              lastSyncedAtEpochMillis = now,
              isDirty = false,
              isDeleted = false,
              updatedByDeviceId = deviceId,
              updatedAtEpochMillis = now,
            ),
          )
          deduplicatedCount++
          return
        }

        val recordId = mapper.deterministicRecordId(entityType, localId)
        val payloadJson = mapper.serializeEntity(entityType, localId, database, uid, deviceId) ?: return
        syncDao.upsertMap(
          SyncEntityMapEntity(
            ownerUid = uid,
            entityType = entityType,
            localId = localId,
            recordId = recordId,
            syncVersion = 1L,
            lastSyncedVersion = 0L,
            lastSyncedAtEpochMillis = 0L,
            isDirty = true,
            isDeleted = false,
            updatedByDeviceId = deviceId,
            updatedAtEpochMillis = now,
          ),
        )
        syncDao.upsertOutboxItem(
          SyncOutboxEntity(
            idempotencyKey = "$uid:$entityType:$recordId",
            ownerUid = uid,
            entityType = entityType,
            localId = localId,
            recordId = recordId,
            operation = SyncOperationType.UPSERT.name,
            payloadJson = payloadJson,
            baseSyncVersion = 0L,
            attemptCount = 0,
            nextRetryAtEpochMillis = now,
            lastError = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
          ),
        )
        enqueuedCount++
      }

      // Migrate in parent-before-child order so FK recordIds resolve deterministically
      database.categoryDao().getAllCategoriesOnce()
        .filter { !it.isSystemDefault }
        .forEach { enqueueIfUnmapped(SyncEntityTypes.CATEGORY, it.id.toString()) }

      database.currencyDao().getAllCurrenciesOnce()
        .filter { it.isActiveInWallet || it.cachedRateToBase != null }
        .forEach { enqueueIfUnmapped(SyncEntityTypes.CURRENCY, it.code) }

      database.appSettingsDao().getSettingsOnce()?.let {
        enqueueIfUnmapped(SyncEntityTypes.APP_SETTINGS, "1")
      }

      database.accountDao().getAllAccountsOnce().forEach { account ->
        database.accountDao().updateAccount(
          account.copy(
            isGuestLocal = false,
            googleAccountEmail = accountEmail ?: account.googleAccountEmail,
          ),
        )
        enqueueIfUnmapped(SyncEntityTypes.ACCOUNT, account.id.toString())
      }

      database.loanDao().getAllLoansOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.LOAN, it.id.toString()) }
      database.loanDao().getAllEmiPaymentsOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.EMI_PAYMENT, it.id.toString()) }
      database.savingsGoalDao().getAllGoalsOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.SAVINGS_GOAL, it.id.toString()) }
      database.budgetDao().getAllBudgetsOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.BUDGET, it.id.toString()) }
      database.investmentDao().getAllInvestmentsOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.INVESTMENT, it.id.toString()) }
      database.transactionDao().getAllTransactionsOnce().forEach { tx ->
        enqueueIfUnmapped(SyncEntityTypes.TRANSACTION, tx.id.toString(), tx.deduplicationKey)
      }
      database.shoppingDao().getAllShoppingListsOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.SHOPPING_LIST, it.id.toString()) }
      database.shoppingDao().getAllShoppingItemsOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.SHOPPING_ITEM, it.id.toString()) }
      database.shoppingDao().getAllShopPricesOnce().forEach { enqueueIfUnmapped(SyncEntityTypes.SHOPPING_SHOP_PRICE, it.id.toString()) }
    }

    preferencesDataStore.setGuestMigrationCompleted(uid, true)

    var syncedNow = 0
    if (preferencesDataStore.isFirestoreSyncEnabled(uid)) {
      cloudSyncEngine.syncNow(uid).onSuccess { summary ->
        syncedNow = summary.pushedCount + summary.pulledCount
      }
    }

    GuestMigrationOutcome(
      enqueuedRecords = enqueuedCount,
      deduplicatedMatches = deduplicatedCount,
      syncedNowCount = syncedNow,
    )
  }
}
