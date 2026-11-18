package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(
  tableName = "budgets",
  foreignKeys = [
    ForeignKey(
      entity = CategoryEntity::class,
      parentColumns = ["id"],
      childColumns = ["categoryId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["categoryId", "yearMonth", "currencyCode"], unique = true),
    Index(value = ["yearMonth"]),
  ],
)
data class BudgetEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val categoryId: Long,
  val yearMonth: String,
  val limitAmount: BigDecimal,
  val currencyCode: String,
  val rolloverEnabled: Boolean = false,
  val rolloverAmount: BigDecimal = BigDecimal.ZERO,
  val warningThresholdPercent: Int = 80,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)
