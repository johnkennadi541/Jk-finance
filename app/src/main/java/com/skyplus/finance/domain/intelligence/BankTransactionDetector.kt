package com.skyplus.finance.domain.intelligence

import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import javax.inject.Inject

/**
 * Privacy-first, completely offline bank/UPI/card message transaction detector.
 * Processes only messages or statement exports explicitly submitted by the user,
 * without requiring restricted SMS or Notification Listener permissions.
 */
class BankTransactionDetector @Inject constructor() {

  companion object {
    private val PROMO_OR_OTP_PATTERNS = listOf(
      Regex("\\b(?:otp|one[-\\s]?time\\s+password|verification\\s+code|passcode)\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(?:do not share.*code|secret code|login code|cvv)\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(?:pre[-\\s]?approved|apply now|avail loan|special offer|exclusive offer|use code|promo code|claim your reward|win upto|flat \\d+%|discount of)\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(?:kyc\\s+(?:pending|update|expired)|e-statement is ready|click here to|tap to avail)\\b", RegexOption.IGNORE_CASE),
    )

    private val FAILED_OR_PENDING_PATTERNS = listOf(
      Regex("\\b(?:failed|declined|unsuccessful|rejected|cancelled|canceled|timed out|not completed|could not be processed|insufficient (?:balance|funds)|transaction reversed due to failure)\\b", RegexOption.IGNORE_CASE),
      Regex("\\b(?:will be debited|upcoming auto[-\\s]?debit|is due on|mandate created|pending approval|awaiting confirmation)\\b", RegexOption.IGNORE_CASE),
    )

    private val REFUND_REVERSAL_PATTERNS = listOf(
      Regex("\\b(?:refund(?:ed)?|reversal|reversed|credited back|cashback credited|chargeback)\\b", RegexOption.IGNORE_CASE),
    )

    private val DEBIT_KEYWORDS = listOf(
      Regex("\\b(?:debited|spent|paid|withdrawn|sent|transferred to|purchase(?:d)?|charged|deducted|auto[-\\s]?debited|dr\\b)\\b", RegexOption.IGNORE_CASE),
    )

    private val CREDIT_KEYWORDS = listOf(
      Regex("\\b(?:credited|received|deposited|salary|cr\\b|added to your)\\b", RegexOption.IGNORE_CASE),
    )

    private val AMOUNT_PATTERNS = listOf(
      Regex("(KWD|KD|INR|USD|EUR|GBP|AED|SAR|OMR|BHD|QAR|SGD|CAD|AUD|Rs\\.?|₹|\\$|€|£)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,4})?)", RegexOption.IGNORE_CASE),
      Regex("([0-9][0-9,]*(?:\\.[0-9]{1,4})?)\\s*(KWD|KD|INR|USD|EUR|GBP|AED|SAR|OMR|BHD|QAR|SGD|CAD|AUD|Rs\\.?)", RegexOption.IGNORE_CASE),
    )

    private val REF_PATTERNS = listOf(
      Regex("(?:UTR|UPI\\s*Ref(?:\\.?\\s*No\\.?)?|Ref(?:erence)?\\s*(?:No\\.?|Id|#|:)?|Txn\\s*(?:ID|No\\.?|#|:)?|IMPS|NEFT)[:\\s#-]*([A-Za-z0-9]{5,24})", RegexOption.IGNORE_CASE),
    )

    private val ACCOUNT_PATTERNS = listOf(
      Regex("(?:A/c|Acct?|Account)\\s*(?:No\\.?)?\\s*([*xX]{1,6}[0-9]{3,4}|[0-9]{4})", RegexOption.IGNORE_CASE),
      Regex("(?:Card|Credit Card|Debit Card)\\s*(?:ending|no\\.?)?\\s*([*xX]{0,6}[0-9]{4})", RegexOption.IGNORE_CASE),
      Regex("\\b([xX]{2,4}[0-9]{3,4})\\b"),
    )

    private val BANK_NAMES = listOf(
      "HDFC", "SBI", "ICICI", "Axis", "Kotak", "IDFC", "Yes Bank", "PNB", "Bank of Baroda",
      "IndusInd", "Federal Bank", "Canara", "NBK", "KFH", "Boubyan", "Burgan", "CBK", "Gulf Bank",
      "Amex", "Citi", "HSBC", "Chase", "Bank of America", "Wells Fargo", "GPay", "Google Pay",
      "PhonePe", "Paytm", "UPI",
    )

    private val MERCHANT_PATTERNS = listOf(
      Regex("(?:Merchant|Payee|Beneficiary)\\s*[:\\-]\\s*([A-Za-z0-9 &._@\\-/]{2,45})", RegexOption.IGNORE_CASE),
      Regex("\\b(?:at|to|from|towards|via)\\s+([A-Za-z0-9][A-Za-z0-9 &._@\\-/]{1,40}?)(?=\\s+(?:on\\b|via\\b|Ref\\b|UTR\\b|Txn\\b|Avl\\b|Bal\\b|Info\\b|using\\b|Not\\b)|[.,;]|$)", RegexOption.IGNORE_CASE),
      Regex("VPA\\s+([A-Za-z0-9._-]+@[A-Za-z0-9]+)", RegexOption.IGNORE_CASE),
    )

    private val DATE_PATTERNS = listOf(
      Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b"),
      Regex("\\b(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})\\b"),
      Regex("\\b(\\d{1,2}[-\\s][A-Za-z]{3}[-\\s]\\d{2,4})\\b"),
    )

    private val TIME_PATTERN = Regex("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM))?)\\b", RegexOption.IGNORE_CASE)
  }

