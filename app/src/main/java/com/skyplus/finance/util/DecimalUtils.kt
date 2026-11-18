package com.skyplus.finance.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object DecimalUtils {
  val FINANCIAL_MATH_CONTEXT: MathContext = MathContext(18, RoundingMode.HALF_UP)
  val ZERO: BigDecimal = BigDecimal.ZERO

  fun fromStringOrZero(value: String?): BigDecimal {
    if (value.isNullOrBlank()) return BigDecimal.ZERO
    return try {
      BigDecimal(value.trim())
    } catch (_: NumberFormatException) {
      BigDecimal.ZERO
    }
  }

  fun parsePositiveOrNull(value: String): BigDecimal? {
    val cleaned = value.trim().replace(",", "")
    if (cleaned.isEmpty()) return null
    return try {
      val decimal = BigDecimal(cleaned)
      if (decimal > BigDecimal.ZERO) decimal else null
    } catch (_: NumberFormatException) {
      null
    }
  }

  fun parseNonNegativeOrNull(value: String): BigDecimal? {
    val cleaned = value.trim().replace(",", "")
    if (cleaned.isEmpty()) return null
    return try {
      val decimal = BigDecimal(cleaned)
      if (decimal >= BigDecimal.ZERO) decimal else null
    } catch (_: NumberFormatException) {
      null
    }
  }

  fun scaleToCurrency(amount: BigDecimal, decimalPrecision: Int): BigDecimal {
    val safeScale = decimalPrecision.coerceIn(0, 4)
    return amount.setScale(safeScale, RoundingMode.HALF_UP)
  }

  fun toCanonicalString(amount: BigDecimal): String =
    amount.stripTrailingZeros().let { stripped ->
      if (stripped.compareTo(BigDecimal.ZERO) == 0) "0" else stripped.toPlainString()
    }

  fun divideExact(numerator: BigDecimal, denominator: BigDecimal, scale: Int): BigDecimal {
    if (denominator.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
    return numerator.divide(denominator, scale, RoundingMode.HALF_UP)
  }

  fun percentage(part: BigDecimal, whole: BigDecimal): BigDecimal {
    if (whole.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO
    return part.multiply(BigDecimal("100"), FINANCIAL_MATH_CONTEXT)
      .divide(whole, 2, RoundingMode.HALF_UP)
  }
}
