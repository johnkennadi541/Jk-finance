package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.skyplus.finance.domain.model.CategoryType

@Entity(
  tableName = "categories",
  indices = [
    Index(value = ["nameKey", "type"], unique = true),
  ],
)
data class CategoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val nameKey: String,
  val displayNameEn: String,
  val displayNameTa: String,
  val type: CategoryType,
  val iconKey: String,
  val colorHex: Long,
  val isSystemDefault: Boolean = true,
  val sortOrder: Int = 0,
)
