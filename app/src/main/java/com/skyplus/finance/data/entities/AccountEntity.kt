package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "accounts")
data class AccountEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val accountName: String,
  val accountType: String = "LOCAL_WALLET",
  val primaryCurrencyCode: String,
  val openingBalance: BigDecimal = BigDecimal.ZERO,
  val isGuestLocal: Boolean = true,
  val googleAccountEmail: String? = null,
  val createdAtEpochMillis: Long,
)
