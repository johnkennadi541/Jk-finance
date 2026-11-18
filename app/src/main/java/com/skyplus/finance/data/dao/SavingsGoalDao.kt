package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.skyplus.finance.data.entities.SavingsGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavingsGoalDao {
  @Query("SELECT * FROM savings_goals ORDER BY isEmergencyFund DESC, createdAtEpochMillis ASC")
  fun observeAllGoals(): Flow<List<SavingsGoalEntity>>

  @Query("SELECT * FROM savings_goals ORDER BY id ASC")
  suspend fun getAllGoalsOnce(): List<SavingsGoalEntity>

  @Query("SELECT * FROM savings_goals WHERE id = :id")
  suspend fun getGoalById(id: Long): SavingsGoalEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertGoal(goal: SavingsGoalEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllGoals(goals: List<SavingsGoalEntity>)

  @Update
  suspend fun updateGoal(goal: SavingsGoalEntity)

  @Delete
  suspend fun deleteGoal(goal: SavingsGoalEntity)

  @Query("DELETE FROM savings_goals")
  suspend fun deleteAll()
}
