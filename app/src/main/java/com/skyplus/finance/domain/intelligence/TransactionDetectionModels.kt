package com.skyplus.finance.domain.intelligence

import com.skyplus.finance.domain.model.TransactionType
import java.math.BigDecimal

enum class DetectionOutcome(val label: String, val canSave: Boolean) {
  DETECTED("Detected", true),
  REFUND_OR_REVERSAL("Refund / Reversal", true),
  DUPLICATE_EXISTING("Duplicate Skipped", false),
  FAILED_OR_PENDING("Failed / Pending", false),
  PROMOTIONAL_OR_OTP("Promo / OTP Ignored", false),
  UNRECOGNIZED("Not a Transaction", false),
}

data class DetectedTransactionCandidate(
  val candidateId: String,
  val rawMessage: String,
  val outcome: DetectionOutcome,
  val statusReason: String,
  val amount: BigDecimal?,
  val currencyCode: String,
  val type: TransactionType,
  val transactionDateEpochDay: Long,
  val timeFormatted: String?,
  val merchantOrRecipient: String,
  val paymentReference: String?,
  val accountIdentifier: String?,
  val bankOrInstitution: String?,
  val description: String,
  val suggestedCategoryId: Long,
  val suggestedCategoryName: String,
  val categoryKey: String,
  val isRefundOrReversal: Boolean,
  val detectionSource: String,
  val deduplicationKey: String,
)

data class DetectionBatchResult(
  val candidates: List<DetectedTransactionCandidate>,
  val actionableCount: Int,
  val refundCount: Int,
  val duplicateCount: Int,
  val failedOrPendingCount: Int,
  val ignoredNonFinancialCount: Int,
)
