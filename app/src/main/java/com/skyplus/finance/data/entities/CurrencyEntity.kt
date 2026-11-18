package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "currencies")
data class CurrencyEntity(
  @PrimaryKey val code: String,
  val numericCode: String,
  val displayName: String,
  val symbol: String,
  val decimalPrecision: Int,
  val isActiveInWallet: Boolean = false,
  val cachedRateToBase: BigDecimal? = null,
  val rateSource: String? = null,
  val rateUpdatedAtEpochMillis: Long? = null,
)
