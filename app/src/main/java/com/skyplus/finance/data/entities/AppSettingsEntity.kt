package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
  @PrimaryKey val id: Int = 1,
  val primaryCurrencyCode: String,
  val languageCode: String = "en",
  val themeMode: String = "SYSTEM",
  val reservedAmount: BigDecimal = BigDecimal.ZERO,
  val monthlySavingsAllocation: BigDecimal = BigDecimal.ZERO,
  val confirmedRemainingIncome: BigDecimal = BigDecimal.ZERO,
  val monthlyEssentialExpenses: BigDecimal = BigDecimal.ZERO,
  val appLockEnabled: Boolean = false,
  val lockOnLaunch: Boolean = true,
  val lockOnBackgroundReturn: Boolean = true,
  val biometricPreferred: Boolean = true,
  val guestProfileName: String = "Local Guest",
  val dailySpendingSummaryEnabled: Boolean = false,
  val spendingSummaryHour: Int = 20,
  val spendingSummaryMinute: Int = 30,
  val budgetAlertsEnabled: Boolean = true,
  val billEmiRemindersEnabled: Boolean = true,
  val shoppingBudgetRemindersEnabled: Boolean = true,
  val updatedAtEpochMillis: Long,
)
