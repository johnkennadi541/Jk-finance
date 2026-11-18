package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.skyplus.finance.data.entities.SyncConflictEntity
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
  // --- Sync Entity Map ---
  @Query(
    "SELECT * FROM sync_entity_map WHERE ownerUid = :ownerUid AND entityType = :entityType AND localId = :localId LIMIT 1",
  )
  suspend fun findMapByLocalId(
    ownerUid: String,
    entityType: String,
    localId: String,
  ): SyncEntityMapEntity?

  @Query(
    "SELECT * FROM sync_entity_map WHERE ownerUid = :ownerUid AND entityType = :entityType AND recordId = :recordId LIMIT 1",
  )
  suspend fun findMapByRecordId(
    ownerUid: String,
    entityType: String,
    recordId: String,
  ): SyncEntityMapEntity?

  @Query("SELECT * FROM sync_entity_map WHERE ownerUid = :ownerUid")
  suspend fun getAllMapsForOwner(ownerUid: String): List<SyncEntityMapEntity>

  @Query("SELECT * FROM sync_entity_map")
  suspend fun getAllMapsOnce(): List<SyncEntityMapEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMap(entity: SyncEntityMapEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllMaps(entities: List<SyncEntityMapEntity>)

  @Query("DELETE FROM sync_entity_map WHERE ownerUid = :ownerUid")
  suspend fun deleteMapsForOwner(ownerUid: String)

  @Query("DELETE FROM sync_entity_map")
  suspend fun deleteAllMaps()

  // --- Sync Outbox ---
  @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerUid = :ownerUid")
  fun observePendingOutboxCount(ownerUid: String): Flow<Int>

  @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerUid = :ownerUid")
  suspend fun getPendingOutboxCountOnce(ownerUid: String): Int

  @Query(
    "SELECT * FROM sync_outbox WHERE ownerUid = :ownerUid AND nextRetryAtEpochMillis <= :nowEpochMillis ORDER BY createdAtEpochMillis ASC",
  )
  suspend fun getDueOutboxItems(
    ownerUid: String,
    nowEpochMillis: Long,
  ): List<SyncOutboxEntity>

  @Query("SELECT * FROM sync_outbox WHERE ownerUid = :ownerUid ORDER BY createdAtEpochMillis ASC")
  suspend fun getAllOutboxItemsForOwner(ownerUid: String): List<SyncOutboxEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertOutboxItem(item: SyncOutboxEntity): Long

  @Query("DELETE FROM sync_outbox WHERE id = :id")
  suspend fun deleteOutboxItemById(id: Long)

  @Query("DELETE FROM sync_outbox WHERE ownerUid = :ownerUid")
  suspend fun deleteOutboxForOwner(ownerUid: String)

  @Query("DELETE FROM sync_outbox")
  suspend fun deleteAllOutbox()

  // --- Sync Conflicts ---
  @Query("SELECT * FROM sync_conflicts WHERE ownerUid = :ownerUid AND status = 'PENDING' ORDER BY createdAtEpochMillis DESC")
  fun observePendingConflicts(ownerUid: String): Flow<List<SyncConflictEntity>>

  @Query("SELECT COUNT(*) FROM sync_conflicts WHERE ownerUid = :ownerUid AND status = 'PENDING'")
  fun observePendingConflictCount(ownerUid: String): Flow<Int>

  @Query("SELECT * FROM sync_conflicts WHERE id = :conflictId LIMIT 1")
  suspend fun getConflictById(conflictId: Long): SyncConflictEntity?

  @Query("SELECT * FROM sync_conflicts WHERE ownerUid = :ownerUid AND status = 'PENDING'")
  suspend fun getPendingConflictsOnce(ownerUid: String): List<SyncConflictEntity>

  @Query("SELECT * FROM sync_conflicts WHERE ownerUid = :ownerUid AND status = 'PENDING' ORDER BY createdAtEpochMillis DESC")
  suspend fun getPendingConflicts(ownerUid: String): List<SyncConflictEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertConflict(conflict: SyncConflictEntity): Long

  @Query(
    "UPDATE sync_conflicts SET status = :status, resolvedAtEpochMillis = :resolvedAtEpochMillis WHERE id = :conflictId",
  )
  suspend fun markConflictResolved(
    conflictId: Long,
    status: String,
    resolvedAtEpochMillis: Long,
  )

  @Query("DELETE FROM sync_conflicts WHERE ownerUid = :ownerUid")
  suspend fun deleteConflictsForOwner(ownerUid: String)

  @Query("DELETE FROM sync_conflicts")
  suspend fun deleteAllConflicts()
}
