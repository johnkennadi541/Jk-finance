package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.skyplus.finance.data.entities.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
  @Query("SELECT * FROM transactions ORDER BY transactionDateEpochDay DESC, createdAtEpochMillis DESC")
  fun observeAllTransactions(): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions ORDER BY transactionDateEpochDay DESC, createdAtEpochMillis DESC")
  suspend fun getAllTransactionsOnce(): List<TransactionEntity>

  @Query(
    "SELECT * FROM transactions WHERE transactionDateEpochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY transactionDateEpochDay DESC, createdAtEpochMillis DESC"
  )
  fun observeTransactionsInRange(startEpochDay: Long, endEpochDay: Long): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions ORDER BY transactionDateEpochDay DESC, createdAtEpochMillis DESC LIMIT :limit")
  fun observeRecentTransactions(limit: Int): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions WHERE id = :id")
  suspend fun getTransactionById(id: Long): TransactionEntity?

  @Query("SELECT * FROM transactions WHERE deduplicationKey = :key LIMIT 1")
  suspend fun findByDeduplicationKey(key: String): TransactionEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(transaction: TransactionEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(transactions: List<TransactionEntity>)

  @Update
  suspend fun update(transaction: TransactionEntity)

  @Delete
  suspend fun delete(transaction: TransactionEntity)

  @Query("DELETE FROM transactions WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM transactions")
  suspend fun deleteAll()
}
