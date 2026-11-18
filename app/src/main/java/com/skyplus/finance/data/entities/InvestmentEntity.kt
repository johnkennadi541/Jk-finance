package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "investments")
data class InvestmentEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val investmentName: String,
  val investmentType: String,
  val amountInvested: BigDecimal,
  val investmentDateEpochDay: Long,
  val currentValue: BigDecimal? = null,
  val notes: String = "",
  val currencyCode: String,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)
