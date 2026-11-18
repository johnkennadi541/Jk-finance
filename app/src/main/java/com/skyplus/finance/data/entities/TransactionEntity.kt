package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.skyplus.finance.domain.model.TransactionType
import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Entity(
  tableName = "transactions",
  foreignKeys = [
    ForeignKey(
      entity = AccountEntity::class,
      parentColumns = ["id"],
      childColumns = ["accountId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = CategoryEntity::class,
      parentColumns = ["id"],
      childColumns = ["categoryId"],
      onDelete = ForeignKey.RESTRICT,
    ),
  ],
  indices = [
    Index(value = ["accountId"]),
    Index(value = ["categoryId"]),
    Index(value = ["transactionDateEpochDay"]),
    Index(value = ["type"]),
    Index(value = ["currencyCode"]),
    Index(value = ["deduplicationKey"], unique = true),
  ],
)
data class TransactionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val accountId: Long,
  val categoryId: Long,
  val type: TransactionType,
  val amount: BigDecimal,
  val currencyCode: String,
  val exchangeRateToPrimary: BigDecimal? = null,
  val exchangeRateSource: String? = null,
  val exchangeRateTimestamp: Long? = null,
  val convertedPrimaryAmount: BigDecimal? = null,
  val primaryCurrencyAtEntry: String? = null,
  val description: String = "",
  val transactionDateEpochDay: Long,
  val isRecurring: Boolean = false,
  val recurrenceIntervalDays: Int? = null,
  val isUpcomingBill: Boolean = false,
  val isPaid: Boolean = true,
  val linkedGoalId: Long? = null,
  val linkedLoanId: Long? = null,
  val deduplicationKey: String? = null,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)
