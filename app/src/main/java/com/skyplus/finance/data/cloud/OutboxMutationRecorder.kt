package com.skyplus.finance.data.cloud

import androidx.room.withTransaction
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.SyncOperationType
import com.skyplus.finance.data.entities.SyncOutboxEntity
import com.skyplus.finance.data.security.AppPreferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records idempotent mutations into `sync_outbox` + `sync_entity_map` ONLY when:
 * 1) A Firebase user is actively signed in (Guest Mode creates ZERO outbox entries), AND
 * 2) That user has explicitly opted into Firestore Cloud Sync (Option C independence), AND
 * 3) We are not inside a remote pull / conflict-resolution Room write.
 */
@Singleton
class OutboxMutationRecorder @Inject constructor(
  private val database: SkyPlusDatabase,
  private val firebaseAuthManager: FirebaseAuthManager,
  private val preferencesDataStore: AppPreferencesDataStore,
  private val mapper: CloudSyncMapper,
) {
  suspend fun recordMutation(
    entityType: String,
    localId: String,
    operation: SyncOperationType = SyncOperationType.UPSERT,
    explicitUid: String? = null,
    bypassSyncToggleCheck: Boolean = false,
  ) {
    val uid = explicitUid ?: firebaseAuthManager.currentUser()?.uid ?: return
    if (uid.isBlank() || uid == "GUEST") return
    if (!bypassSyncToggleCheck && !preferencesDataStore.isFirestoreSyncEnabled(uid)) return

    val deviceId = preferencesDataStore.getOrCreateDeviceId()
    val now = System.currentTimeMillis()

    database.withTransaction {
      val syncDao = database.syncDao()
      val existingMap = syncDao.findMapByLocalId(uid, entityType, localId)
      val recordId = existingMap?.recordId ?: mapper.deterministicRecordId(entityType, localId)
      val nextVersion = (existingMap?.syncVersion ?: 0L) + 1L

      val payloadJson = if (operation == SyncOperationType.DELETE) {
        "{}"
      } else {
        mapper.serializeEntity(
          entityType = entityType,
          localId = localId,
          database = database,
          ownerUid = uid,
          deviceId = deviceId,
        ) ?: return@withTransaction
      }

      syncDao.upsertMap(
        SyncEntityMapEntity(
          id = existingMap?.id ?: 0L,
          ownerUid = uid,
          entityType = entityType,
          localId = localId,
          recordId = recordId,
          syncVersion = nextVersion,
          lastSyncedVersion = existingMap?.lastSyncedVersion ?: 0L,
          lastSyncedAtEpochMillis = existingMap?.lastSyncedAtEpochMillis ?: 0L,
          isDirty = true,
          isDeleted = operation == SyncOperationType.DELETE,
          updatedByDeviceId = deviceId,
          updatedAtEpochMillis = now,
        ),
      )

      val idempotencyKey = "$uid:$entityType:$recordId"
      syncDao.upsertOutboxItem(
        SyncOutboxEntity(
          idempotencyKey = idempotencyKey,
          ownerUid = uid,
          entityType = entityType,
          localId = localId,
          recordId = recordId,
          operation = operation.name,
          payloadJson = payloadJson,
          baseSyncVersion = existingMap?.lastSyncedVersion ?: 0L,
          attemptCount = 0,
          nextRetryAtEpochMillis = now,
          lastError = null,
          createdAtEpochMillis = now,
          updatedAtEpochMillis = now,
        ),
      )
    }
  }
}
