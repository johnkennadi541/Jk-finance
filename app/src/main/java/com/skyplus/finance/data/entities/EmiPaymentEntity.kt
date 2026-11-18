package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(
  tableName = "emi_payments",
  foreignKeys = [
    ForeignKey(
      entity = LoanEntity::class,
      parentColumns = ["id"],
      childColumns = ["loanId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["loanId"]),
    Index(value = ["paymentDateEpochDay"]),
  ],
)
data class EmiPaymentEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val loanId: Long,
  val paymentDateEpochDay: Long,
  val totalPaidAmount: BigDecimal,
  val principalComponent: BigDecimal,
  val interestComponent: BigDecimal,
  val extraPrepaymentAmount: BigDecimal = BigDecimal.ZERO,
  val remainingBalanceAfter: BigDecimal,
  val currencyCode: String,
  val note: String = "",
  val createdAtEpochMillis: Long,
)
