package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(
  tableName = "monthly_financial_summaries",
  indices = [
    Index(value = ["yearMonth", "currencyCode"], unique = true),
  ],
)
data class MonthlyFinancialSummaryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val yearMonth: String,
  val currencyCode: String,
  val totalIncome: BigDecimal,
  val totalExpense: BigDecimal,
  val totalSavingsAllocated: BigDecimal,
  val totalEmiPaid: BigDecimal,
  val safeToSpendSnapshot: BigDecimal,
  val confirmedRemainingIncome: BigDecimal,
  val reservedAmount: BigDecimal,
  val monthlyEssentialExpenses: BigDecimal,
  val updatedAtEpochMillis: Long,
)
