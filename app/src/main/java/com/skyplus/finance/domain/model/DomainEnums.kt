package com.skyplus.finance.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionType {
  INCOME,
  EXPENSE,
  SAVINGS_ALLOCATION,
}

@Serializable
enum class CategoryType {
  EXPENSE,
  INCOME,
  SAVINGS,
}

@Serializable
enum class LoanStatus {
  ACTIVE,
  PAID_OFF,
  PAUSED,
}

@Serializable
enum class SavingsGoalType {
  EMERGENCY_FUND,
  HOUSE,
  VEHICLE,
  EDUCATION,
  TRAVEL,
  PERSONAL_SAVINGS,
}

@Serializable
enum class AppThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

@Serializable
enum class AppLanguage(val code: String, val displayName: String) {
  ENGLISH("en", "English"),
  TAMIL("ta", "தமிழ்");

  companion object {
    fun fromCode(code: String): AppLanguage =
      entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
  }
}

@Serializable
enum class BudgetHealthStatus {
  NORMAL,
  APPROACHING_LIMIT,
  EXCEEDED,
}

@Serializable
enum class DebtPayoffStrategy {
  SNOWBALL,
  AVALANCHE,
}
