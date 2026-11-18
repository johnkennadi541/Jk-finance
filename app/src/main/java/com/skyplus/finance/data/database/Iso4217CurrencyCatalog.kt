package com.skyplus.finance.data.database

import com.skyplus.finance.data.entities.CurrencyEntity
import java.util.Currency
import java.util.Locale

object Iso4217CurrencyCatalog {

  private val symbolOverrides = mapOf(
    "INR" to "₹",
    "KWD" to "د.ك",
    "USD" to "$",
    "EUR" to "€",
    "GBP" to "£",
    "JPY" to "¥",
    "CNY" to "¥",
    "AED" to "د.إ",
    "SAR" to "﷼",
    "QAR" to "ر.ق",
    "BHD" to ".د.ب",
    "OMR" to "ر.ع.",
    "JOD" to "د.أ",
    "SGD" to "S$",
    "AUD" to "A$",
    "CAD" to "C$",
    "CHF" to "CHF",
    "MYR" to "RM",
    "LKR" to "Rs",
    "KRW" to "₩",
    "THB" to "฿",
    "IDR" to "Rp",
    "PHP" to "₱",
    "VND" to "₫",
    "BDT" to "৳",
    "PKR" to "₨",
    "ZAR" to "R",
    "NGN" to "₦",
    "KES" to "KSh",
    "EGP" to "E£",
    "TRY" to "₺",
    "RUB" to "₽",
    "BRL" to "R$",
    "MXN" to "Mex$",
    "SEK" to "kr",
    "NOK" to "kr",
    "DKK" to "kr",
    "PLN" to "zł",
    "HKD" to "HK$",
    "TWD" to "NT$",
    "NZD" to "NZ$",
  )

  private val excludedPseudoCodes = setOf(
    "XAU", "XAG", "XPT", "XPD", "XBA", "XBB", "XBC", "XBD",
    "XTS", "XXX", "XDR", "XSU", "XUA", "BOV", "CHE", "CHW",
    "CLF", "COU", "MXV", "USN", "UYI", "UYW",
  )

  fun getAllOfficialCurrencies(): List<CurrencyEntity> {
    val activeDefault = setOf("INR", "KWD", "USD", "EUR", "GBP", "AED", "SGD", "JPY")
    return Currency.getAvailableCurrencies()
      .asSequence()
      .filter { currency ->
        val code = currency.currencyCode
        code.length == 3 &&
          currency.defaultFractionDigits >= 0 &&
          !excludedPseudoCodes.contains(code)
      }
      .map { currency ->
        val code = currency.currencyCode
        val symbol = symbolOverrides[code] ?: runCatching {
          currency.getSymbol(Locale.ENGLISH)
        }.getOrDefault(code)
        val numeric = runCatching {
          currency.numericCode.toString().padStart(3, '0')
        }.getOrDefault("000")
        val name = runCatching {
          currency.getDisplayName(Locale.ENGLISH)
        }.getOrDefault(code)
        CurrencyEntity(
          code = code,
          numericCode = numeric,
          displayName = name,
          symbol = symbol,
          decimalPrecision = currency.defaultFractionDigits.coerceIn(0, 3),
          isActiveInWallet = activeDefault.contains(code),
        )
      }
      .sortedBy { it.code }
      .toList()
  }

  fun suggestDefaultCurrencyCode(locale: Locale = Locale.getDefault()): String {
    val country = locale.country.uppercase(Locale.ROOT)
    return when (country) {
      "KW" -> "KWD"
      "IN" -> "INR"
      else -> runCatching {
        val c = Currency.getInstance(locale)
        if (c != null && c.defaultFractionDigits >= 0 && !excludedPseudoCodes.contains(c.currencyCode)) {
          c.currencyCode
        } else {
          "INR"
        }
      }.getOrDefault("INR")
    }
  }
}
