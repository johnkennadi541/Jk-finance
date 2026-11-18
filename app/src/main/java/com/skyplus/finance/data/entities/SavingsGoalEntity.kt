package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.skyplus.finance.domain.model.SavingsGoalType
import java.math.BigDecimal

@Entity(
  tableName = "savings_goals",
  indices = [
    Index(value = ["isEmergencyFund"]),
  ],
)
data class SavingsGoalEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val goalName: String,
  val goalCategory: SavingsGoalType,
  val targetAmount: BigDecimal,
  val currentSavedAmount: BigDecimal,
  val currencyCode: String,
  val targetDateEpochDay: Long? = null,
  val isEmergencyFund: Boolean = false,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)
