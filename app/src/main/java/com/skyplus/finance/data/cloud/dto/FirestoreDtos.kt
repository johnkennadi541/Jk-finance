package com.skyplus.finance.data.cloud.dto

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
data class FirestoreEncryptedEnvelope(
  val recordId: String = "",
  val ownerUid: String = "",
  val entityType: String = "",
  val encryptedPayloadBase64: String = "",
  val payloadNonceBase64: String = "",
  val schemaVersion: Int = 3,
  val syncVersion: Long = 1L,
  val updatedByDeviceId: String = "",
  val isDeleted: Boolean = false,
  val clientUpdatedAtMillis: Long = 0L,
)

@Keep
data class FirestorePrivateProfileDto(
  val uid: String = "",
  val email: String? = null,
  val displayName: String? = null,
  val cloudSyncEnabled: Boolean = false,
  val driveBackupEnabled: Boolean = false,
  val driveWifiOnly: Boolean = true,
  val deviceId: String = "",
  val schemaVersion: Int = 1,
)

@Serializable
data class CloudTransactionPayload(
  val localId: Long = 0L,
  val accountRecordId: String = "",
  val categoryNameKey: String = "",
  val categoryId: Long = 0L,
  val type: String,
  val amount: String,
  val currencyCode: String,
  val convertedPrimaryAmount: String? = null,
  val primaryCurrencyAtEntry: String? = null,
  val exchangeRateSource: String? = null,
  val description: String = "",
  val transactionDateEpochDay: Long,
  val isRecurring: Boolean = false,
  val recurrenceIntervalDays: Int? = null,
  val isUpcomingBill: Boolean = false,
  val isPaid: Boolean = true,
  val linkedGoalRecordId: String? = null,
  val linkedLoanRecordId: String? = null,
  val deduplicationKey: String? = null,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudBudgetPayload(
  val localId: Long = 0L,
  val categoryId: Long,
  val yearMonth: String,
  val limitAmount: String,
  val currencyCode: String,
  val rolloverEnabled: Boolean = false,
  val rolloverAmount: String = "0",
  val warningThresholdPercent: Int = 80,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudLoanPayload(
  val localId: Long = 0L,
  val loanName: String,
  val lender: String,
  val originalPrincipal: String,
  val outstandingPrincipal: String,
  val annualInterestRatePercent: String,
  val emiAmount: String,
  val currencyCode: String,
  val paymentDueDayOfMonth: Int,
  val nextDueDateEpochDay: Long,
  val remainingTenureMonths: Int,
  val startDateEpochDay: Long,
  val status: String,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudEmiPaymentPayload(
  val localId: Long = 0L,
  val loanLocalId: Long,
  val loanRecordId: String? = null,
  val paymentDateEpochDay: Long,
  val totalPaidAmount: String,
  val principalComponent: String,
  val interestComponent: String,
  val extraPrepaymentAmount: String,
  val remainingBalanceAfter: String,
  val currencyCode: String,
  val note: String = "",
  val createdAtEpochMillis: Long,
)

@Serializable
data class CloudSavingsGoalPayload(
  val localId: Long = 0L,
  val goalName: String,
  val goalCategory: String,
  val targetAmount: String,
  val currentSavedAmount: String,
  val currencyCode: String,
  val targetDateEpochDay: Long? = null,
  val isEmergencyFund: Boolean = false,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudInvestmentPayload(
  val localId: Long = 0L,
  val investmentName: String,
  val investmentType: String,
  val amountInvested: String,
  val investmentDateEpochDay: Long,
  val currentValue: String? = null,
  val notes: String = "",
  val currencyCode: String,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudShoppingListPayload(
  val localId: Long = 0L,
  val name: String,
  val budgetAmount: String,
  val currencyCode: String,
  val notes: String = "",
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudShoppingItemPayload(
  val localId: Long = 0L,
  val listId: Long,
  val listRecordId: String? = null,
  val name: String,
  val categoryName: String,
  val quantity: String,
  val unit: String,
  val estimatedUnitPrice: String,
  val actualUnitPrice: String? = null,
  val isPurchased: Boolean = false,
  val notes: String = "",
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudShoppingShopPricePayload(
  val localId: Long = 0L,
  val itemNameNormalized: String,
  val productDisplayName: String,
  val shopName: String,
  val shopLocation: String,
  val unit: String,
  val price: String,
  val currencyCode: String,
  val priceSource: String,
  val isLiveFeedAvailable: Boolean = false,
  val updatedAtEpochMillis: Long,
)

@Serializable
data class CloudAccountPayload(
  val localId: Long = 0L,
  val accountName: String,
  val accountType: String,
  val primaryCurrencyCode: String,
  val openingBalance: String,
  val isGuestLocal: Boolean,
  val createdAtEpochMillis: Long,
)

@Serializable
data class CloudCategoryPayload(
  val localId: Long = 0L,
  val nameKey: String,
  val displayNameEn: String,
  val displayNameTa: String,
  val type: String,
  val iconKey: String,
  val colorHex: Long,
  val isSystemDefault: Boolean,
  val sortOrder: Int,
)

@Serializable
data class CloudCurrencyPayload(
  val code: String,
  val numericCode: String,
  val displayName: String,
  val symbol: String,
  val decimalPrecision: Int,
  val isActiveInWallet: Boolean,
  val cachedRateToBase: String? = null,
  val rateSource: String? = null,
  val rateUpdatedAtEpochMillis: Long? = null,
)

@Serializable
data class CloudAppSettingsPayload(
  val primaryCurrencyCode: String,
  val languageCode: String,
  val themeMode: String,
  val reservedAmount: String,
  val monthlySavingsAllocation: String,
  val confirmedRemainingIncome: String,
  val monthlyEssentialExpenses: String,
  val dailySpendingSummaryEnabled: Boolean,
  val spendingSummaryHour: Int,
  val spendingSummaryMinute: Int,
  val budgetAlertsEnabled: Boolean,
  val billEmiRemindersEnabled: Boolean,
  val shoppingBudgetRemindersEnabled: Boolean,
  val updatedAtEpochMillis: Long,
)