  fun detectBatch(
    rawInput: String,
    existingTransactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    defaultCurrencyCode: String,
    currencyPrecisionMap: Map<String, Int>,
    detectionSource: String = "Bank Message Import",
    today: LocalDate = LocalDate.now(),
  ): DetectionBatchResult {
    val chunks = splitIntoMessages(rawInput)
    if (chunks.isEmpty()) {
      return DetectionBatchResult(emptyList(), 0, 0, 0, 0, 0)
    }

    val batchKeys = HashSet<String>()
    val candidates = chunks.mapIndexed { idx, chunk ->
      parseSingleMessage(
        index = idx,
        rawMessage = chunk,
        existingTransactions = existingTransactions,
        seenKeysInBatch = batchKeys,
        categories = categories,
        defaultCurrencyCode = defaultCurrencyCode,
        currencyPrecisionMap = currencyPrecisionMap,
        detectionSource = detectionSource,
        today = today,
      )
    }

    return DetectionBatchResult(
      candidates = candidates,
      actionableCount = candidates.count { it.outcome.canSave },
      refundCount = candidates.count { it.outcome == DetectionOutcome.REFUND_OR_REVERSAL },
      duplicateCount = candidates.count { it.outcome == DetectionOutcome.DUPLICATE_EXISTING },
      failedOrPendingCount = candidates.count { it.outcome == DetectionOutcome.FAILED_OR_PENDING },
      ignoredNonFinancialCount = candidates.count {
        it.outcome == DetectionOutcome.PROMOTIONAL_OR_OTP || it.outcome == DetectionOutcome.UNRECOGNIZED
      },
    )
  }

  private fun splitIntoMessages(rawInput: String): List<String> {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return emptyList()

    // Prefer blank-line separated blocks; if a single block has multiple distinct alert lines, split per line
    val paragraphChunks = trimmed
      .split(Regex("\\n\\s*\\n+"))
      .map { it.trim() }
      .filter { it.isNotEmpty() }

    if (paragraphChunks.size > 1) return paragraphChunks

    val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val allLookLikeAlertsOrRows = lines.size > 1 && lines.count { line ->
      AMOUNT_PATTERNS.any { it.containsMatchIn(line) } ||
        PROMO_OR_OTP_PATTERNS.any { it.containsMatchIn(line) } ||
        FAILED_OR_PENDING_PATTERNS.any { it.containsMatchIn(line) }
    } >= 2

    return if (allLookLikeAlertsOrRows) lines else listOf(trimmed)
  }

