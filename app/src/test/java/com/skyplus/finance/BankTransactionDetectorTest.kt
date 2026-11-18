package com.skyplus.finance

import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.intelligence.BankTransactionDetector
import com.skyplus.finance.domain.intelligence.DetectionOutcome
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankTransactionDetectorTest {

  private val detector = BankTransactionDetector()
  private val today = LocalDate.of(2026, 11, 15)
  private val precisionMap = mapOf("INR" to 2, "KWD" to 3, "USD" to 2)
  private val categories = listOf(
    CategoryEntity(1L, "food", "Food", "Food", CategoryType.EXPENSE, "restaurant", 0xFF2878D0),
    CategoryEntity(2L, "rent_and_bills", "Bills & Utilities", "Bills", CategoryType.EXPENSE, "receipt", 0xFF2878D0),
    3L.let { CategoryEntity(it, "transport", "Transport", "Transport", CategoryType.EXPENSE, "directions_car", 0xFF00BFA6) },
    CategoryEntity(4L, "emi_and_loans", "EMI & Loans", "EMI", CategoryType.EXPENSE, "account_balance", 0xFF2878D0),
    CategoryEntity(5L, "expense_other", "Other Expense", "Other", CategoryType.EXPENSE, "category", 0xFF2878D0),
    CategoryEntity(10L, "salary", "Salary", "Salary", CategoryType.INCOME, "payments", 0xFF00BFA6),
    CategoryEntity(11L, "income_other", "Other Income", "Other", CategoryType.INCOME, "payments", 0xFF00BFA6),
  )

  @Test
  fun `detects multi-currency bank debit and credit alerts with metadata and smart categories`() {
    val input = """
      INR 1,250.50 debited from HDFC Bank A/c XX4521 on 14-11-2026 19:45 PM at Swiggy UPI Ref No 6319201188.

      KWD 450.250 credited to NBK Account **8812 on 2026-11-01 09:00 AM Ref SAL881201 Salary payout.
    """.trimIndent()

    val result = detector.detectBatch(
      rawInput = input,
      existingTransactions = emptyList(),
      categories = categories,
      defaultCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(2, result.candidates.size)
    assertEquals(2, result.actionableCount)

    val debit = result.candidates[0]
    assertEquals(DetectionOutcome.DETECTED, debit.outcome)
    assertEquals(TransactionType.EXPENSE, debit.type)
    assertEquals(BigDecimal("1250.50"), debit.amount)
    assertEquals("INR", debit.currencyCode)
    assertEquals("HDFC", debit.bankOrInstitution)
    assertEquals("XX4521", debit.accountIdentifier)
    assertEquals("6319201188", debit.paymentReference)
    assertEquals(LocalDate.of(2026, 11, 14).toEpochDay(), debit.transactionDateEpochDay)
    assertNotNull(debit.timeFormatted)
    assertEquals("food", debit.categoryKey)

    val credit = result.candidates[1]
    assertEquals(DetectionOutcome.DETECTED, credit.outcome)
    assertEquals(TransactionType.INCOME, credit.type)
    assertEquals(BigDecimal("450.250"), credit.amount)
    assertEquals("KWD", credit.currencyCode)
    assertEquals("NBK", credit.bankOrInstitution)
    assertEquals("salary", credit.categoryKey)
  }

  @Test
  fun `filters out otp promotional failed pending and flags refunds cleanly`() {
    val input = """
      Your OTP is 482910 for HDFC NetBanking login. Do not share this code with anyone.

      Special offer: Get pre-approved personal loan! Apply now and claim your reward using code WIN50.

      Your ICICI card transaction of USD 45.00 at Uber was declined due to insufficient balance.

      Upcoming auto-debit of INR 999.00 will be debited on 20-11-2026 for Airtel Fiber.

      INR 540.00 refunded to SBI A/c XX9901 from Amazon Pay Ref RFD90211 on 15-11-2026.
    """.trimIndent()

    val result = detector.detectBatch(
      rawInput = input,
      existingTransactions = emptyList(),
      categories = categories,
      defaultCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(5, result.candidates.size)
    assertEquals(1, result.actionableCount)
    assertEquals(1, result.refundCount)
    assertEquals(2, result.failedOrPendingCount)
    assertEquals(2, result.ignoredNonFinancialCount)

    assertEquals(DetectionOutcome.PROMOTIONAL_OR_OTP, result.candidates[0].outcome)
    assertFalse(result.candidates[0].outcome.canSave)
    assertEquals(DetectionOutcome.PROMOTIONAL_OR_OTP, result.candidates[1].outcome)
    assertEquals(DetectionOutcome.FAILED_OR_PENDING, result.candidates[2].outcome)
    assertEquals(DetectionOutcome.FAILED_OR_PENDING, result.candidates[3].outcome)

    val refund = result.candidates[4]
    assertEquals(DetectionOutcome.REFUND_OR_REVERSAL, refund.outcome)
    assertTrue(refund.outcome.canSave)
    assertTrue(refund.isRefundOrReversal)
    assertEquals(TransactionType.INCOME, refund.type)
    assertEquals(BigDecimal("540.00"), refund.amount)
  }

  @Test
  fun `skips duplicates against existing database transactions and within batch`() {
    val message = "INR 750.00 spent at Uber on 15-11-2026 via SBI Card XX1122 UTR 99887766"
    val firstRun = detector.detectBatch(
      rawInput = message,
      existingTransactions = emptyList(),
      categories = categories,
      defaultCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )
    assertEquals(1, firstRun.actionableCount)
    val firstKey = firstRun.candidates[0].deduplicationKey

    val existingTx = TransactionEntity(
      id = 42L,
      accountId = 1L,
      categoryId = 3L,
      type = TransactionType.EXPENSE,
      amount = BigDecimal("750.00"),
      currencyCode = "INR",
      description = "Uber (Bank Message Import • SBI • Ref 99887766)",
      transactionDateEpochDay = today.toEpochDay(),
      deduplicationKey = firstKey,
      createdAtEpochMillis = 1L,
      updatedAtEpochMillis = 1L,
    )

    val secondRun = detector.detectBatch(
      rawInput = "$message\n\n$message",
      existingTransactions = listOf(existingTx),
      categories = categories,
      defaultCurrencyCode = "INR",
      currencyPrecisionMap = precisionMap,
      today = today,
    )

    assertEquals(0, secondRun.actionableCount)
    assertEquals(2, secondRun.duplicateCount)
    assertTrue(secondRun.candidates.all { it.outcome == DetectionOutcome.DUPLICATE_EXISTING })
  }
}
