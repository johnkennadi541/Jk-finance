package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.skyplus.finance.data.entities.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
  @Query("SELECT * FROM budgets ORDER BY yearMonth DESC, id ASC")
  fun observeAllBudgets(): Flow<List<BudgetEntity>>

  @Query("SELECT * FROM budgets ORDER BY yearMonth DESC, id ASC")
  suspend fun getAllBudgetsOnce(): List<BudgetEntity>

  @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth ORDER BY id ASC")
  fun observeBudgetsForMonth(yearMonth: String): Flow<List<BudgetEntity>>

  @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND yearMonth = :yearMonth AND currencyCode = :currencyCode LIMIT 1")
  suspend fun findBudget(categoryId: Long, yearMonth: String, currencyCode: String): BudgetEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(budget: BudgetEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(budgets: List<BudgetEntity>)

  @Update
  suspend fun update(budget: BudgetEntity)

  @Delete
  suspend fun delete(budget: BudgetEntity)

  @Query("DELETE FROM budgets WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM budgets")
  suspend fun deleteAll()
}
