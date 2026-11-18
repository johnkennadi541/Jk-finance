package com.skyplus.finance.data.cloud

import androidx.room.withTransaction
import com.skyplus.finance.data.cloud.dto.FirestoreEncryptedEnvelope
import com.skyplus.finance.data.cloud.dto.FirestorePrivateProfileDto
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.SyncConflictEntity
import com.skyplus.finance.data.entities.SyncConflictStatus
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.SyncEntityTypes
import com.skyplus.finance.data.entities.SyncOperationType
import com.skyplus.finance.data.entities.SyncOutboxEntity
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.CloudKeyManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RecoveryUnlockOutcome {
  INITIALIZED_NEW,
  UNLOCKED_EXISTING,
}

data class SyncRunSummary(
  val pushedCount: Int,
  val pulledCount: Int,
  val conflictCount: Int,
  val failedOutboxCount: Int,
  val timestampMillis: Long,
)

@Singleton
class CloudSyncEngine @Inject constructor(
  private val database: SkyPlusDatabase,
  private val remoteDataSource: CloudSyncRemoteDataSource,
  private val cloudKeyManager: CloudKeyManager,
  private val preferencesDataStore: AppPreferencesDataStore,
  private val mapper: CloudSyncMapper,
  private val outboxMutationRecorder: OutboxMutationRecorder,
) {
  companion object {
    const val DELTA_PAGE_LIMIT = 500L
    const val MAX_RETRY_ATTEMPTS = 10

    fun buildPayloadAad(
      uid: String,
      entityType: String,
      recordId: String,
      syncVersion: Long,
    ): String = "$uid|$entityType|$recordId|$syncVersion"
  }

  private val syncMutex = Mutex()
  private val orderPriority: Map<String, Int> = SyncEntityTypes.orderedCollections
    .mapIndexed { index, pair -> pair.first to index }
    .toMap()

  fun observePendingOutboxCount(uid: String): Flow<Int> =
    database.syncDao().observePendingOutboxCount(uid)

  fun observePendingConflicts(uid: String): Flow<List<SyncConflictEntity>> =
    database.syncDao().observePendingConflicts(uid)

  suspend fun checkRemoteRecoveryMetadataExists(uid: String): Result<Boolean> {
    if (uid.isBlank() || uid == "GUEST") return Result.success(false)
    return remoteDataSource.getRecoveryMetadata(uid).map { it != null }
  }

  suspend fun setupOrUnlockEncryption(
    uid: String,
    passphrase: CharArray,
  ): Result<RecoveryUnlockOutcome> = runCatching {
    require(uid.isNotBlank() && uid != "GUEST") { "Sign in with Google first." }
    val deviceId = preferencesDataStore.getOrCreateDeviceId()
    val existingEnvelope = remoteDataSource.getRecoveryMetadata(uid).getOrThrow()
    if (existingEnvelope == null) {
      val created = cloudKeyManager.setupRecoveryPassphrase(
        uid = uid,
        passphrase = passphrase,
        deviceId = deviceId,
      ).getOrThrow()
      remoteDataSource.upsertRecoveryMetadata(created).getOrThrow()
      RecoveryUnlockOutcome.INITIALIZED_NEW
    } else {
      cloudKeyManager.unlockDekWithPassphrase(
        uid = uid,
        envelope = existingEnvelope,
        passphrase = passphrase,
      ).getOrThrow()
      RecoveryUnlockOutcome.UNLOCKED_EXISTING
    }
  }

  suspend fun rotateRecoveryPassphrase(
    uid: String,
    oldPassphrase: CharArray,
    newPassphrase: CharArray,
  ): Result<Unit> = runCatching {
    require(uid.isNotBlank() && uid != "GUEST") { "Sign in with Google first." }
    val existing = remoteDataSource.getRecoveryMetadata(uid).getOrThrow()
      ?: error("Recovery encryption is not initialized for this account yet.")
    val deviceId = preferencesDataStore.getOrCreateDeviceId()
    val rotated = cloudKeyManager.rotateRecoveryPassphrase(
      uid = uid,
      currentEnvelope = existing,
      oldPassphrase = oldPassphrase,
      newPassphrase = newPassphrase,
      deviceId = deviceId,
    ).getOrThrow()
    remoteDataSource.upsertRecoveryMetadata(rotated).getOrThrow()
  }

  suspend fun enableFirestoreSync(
    uid: String,
    email: String?,
    displayName: String?,
  ): Result<Unit> = runCatching {
    require(uid.isNotBlank() && uid != "GUEST") { "Google Sign-In is required before enabling Cloud Sync." }
    require(cloudKeyManager.hasLocalUnlockedDek(uid)) {
      "Set up or unlock your Recovery Passphrase before enabling Firestore Cloud Sync."
    }
    preferencesDataStore.setFirestoreSyncEnabled(uid, true)
    val deviceId = preferencesDataStore.getOrCreateDeviceId()
    val driveEnabled = preferencesDataStore.isDriveAutoBackupEnabled(uid)
    val driveWifiOnly = preferencesDataStore.isDriveWifiOnly(uid)
    remoteDataSource.upsertPrivateProfile(
      FirestorePrivateProfileDto(
        uid = uid,
        email = email,
        displayName = displayName,
        cloudSyncEnabled = true,
        driveBackupEnabled = driveEnabled,
        driveWifiOnly = driveWifiOnly,
        deviceId = deviceId,
        schemaVersion = 1,
      ),
    ).getOrThrow()
    preferencesDataStore.recordSyncState(uid, 0L, "Cloud Sync enabled (encrypted)")
  }

  suspend fun disableFirestoreSync(
    uid: String,
    email: String? = null,
    displayName: String? = null,
  ) {
    if (uid.isBlank() || uid == "GUEST") return
    // Disabling Cloud Sync NEVER deletes local Room financial records.
    preferencesDataStore.setFirestoreSyncEnabled(uid, false)
    preferencesDataStore.recordSyncState(uid, 0L, "Cloud Sync paused (local data intact)")
    val deviceId = preferencesDataStore.getOrCreateDeviceId()
    val driveEnabled = preferencesDataStore.isDriveAutoBackupEnabled(uid)
    val driveWifiOnly = preferencesDataStore.isDriveWifiOnly(uid)
    runCatching {
      remoteDataSource.upsertPrivateProfile(
        FirestorePrivateProfileDto(
          uid = uid,
          email = email,
          displayName = displayName,
          cloudSyncEnabled = false,
          driveBackupEnabled = driveEnabled,
          driveWifiOnly = driveWifiOnly,
          deviceId = deviceId,
          schemaVersion = 1,
        ),
      )
    }
  }

  suspend fun syncNow(uid: String): Result<SyncRunSummary> = syncMutex.withLock {
    runCatching {
      require(uid.isNotBlank() && uid != "GUEST") {
        "Guest Mode stays 100% offline. Sign in with Google to use optional Cloud Sync."
      }
      require(preferencesDataStore.isFirestoreSyncEnabled(uid)) {
        "Firestore Cloud Sync is currently disabled for this account."
      }
      require(cloudKeyManager.hasLocalUnlockedDek(uid)) {
        "Recovery encryption key is locked. Enter your Recovery Passphrase to unlock sync."
      }

      val (pushed, pushConflicts, failed) = pushOutboxInternal(uid)
      val (pulled, pullConflicts) = pullRemoteDeltasInternal(uid)
      val totalConflicts = pushConflicts + pullConflicts
      val now = System.currentTimeMillis()
      val statusMsg = buildString {
        append("Synced ($pushed up, $pulled down)")
        if (totalConflicts > 0) append(" • $totalConflicts conflict(s) pending")
        if (failed > 0) append(" • $failed queued for retry")
      }
      preferencesDataStore.recordSyncState(uid, now, statusMsg)
      SyncRunSummary(
        pushedCount = pushed,
        pulledCount = pulled,
        conflictCount = totalConflicts,
        failedOutboxCount = failed,
        timestampMillis = now,
      )
    }.onFailure { err ->
      if (uid.isNotBlank() && uid != "GUEST") {
        preferencesDataStore.recordSyncState(uid, 0L, "Sync error: ${err.message ?: "Retry later"}")
      }
    }
  }

  suspend fun pushOutbox(uid: String): Result<Int> = syncMutex.withLock {
    runCatching {
      require(uid.isNotBlank() && uid != "GUEST") { "Authenticated user required." }
      require(preferencesDataStore.isFirestoreSyncEnabled(uid)) { "Firestore Cloud Sync is disabled." }
      require(cloudKeyManager.hasLocalUnlockedDek(uid)) { "Recovery encryption key is locked." }
      pushOutboxInternal(uid).first
    }
  }

  suspend fun pullRemoteDeltas(uid: String): Result<Int> = syncMutex.withLock {
    runCatching {
      require(uid.isNotBlank() && uid != "GUEST") { "Authenticated user required." }
      require(preferencesDataStore.isFirestoreSyncEnabled(uid)) { "Firestore Cloud Sync is disabled." }
      require(cloudKeyManager.hasLocalUnlockedDek(uid)) { "Recovery encryption key is locked." }
      pullRemoteDeltasInternal(uid).first
    }
  }

  private suspend fun pushOutboxInternal(uid: String): Triple<Int, Int, Int> {
    val now = System.currentTimeMillis()
    val deviceId = preferencesDataStore.getOrCreateDeviceId()
    val syncDao = database.syncDao()
    val dueItems = syncDao.getDueOutboxItems(uid, now)
      .filter { it.attemptCount < MAX_RETRY_ATTEMPTS }
      .sortedWith(compareBy({ orderPriority[it.entityType] ?: Int.MAX_VALUE }, { it.createdAtEpochMillis }))

    var pushed = 0
    var conflicts = 0
    var failed = 0

    for (item in dueItems) {
      val result = runCatching {
        val collection = SyncEntityTypes.firestoreCollectionFor(item.entityType)
        val localMap = syncDao.findMapByRecordId(uid, item.entityType, item.recordId)
        val remoteDoc = remoteDataSource.getEncryptedRecord(uid, collection, item.recordId).getOrThrow()

        if (remoteDoc != null &&
          remoteDoc.syncVersion > (localMap?.lastSyncedVersion ?: item.baseSyncVersion) &&
          remoteDoc.updatedByDeviceId != deviceId
        ) {
          val remotePlain = decryptRemoteEnvelope(uid, remoteDoc)
          recordConflict(
            ownerUid = uid,
            entityType = item.entityType,
            localId = item.localId,
            recordId = item.recordId,
            localPayloadJson = item.payloadJson,
            remoteDoc = remoteDoc,
            remotePlaintextJson = remotePlain,
          )
          syncDao.deleteOutboxItemById(item.id)
          conflicts++
          return@runCatching
        }

        val isDelete = item.operation == SyncOperationType.DELETE.name
        val latestPayloadJson = if (isDelete) {
          "{}"
        } else {
          mapper.serializeEntity(
            entityType = item.entityType,
            localId = item.localId,
            database = database,
            ownerUid = uid,
            deviceId = deviceId,
          ) ?: item.payloadJson
        }

        val nextVersion = maxOf(
          localMap?.syncVersion ?: 1L,
          (remoteDoc?.syncVersion ?: 0L) + 1L,
        )
        val aad = buildPayloadAad(uid, item.entityType, item.recordId, nextVersion)
        val encrypted = cloudKeyManager.encryptPayload(uid, latestPayloadJson, aad).getOrThrow()
        val updateMillis = System.currentTimeMillis()

        remoteDataSource.upsertEncryptedRecord(
          collectionName = collection,
          envelope = FirestoreEncryptedEnvelope(
            recordId = item.recordId,
            ownerUid = uid,
            entityType = item.entityType,
            encryptedPayloadBase64 = encrypted.ciphertextBase64,
            payloadNonceBase64 = encrypted.nonceBase64,
            schemaVersion = 3,
            syncVersion = nextVersion,
            updatedByDeviceId = deviceId,
            isDeleted = isDelete,
            clientUpdatedAtMillis = updateMillis,
          ),
        ).getOrThrow()

        database.withTransaction {
          syncDao.upsertMap(
            SyncEntityMapEntity(
              id = localMap?.id ?: 0L,
              ownerUid = uid,
              entityType = item.entityType,
              localId = item.localId,
              recordId = item.recordId,
              syncVersion = nextVersion,
              lastSyncedVersion = nextVersion,
              lastSyncedAtEpochMillis = updateMillis,
              isDirty = false,
              isDeleted = isDelete,
              updatedByDeviceId = deviceId,
              updatedAtEpochMillis = updateMillis,
            ),
          )
          syncDao.deleteOutboxItemById(item.id)
        }
        pushed++
      }

      result.onFailure { err ->
        failed++
        val nextAttempt = item.attemptCount + 1
        val backoffMillis = (30_000L shl (nextAttempt - 1).coerceAtMost(7)).coerceAtMost(3_600_000L)
        syncDao.upsertOutboxItem(
          item.copy(
            attemptCount = nextAttempt,
            nextRetryAtEpochMillis = System.currentTimeMillis() + backoffMillis,
            lastError = err.message ?: "Network error",
            updatedAtEpochMillis = System.currentTimeMillis(),
          ),
        )
      }
    }

    return Triple(pushed, conflicts, failed)
  }

  private suspend fun pullRemoteDeltasInternal(uid: String): Pair<Int, Int> {
    val syncDao = database.syncDao()
    val lastSyncTs = preferencesDataStore.getLastSyncTimestamp(uid)
    // Subtract small skew window (5s) so concurrent clock skew never skips updates, while version check keeps pull idempotent
    val deltaSince = if (lastSyncTs > 5_000L) lastSyncTs - 5_000L else 0L
    val deviceId = preferencesDataStore.getOrCreateDeviceId()

    var applied = 0
    var conflicts = 0

    for ((entityType, collectionName) in SyncEntityTypes.orderedCollections) {
      val docs = remoteDataSource.fetchEncryptedRecordsDelta(
        uid = uid,
        collectionName = collectionName,
        sinceClientUpdatedAtMillis = deltaSince,
        limit = DELTA_PAGE_LIMIT,
      ).getOrThrow()
        .sortedBy { it.clientUpdatedAtMillis }

      for (doc in docs) {
        if (doc.ownerUid != uid) continue
        val localMap = syncDao.findMapByRecordId(uid, doc.entityType, doc.recordId)

        if (localMap != null && !localMap.isDirty && doc.syncVersion <= localMap.lastSyncedVersion) {
          continue
        }

        val remotePlainJson = decryptRemoteEnvelope(uid, doc)

        if (localMap != null && localMap.isDirty && doc.syncVersion > localMap.lastSyncedVersion) {
          val localPlainJson = if (localMap.isDeleted) {
            "{}"
          } else {
            mapper.serializeEntity(
              entityType = doc.entityType,
              localId = localMap.localId,
              database = database,
              ownerUid = uid,
              deviceId = deviceId,
            ) ?: "{}"
          }
          recordConflict(
            ownerUid = uid,
            entityType = doc.entityType,
            localId = localMap.localId,
            recordId = doc.recordId,
            localPayloadJson = localPlainJson,
            remoteDoc = doc,
            remotePlaintextJson = remotePlainJson,
          )
          conflicts++
          continue
        }

        if (localMap == null && doc.isDeleted) {
          continue
        }

        database.withTransaction {
          mapper.applyRemotePayload(
            ownerUid = uid,
            entityType = doc.entityType,
            recordId = doc.recordId,
            payloadJson = remotePlainJson,
            isDeleted = doc.isDeleted,
            database = database,
            syncVersion = doc.syncVersion,
            updatedByDeviceId = doc.updatedByDeviceId,
            updatedAtEpochMillis = doc.clientUpdatedAtMillis,
            asCopyKeepBoth = false,
          )
        }
        applied++
      }
    }

    return applied to conflicts
  }

  private fun decryptRemoteEnvelope(uid: String, doc: RemoteEncryptedDocument): String {
    if (doc.isDeleted && doc.encryptedPayloadBase64.isBlank()) return "{}"
    val aad = buildPayloadAad(uid, doc.entityType, doc.recordId, doc.syncVersion)
    return cloudKeyManager.decryptPayload(
      uid = uid,
      ciphertextBase64 = doc.encryptedPayloadBase64,
      nonceBase64 = doc.payloadNonceBase64,
      aad = aad,
    ).getOrThrow()
  }

  private suspend fun recordConflict(
    ownerUid: String,
    entityType: String,
    localId: String,
    recordId: String,
    localPayloadJson: String,
    remoteDoc: RemoteEncryptedDocument,
    remotePlaintextJson: String,
  ) {
    val now = System.currentTimeMillis()
    val localSummary = mapper.buildSummary(entityType, localPayloadJson)
    val remoteSummary = if (remoteDoc.isDeleted) {
      "$entityType (Deleted on ${remoteDoc.updatedByDeviceId})"
    } else {
      mapper.buildSummary(entityType, remotePlaintextJson)
    }
    database.syncDao().upsertConflict(
      SyncConflictEntity(
        ownerUid = ownerUid,
        entityType = entityType,
        localId = localId,
        recordId = recordId,
        localPayloadJson = localPayloadJson,
        remotePayloadJson = remotePlaintextJson,
        localSummary = localSummary,
        remoteSummary = remoteSummary,
        localUpdatedAtEpochMillis = now,
        remoteUpdatedAtEpochMillis = remoteDoc.clientUpdatedAtMillis,
        remoteSyncVersion = remoteDoc.syncVersion,
        remoteDeviceId = remoteDoc.updatedByDeviceId,
        status = SyncConflictStatus.PENDING.name,
        createdAtEpochMillis = now,
      ),
    )
  }

  suspend fun resolveConflict(
    conflictId: Long,
    resolution: SyncConflictStatus,
  ): Result<Unit> = syncMutex.withLock {
    runCatching {
      require(resolution != SyncConflictStatus.PENDING) { "Select a conflict resolution option." }
      val syncDao = database.syncDao()
      val conflict = syncDao.getConflictById(conflictId)
        ?: error("Sync conflict record not found.")
      val uid = conflict.ownerUid
      val deviceId = preferencesDataStore.getOrCreateDeviceId()
      val now = System.currentTimeMillis()

      when (resolution) {
        SyncConflictStatus.RESOLVED_USE_CLOUD -> {
          database.withTransaction {
            val isRemoteDelete = conflict.remotePayloadJson.isBlank() || conflict.remotePayloadJson == "{}"
            mapper.applyRemotePayload(
              ownerUid = uid,
              entityType = conflict.entityType,
              recordId = conflict.recordId,
              payloadJson = conflict.remotePayloadJson,
              isDeleted = isRemoteDelete,
              database = database,
              syncVersion = conflict.remoteSyncVersion,
              updatedByDeviceId = conflict.remoteDeviceId,
              updatedAtEpochMillis = conflict.remoteUpdatedAtEpochMillis,
              asCopyKeepBoth = false,
            )
            // Remove any pending stale outbox entry for this recordId
            val staleOutbox = syncDao.getAllOutboxItemsForOwner(uid)
              .firstOrNull { it.entityType == conflict.entityType && it.recordId == conflict.recordId }
            if (staleOutbox != null) {
              syncDao.deleteOutboxItemById(staleOutbox.id)
            }
            syncDao.markConflictResolved(conflict.id, resolution.name, now)
          }
        }

        SyncConflictStatus.RESOLVED_KEEP_LOCAL -> {
          database.withTransaction {
            val map = syncDao.findMapByRecordId(uid, conflict.entityType, conflict.recordId)
            val nextVersion = maxOf(map?.syncVersion ?: 1L, conflict.remoteSyncVersion) + 1L
            val isLocalDeleted = conflict.localPayloadJson.isBlank() || conflict.localPayloadJson == "{}"
            syncDao.upsertMap(
              SyncEntityMapEntity(
                id = map?.id ?: 0L,
                ownerUid = uid,
                entityType = conflict.entityType,
                localId = conflict.localId,
                recordId = conflict.recordId,
                syncVersion = nextVersion,
                lastSyncedVersion = conflict.remoteSyncVersion,
                lastSyncedAtEpochMillis = map?.lastSyncedAtEpochMillis ?: 0L,
                isDirty = true,
                isDeleted = isLocalDeleted,
                updatedByDeviceId = deviceId,
                updatedAtEpochMillis = now,
              ),
            )
            val idempotencyKey = "$uid:${conflict.entityType}:${conflict.recordId}"
            syncDao.upsertOutboxItem(
              SyncOutboxEntity(
                idempotencyKey = idempotencyKey,
                ownerUid = uid,
                entityType = conflict.entityType,
                localId = conflict.localId,
                recordId = conflict.recordId,
                operation = if (isLocalDeleted) SyncOperationType.DELETE.name else SyncOperationType.UPSERT.name,
                payloadJson = conflict.localPayloadJson,
                baseSyncVersion = conflict.remoteSyncVersion,
                attemptCount = 0,
                nextRetryAtEpochMillis = now,
                lastError = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
              ),
            )
            syncDao.markConflictResolved(conflict.id, resolution.name, now)
          }
        }

        SyncConflictStatus.RESOLVED_KEEP_BOTH -> {
          database.withTransaction {
            // Keep local on original recordId queued to supersede remote
            val map = syncDao.findMapByRecordId(uid, conflict.entityType, conflict.recordId)
            val nextVersion = maxOf(map?.syncVersion ?: 1L, conflict.remoteSyncVersion) + 1L
            val isLocalDeleted = conflict.localPayloadJson.isBlank() || conflict.localPayloadJson == "{}"
            syncDao.upsertMap(
              SyncEntityMapEntity(
                id = map?.id ?: 0L,
                ownerUid = uid,
                entityType = conflict.entityType,
                localId = conflict.localId,
                recordId = conflict.recordId,
                syncVersion = nextVersion,
                lastSyncedVersion = conflict.remoteSyncVersion,
                lastSyncedAtEpochMillis = map?.lastSyncedAtEpochMillis ?: 0L,
                isDirty = true,
                isDeleted = isLocalDeleted,
                updatedByDeviceId = deviceId,
                updatedAtEpochMillis = now,
              ),
            )
            if (!isLocalDeleted) {
              syncDao.upsertOutboxItem(
                SyncOutboxEntity(
                  idempotencyKey = "$uid:${conflict.entityType}:${conflict.recordId}",
                  ownerUid = uid,
                  entityType = conflict.entityType,
                  localId = conflict.localId,
                  recordId = conflict.recordId,
                  operation = SyncOperationType.UPSERT.name,
                  payloadJson = conflict.localPayloadJson,
                  baseSyncVersion = conflict.remoteSyncVersion,
                  attemptCount = 0,
                  nextRetryAtEpochMillis = now,
                  lastError = null,
                  createdAtEpochMillis = now,
                  updatedAtEpochMillis = now,
                ),
              )
            }

            // Also import cloud payload as a brand-new record if not deleted
            val isRemoteDeleted = conflict.remotePayloadJson.isBlank() || conflict.remotePayloadJson == "{}"
            if (!isRemoteDeleted) {
              val newRecordId = UUID.randomUUID().toString()
              val newLocalId = mapper.applyRemotePayload(
                ownerUid = uid,
                entityType = conflict.entityType,
                recordId = newRecordId,
                payloadJson = conflict.remotePayloadJson,
                isDeleted = false,
                database = database,
                syncVersion = 1L,
                updatedByDeviceId = deviceId,
                updatedAtEpochMillis = now,
                asCopyKeepBoth = true,
              )
              outboxMutationRecorder.recordMutation(
                entityType = conflict.entityType,
                localId = newLocalId,
                operation = SyncOperationType.UPSERT,
                explicitUid = uid,
                bypassSyncToggleCheck = true,
              )
            }
            syncDao.markConflictResolved(conflict.id, resolution.name, now)
          }
        }

        SyncConflictStatus.PENDING -> Unit
      }
    }
  }
}