  private fun parseSingleMessage(
    index: Int,
    rawMessage: String,
    existingTransactions: List<TransactionEntity>,
    seenKeysInBatch: MutableSet<String>,
    categories: List<CategoryEntity>,
    defaultCurrencyCode: String,
    currencyPrecisionMap: Map<String, Int>,
    detectionSource: String,
    today: LocalDate,
  ): DetectedTransactionCandidate {
    val normalized = rawMessage.replace(Regex("\\s+"), " ").trim()
    val (parsedDate, timeFormatted) = extractDateTime(normalized, today)

    // 1. Reject OTP / Marketing / Balance-only alerts cleanly
    val isPromoOrOtp = PROMO_OR_OTP_PATTERNS.any { it.containsMatchIn(normalized) }
    val hasDebitCue = DEBIT_KEYWORDS.any { it.containsMatchIn(normalized) }
    val hasCreditCue = CREDIT_KEYWORDS.any { it.containsMatchIn(normalized) }
    val isRefund = REFUND_REVERSAL_PATTERNS.any { it.containsMatchIn(normalized) }
    val isBalanceInquiryOnly = Regex("\\b(?:avl\\s*bal|available\\s*balance|balance\\s*(?:is|:))\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized) &&
      !hasDebitCue && !hasCreditCue && !isRefund

    if (isPromoOrOtp || isBalanceInquiryOnly) {
      return ignoredCandidate(
        index = index,
        rawMessage = rawMessage,
        outcome = DetectionOutcome.PROMOTIONAL_OR_OTP,
        reason = if (isBalanceInquiryOnly) {
          "Balance update only — no debit or credit transaction."
        } else {
          "Promotional, KYC, or OTP security alert — skipped automatically."
        },
        defaultCurrencyCode = defaultCurrencyCode,
        categories = categories,
        detectionSource = detectionSource,
        date = parsedDate,
        timeFormatted = timeFormatted,
      )
    }

    // 2. Reject failed, declined, or pending/scheduled future mandate alerts
    if (FAILED_OR_PENDING_PATTERNS.any { it.containsMatchIn(normalized) } && !isRefund) {
      return ignoredCandidate(
        index = index,
        rawMessage = rawMessage,
        outcome = DetectionOutcome.FAILED_OR_PENDING,
        reason = "Failed, declined, or upcoming/pending notice — excluded from realized ledger.",
        defaultCurrencyCode = defaultCurrencyCode,
        categories = categories,
        detectionSource = detectionSource,
        date = parsedDate,
        timeFormatted = timeFormatted,
      )
    }

    // 3. Extract monetary amount and currency
    val (rawAmount, detectedCurrency) = extractAmountAndCurrency(normalized, defaultCurrencyCode)
    if (rawAmount == null || rawAmount <= BigDecimal.ZERO) {
      return ignoredCandidate(
        index = index,
        rawMessage = rawMessage,
        outcome = DetectionOutcome.UNRECOGNIZED,
        reason = "No valid transaction amount recognized.",
        defaultCurrencyCode = defaultCurrencyCode,
        categories = categories,
        detectionSource = detectionSource,
        date = parsedDate,
        timeFormatted = timeFormatted,
      )
    }

    if (!hasDebitCue && !hasCreditCue && !isRefund) {
      return ignoredCandidate(
        index = index,
        rawMessage = rawMessage,
        outcome = DetectionOutcome.UNRECOGNIZED,
        reason = "Message does not confirm a completed debit or credit.",
        defaultCurrencyCode = defaultCurrencyCode,
        categories = categories,
        detectionSource = detectionSource,
        date = parsedDate,
        timeFormatted = timeFormatted,
      )
    }

    val precision = currencyPrecisionMap[detectedCurrency.uppercase()] ?: if (detectedCurrency == "KWD") 3 else 2
    val scaledAmount = DecimalUtils.scaleToCurrency(rawAmount, precision)

    val txType = when {
      isRefund -> TransactionType.INCOME
      hasCreditCue && !hasDebitCue -> TransactionType.INCOME
      else -> TransactionType.EXPENSE
    }

    val paymentRef = extractReference(normalized)
    val accountIdentifier = extractAccountIdentifier(normalized)
    val bankName = extractBankName(normalized)
    val merchant = extractMerchant(normalized, bankName)
    val dateEpochDay = parsedDate.toEpochDay()

    val (catKey, category) = inferCategory(
      text = "$normalized $merchant",
      txType = txType,
      isRefund = isRefund,
      categories = categories,
    )

    val dedupKey = buildDeduplicationKey(
      paymentRef = paymentRef,
      amount = scaledAmount,
      currencyCode = detectedCurrency,
      txType = txType,
      dateEpochDay = dateEpochDay,
      merchant = merchant,
    )

    val isDuplicate = !seenKeysInBatch.add(dedupKey) || isDuplicateInExisting(
      dedupKey = dedupKey,
      paymentRef = paymentRef,
      amount = scaledAmount,
      currencyCode = detectedCurrency,
      txType = txType,
      dateEpochDay = dateEpochDay,
      merchant = merchant,
      existingTransactions = existingTransactions,
    )

    val sourceTagParts = buildList {
      add(detectionSource)
      if (!bankName.isNullOrBlank()) add(bankName)
      if (!accountIdentifier.isNullOrBlank()) add(accountIdentifier)
      if (!paymentRef.isNullOrBlank()) add("Ref $paymentRef")
    }
    val summaryMerchant = merchant.ifBlank {
      if (isRefund) "Refund / Reversal" else if (txType == TransactionType.INCOME) "Credited Amount" else "Card / Bank Purchase"
    }
    val description = "$summaryMerchant (${sourceTagParts.joinToString(" • ")})"

    val outcome = when {
      isDuplicate -> DetectionOutcome.DUPLICATE_EXISTING
      isRefund -> DetectionOutcome.REFUND_OR_REVERSAL
      else -> DetectionOutcome.DETECTED
    }
    val reason = when (outcome) {
      DetectionOutcome.DUPLICATE_EXISTING -> "Matches an existing record or reference in your portfolio."
      DetectionOutcome.REFUND_OR_REVERSAL -> "Detected refund or reversal — mapped as credit."
      DetectionOutcome.DETECTED -> "Verified transaction ready for your confirmation."
      else -> ""
    }

    return DetectedTransactionCandidate(
      candidateId = "cand_${index}_${dedupKey.takeLast(8)}",
      rawMessage = rawMessage,
      outcome = outcome,
      statusReason = reason,
      amount = scaledAmount,
      currencyCode = detectedCurrency,
      type = txType,
      transactionDateEpochDay = dateEpochDay,
      timeFormatted = timeFormatted,
      merchantOrRecipient = summaryMerchant,
      paymentReference = paymentRef,
      accountIdentifier = accountIdentifier,
      bankOrInstitution = bankName,
      description = description,
      suggestedCategoryId = category?.id ?: 1L,
      suggestedCategoryName = category?.displayNameEn ?: "Other",
      categoryKey = catKey,
      isRefundOrReversal = isRefund,
      detectionSource = detectionSource,
      deduplicationKey = dedupKey,
    )
  }

  private fun ignoredCandidate(
    index: Int,
    rawMessage: String,
    outcome: DetectionOutcome,
    reason: String,
    defaultCurrencyCode: String,
    categories: List<CategoryEntity>,
    detectionSource: String,
    date: LocalDate,
    timeFormatted: String?,
  ): DetectedTransactionCandidate {
    val fallbackCat = categories.firstOrNull { it.type == CategoryType.EXPENSE }
    return DetectedTransactionCandidate(
      candidateId = "ignored_$index",
      rawMessage = rawMessage,
      outcome = outcome,
      statusReason = reason,
      amount = null,
      currencyCode = defaultCurrencyCode,
      type = TransactionType.EXPENSE,
      transactionDateEpochDay = date.toEpochDay(),
      timeFormatted = timeFormatted,
      merchantOrRecipient = "",
      paymentReference = null,
      accountIdentifier = null,
      bankOrInstitution = null,
      description = "",
      suggestedCategoryId = fallbackCat?.id ?: 1L,
      suggestedCategoryName = fallbackCat?.displayNameEn ?: "Other",
      categoryKey = fallbackCat?.nameKey ?: "expense_other",
      isRefundOrReversal = false,
      detectionSource = detectionSource,
      deduplicationKey = "ignored_${sha256Short(rawMessage)}",
    )
  }

  private fun extractAmountAndCurrency(text: String, defaultCurrencyCode: String): Pair<BigDecimal?, String> {
    for (pattern in AMOUNT_PATTERNS) {
      val match = pattern.find(text) ?: continue
      val g1 = match.groupValues[1]
      val g2 = match.groupValues[2]
      val (curToken, numToken) = if (g1.firstOrNull()?.isDigit() == true) {
        g2 to g1
      } else {
        g1 to g2
      }
      val cleanNum = numToken.replace(",", "")
      val parsed = DecimalUtils.parsePositiveOrNull(cleanNum) ?: continue
      val iso = normalizeCurrencyToken(curToken, defaultCurrencyCode)
      return parsed to iso
    }
    return null to defaultCurrencyCode.uppercase()
  }

  private fun normalizeCurrencyToken(token: String, fallback: String): String {
    val clean = token.trim().uppercase().removeSuffix(".")
    return when (clean) {
      "RS", "INR", "₹" -> "INR"
      "KD", "KWD" -> "KWD"
      "$", "USD" -> "USD"
      "€", "EUR" -> "EUR"
      "£", "GBP" -> "GBP"
      "AED", "DHS" -> "AED"
      "SAR" -> "SAR"
      "OMR" -> "OMR"
      "BHD" -> "BHD"
      "QAR" -> "QAR"
      "SGD" -> "SGD"
      "CAD" -> "CAD"
      "AUD" -> "AUD"
      else -> fallback.uppercase()
    }
  }

  private fun extractReference(text: String): String? {
    for (pattern in REF_PATTERNS) {
      val m = pattern.find(text) ?: continue
      val candidate = m.groupValues[1].trim()
      if (candidate.any { it.isDigit() }) {
        return candidate
      }
    }
    return null
  }

  private fun extractAccountIdentifier(text: String): String? {
    for (pattern in ACCOUNT_PATTERNS) {
      val m = pattern.find(text) ?: continue
      val raw = m.groupValues[1].trim()
      if (raw.length >= 3) {
        return if (raw.startsWith("X", true) || raw.startsWith("*")) raw.uppercase() else "XX${raw.takeLast(4)}"
      }
    }
    return null
  }

  private fun extractBankName(text: String): String? {
    return BANK_NAMES.firstOrNull { bank ->
      Regex("\\b${Regex.escape(bank)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
  }

  private fun extractMerchant(text: String, bankName: String?): String {
    // Strip trailing balance clause to avoid capturing "Avl Bal ..." as merchant
    val sanitized = text.substringBefore("Avl Bal", text)
      .substringBefore("Available Bal", text)
      .substringBefore("Avl Limit", text)

    for (pattern in MERCHANT_PATTERNS) {
      val m = pattern.find(sanitized) ?: continue
      val candidate = m.groupValues[1]
        .trim()
        .trimEnd('.', ',', ';', '-', ':')
      if (candidate.length >= 2 &&
        !candidate.equals(bankName, ignoreCase = true) &&
        !candidate.startsWith("A/c", ignoreCase = true) &&
        !candidate.startsWith("XX", ignoreCase = true) &&
        !candidate.all { it.isDigit() }
      ) {
        return candidate.take(40)
      }
    }
    return ""
  }

  private fun extractDateTime(text: String, fallbackDate: LocalDate): Pair<LocalDate, String?> {
    val timeMatch = TIME_PATTERN.find(text)?.groupValues?.getOrNull(1)?.trim()

    val formatters = listOf(
      DateTimeFormatter.ISO_LOCAL_DATE,
      DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
      DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
      DateTimeFormatter.ofPattern("d-M-yyyy", Locale.ENGLISH),
      DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH),
      DateTimeFormatter.ofPattern("dd-MM-yy", Locale.ENGLISH),
      DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ENGLISH),
      DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH),
      DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yyyy").toFormatter(Locale.ENGLISH),
      DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yy").toFormatter(Locale.ENGLISH),
      DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yy").toFormatter(Locale.ENGLISH),
      DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM")
        .parseDefaulting(ChronoField.YEAR, fallbackDate.year.toLong())
        .toFormatter(Locale.ENGLISH),
    )

    for (pattern in DATE_PATTERNS) {
      val match = pattern.find(text) ?: continue
      val raw = match.groupValues[1].trim()
      for (fmt in formatters) {
        val parsed = runCatching { LocalDate.parse(raw, fmt) }.getOrNull()
        if (parsed != null) return parsed to timeMatch
      }
    }
    return fallbackDate to timeMatch
  }

