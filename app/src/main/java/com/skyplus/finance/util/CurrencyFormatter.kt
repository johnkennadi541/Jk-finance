package com.skyplus.finance.util

import com.skyplus.finance.data.entities.CurrencyEntity
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object CurrencyFormatter {

  fun format(
    amount: BigDecimal,
    currencyCode: String,
    symbol: String? = null,
    decimalPrecision: Int = 2,
    locale: Locale = Locale.ENGLISH,
  ): String {
    val safePrecision = decimalPrecision.coerceIn(0, 3)
    val scaled = DecimalUtils.scaleToCurrency(amount.abs(), safePrecision)
    val symbols = DecimalFormatSymbols(locale)
    val pattern = buildString {
      append("#,##0")
      if (safePrecision > 0) {
        append(".")
        repeat(safePrecision) { append("0") }
      }
    }
    val formatter = DecimalFormat(pattern, symbols)
    val formattedNumber = formatter.format(scaled)
    val resolvedSymbol = symbol?.takeIf { it.isNotBlank() } ?: currencyCode
    val signPrefix = if (amount < BigDecimal.ZERO) "-" else ""
    return "$signPrefix$resolvedSymbol $formattedNumber"
  }

  fun formatWithEntity(
    amount: BigDecimal,
    currency: CurrencyEntity?,
    fallbackCode: String,
  ): String = format(
    amount = amount,
    currencyCode = currency?.code ?: fallbackCode,
    symbol = currency?.symbol ?: fallbackCode,
    decimalPrecision = currency?.decimalPrecision ?: 2,
  )
}
