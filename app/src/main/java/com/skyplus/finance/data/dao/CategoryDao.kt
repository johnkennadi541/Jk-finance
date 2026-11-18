package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.domain.model.CategoryType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
  @Query("SELECT * FROM categories ORDER BY type ASC, sortOrder ASC, id ASC")
  fun observeAllCategories(): Flow<List<CategoryEntity>>

  @Query("SELECT * FROM categories ORDER BY type ASC, sortOrder ASC, id ASC")
  suspend fun getAllCategoriesOnce(): List<CategoryEntity>

  @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder ASC, id ASC")
  fun observeCategoriesByType(type: CategoryType): Flow<List<CategoryEntity>>

  @Query("SELECT * FROM categories WHERE id = :id")
  suspend fun getCategoryById(id: Long): CategoryEntity?

  @Query("SELECT COUNT(*) FROM categories")
  suspend fun getCategoryCount(): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(categories: List<CategoryEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(category: CategoryEntity): Long
}
