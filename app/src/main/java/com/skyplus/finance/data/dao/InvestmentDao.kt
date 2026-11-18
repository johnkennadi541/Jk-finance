package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.skyplus.finance.data.entities.InvestmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestmentDao {
  @Query("SELECT * FROM investments ORDER BY updatedAtEpochMillis DESC, id DESC")
  fun observeInvestments(): Flow<List<InvestmentEntity>>

  @Query("SELECT * FROM investments ORDER BY id ASC")
  suspend fun getAllInvestmentsOnce(): List<InvestmentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertInvestment(investment: InvestmentEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllInvestments(investments: List<InvestmentEntity>)

  @Query("DELETE FROM investments WHERE id = :investmentId")
  suspend fun deleteInvestmentById(investmentId: Long)

  @Query("DELETE FROM investments")
  suspend fun deleteAllInvestments()
}