  private fun inferCategory(
    text: String,
    txType: TransactionType,
    isRefund: Boolean,
    categories: List<CategoryEntity>,
  ): Pair<String, CategoryEntity?> {
    val lower = text.lowercase(Locale.ENGLISH)

    val key = when {
      txType == TransactionType.INCOME -> when {
        isRefund -> "income_other"
        containsAny(lower, "salary", "payroll", "wages", "stipend") -> "salary"
        containsAny(lower, "interest", "dividend", "yield") -> "interest"
        containsAny(lower, "freelance", "consulting", "payout", "upwork", "fiverr") -> "freelance"
        containsAny(lower, "business", "invoice", "merchant settlement", "sales") -> "business"
        else -> "income_other"
      }

      containsAny(lower, "emi", "loan", "bajaj finserv", "mortgage", "repayment", "credit card bill") -> "emi_and_loans"
      containsAny(
        lower,
        "swiggy", "zomato", "talabat", "deliveroo", "uber eats", "food", "restaurant", "cafe",
        "coffee", "starbucks", "mcdonald", "kfc", "domino", "pizza", "bakery", "supermarket",
        "grocery", "groceries", "bigbasket", "zepto", "blinkit", "instamart", "dmart", "lulu",
        "carrefour", "sultan center", "fresh", "dinner", "lunch",
      ) -> "food"

      containsAny(
        lower,
        "rent", "electricity", "water", "gas", "bescom", "tneb", "broadband", "fiber", "wifi",
        "internet", "airtel", "jio", "vodafone", "ooredoo", "zain", "stc", "utility", "bill",
        "mobile recharge", "dth",
      ) -> "rent_and_bills"

      containsAny(
        lower,
        "uber", "ola", "careem", "rapido", "metro", "bus", "train", "irctc", "flight", "airline",
        "petrol", "diesel", "fuel", "shell", "hpcl", "bpcl", "indian oil", "parking", "fastag", "toll",
      ) -> "transport"

      containsAny(
        lower,
        "pharmacy", "apollo", "medplus", "hospital", "clinic", "doctor", "diagnostic", "dental",
        "medical", "pharmeasy", "1mg", "health",
      ) -> "medical"

      containsAny(
        lower,
        "amazon", "flipkart", "myntra", "noon", "ikea", "ajio", "meesho", "mall", "store",
        "shopping", "apparel", "electronics",
      ) -> "shopping"

      containsAny(
        lower,
        "netflix", "spotify", "hotstar", "prime video", "youtube", "bookmyshow", "pvr", "cinema",
        "movie", "concert", "playstation", "steam", "entertainment",
      ) -> "entertainment"

      containsAny(
        lower,
        "school", "college", "university", "tuition", "coursera", "udemy", "exam", "fee", "books",
      ) -> "education"

      else -> "expense_other"
    }

    val expectedCatType = when (txType) {
      TransactionType.INCOME -> CategoryType.INCOME
      TransactionType.EXPENSE -> CategoryType.EXPENSE
      TransactionType.SAVINGS_ALLOCATION -> CategoryType.SAVINGS
    }
    val matched = categories.firstOrNull { it.nameKey == key && it.type == expectedCatType }
      ?: categories.firstOrNull { it.type == expectedCatType }
      ?: categories.firstOrNull()
    return (matched?.nameKey ?: key) to matched
  }

