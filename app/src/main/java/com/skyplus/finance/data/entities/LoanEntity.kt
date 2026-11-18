package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.skyplus.finance.domain.model.LoanStatus
import java.math.BigDecimal

@Entity(
  tableName = "loans",
  indices = [
    Index(value = ["status"]),
    Index(value = ["nextDueDateEpochDay"]),
  ],
)
data class LoanEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val loanName: String,
  val lender: String,
  val originalPrincipal: BigDecimal,
  val outstandingPrincipal: BigDecimal,
  val annualInterestRatePercent: BigDecimal,
  val emiAmount: BigDecimal,
  val currencyCode: String,
  val paymentDueDayOfMonth: Int,
  val nextDueDateEpochDay: Long,
  val remainingTenureMonths: Int,
  val startDateEpochDay: Long,
  val status: LoanStatus = LoanStatus.ACTIVE,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)