  private fun containsAny(haystack: String, vararg terms: String): Boolean {
    return terms.any { haystack.contains(it) }
  }

  private fun buildDeduplicationKey(
    paymentRef: String?,
    amount: BigDecimal,
    currencyCode: String,
    txType: TransactionType,
    dateEpochDay: Long,
    merchant: String,
  ): String {
    val amtStr = amount.stripTrailingZeros().toPlainString()
    val normMerchant = merchant.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "").take(20)
    val rawSeed = if (!paymentRef.isNullOrBlank()) {
      "ref|${paymentRef.lowercase(Locale.ENGLISH)}|$amtStr|${currencyCode.uppercase()}|${txType.name}"
    } else {
      "sig|$dateEpochDay|$amtStr|${currencyCode.uppercase()}|${txType.name}|$normMerchant"
    }
    return "autodetect_${sha256Short(rawSeed)}"
  }

  private fun isDuplicateInExisting(
    dedupKey: String,
    paymentRef: String?,
    amount: BigDecimal,
    currencyCode: String,
    txType: TransactionType,
    dateEpochDay: Long,
    merchant: String,
    existingTransactions: List<TransactionEntity>,
  ): Boolean {
    val normMerchant = merchant.lowercase(Locale.ENGLISH).trim()
    return existingTransactions.any { existing ->
      if (existing.deduplicationKey == dedupKey) return@any true
      if (!paymentRef.isNullOrBlank() &&
        existing.description.contains(paymentRef, ignoreCase = true) &&
        existing.amount.compareTo(amount) == 0 &&
        existing.currencyCode.equals(currencyCode, ignoreCase = true)
      ) {
        return@any true
      }
      val sameCore = existing.transactionDateEpochDay == dateEpochDay &&
        existing.type == txType &&
        existing.currencyCode.equals(currencyCode, ignoreCase = true) &&
        existing.amount.compareTo(amount) == 0
      if (!sameCore) return@any false

      if (normMerchant.isEmpty()) {
        true
      } else {
        existing.description.lowercase(Locale.ENGLISH).contains(normMerchant.take(12))
      }
    }
  }

  private fun sha256Short(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString("") { "%02x".format(it) }
  }
}
