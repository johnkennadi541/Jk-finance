package com.skyplus.finance.domain.shopping

import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import javax.inject.Inject
import kotlin.math.min

data class ProductCatalogEntry(
  val canonicalKey: String,
  val displayEn: String,
  val displayTa: String,
  val category: String,
  val defaultUnit: String,
  val tanglishAliases: List<String>,
)

enum class SearchMatchType {
  EXACT_OR_CONTAINS,
  TAMIL_SCRIPT,
  TANGLISH_PHONETIC,
  TYPO_CORRECTED,
}

data class SmartProductSearchResult(
  val canonicalKey: String,
  val displayEn: String,
  val displayTa: String,
  val category: String,
  val suggestedUnit: String,
  val matchType: SearchMatchType,
  val relevanceScore: Int,
  val savedShopPriceCount: Int = 0,
  val lowestSavedPriceLabel: String? = null,
)

/**
 * Smart Product Search Engine operating strictly on user-saved shopping items and
 * user-recorded shop prices (no preloaded sample product catalogue). Supports any
 * script/language as well as phonetic & bounded-typo matching against user records.
 */
class SmartProductSearchEngine @Inject constructor() {

  fun search(
    query: String,
    existingItems: List<ShoppingItemEntity> = emptyList(),
    savedShopPrices: List<ShoppingShopPriceEntity> = emptyList(),
    maxResults: Int = 20,
  ): List<SmartProductSearchResult> {
    val trimmed = query.trim()
    val pricesByKey = savedShopPrices.groupBy { normalizeProductKey(it.itemNameNormalized.ifBlank { it.productDisplayName }) }

    val allCandidates = buildExtraEntries(existingItems, savedShopPrices)
    if (allCandidates.isEmpty()) return emptyList()

    if (trimmed.isBlank()) {
      return allCandidates.take(maxResults).map { entry ->
        val entryPrices = pricesByKey[entry.canonicalKey].orEmpty()
        val minPrice = entryPrices.minByOrNull { it.price }
        SmartProductSearchResult(
          canonicalKey = entry.canonicalKey,
          displayEn = entry.displayEn,
          displayTa = entry.displayTa,
          category = entry.category,
          suggestedUnit = entry.defaultUnit,
          matchType = SearchMatchType.EXACT_OR_CONTAINS,
          relevanceScore = if (entryPrices.isNotEmpty()) 90 else 50,
          savedShopPriceCount = entryPrices.size,
          lowestSavedPriceLabel = minPrice?.let { "${it.shopName}: ${it.price.toPlainString()} ${it.currencyCode}/${it.unit}" },
        )
      }
    }

    val normalizedQuery = normalizeText(trimmed)
    val phoneticQuery = phoneticKey(normalizedQuery)

    val scored = allCandidates.mapNotNull { entry ->
      val terms = buildList {
        add(entry.displayEn)
        if (entry.displayTa != entry.displayEn) add(entry.displayTa)
        add(entry.canonicalKey.replace("_", " "))
        addAll(entry.tanglishAliases)
      }

      var bestScore = 0
      var bestType: SearchMatchType? = null

      for (term in terms) {
        val normTerm = normalizeText(term)
        val isTamilTerm = containsTamilScript(term)

        // 1. Exact or prefix match
        when {
          normTerm == normalizedQuery -> {
            val s = 100
            if (s > bestScore) {
              bestScore = s
              bestType = if (isTamilTerm) SearchMatchType.TAMIL_SCRIPT else SearchMatchType.EXACT_OR_CONTAINS
            }
          }

          normTerm.startsWith(normalizedQuery) || normTerm.contains(normalizedQuery) -> {
            val s = if (normTerm.startsWith(normalizedQuery)) 92 else 82
            if (s > bestScore) {
              bestScore = s
              bestType = if (isTamilTerm) SearchMatchType.TAMIL_SCRIPT else SearchMatchType.EXACT_OR_CONTAINS
            }
          }
        }

        // 2. Phonetic match
        if (phoneticQuery.length >= 2) {
          val pTerm = phoneticKey(normTerm)
          if (pTerm == phoneticQuery) {
            val s = 88
            if (s > bestScore) {
              bestScore = s
              bestType = SearchMatchType.TANGLISH_PHONETIC
            }
          } else if (pTerm.contains(phoneticQuery) || levenshtein(pTerm, phoneticQuery) <= 1 && phoneticQuery.length >= 4) {
            val s = 76
            if (s > bestScore) {
              bestScore = s
              bestType = SearchMatchType.TANGLISH_PHONETIC
            }
          }
        }

        // 3. Typo correction via bounded Levenshtein distance
        if (!isTamilTerm || containsTamilScript(trimmed)) {
          val maxAllowedDist = when {
            normalizedQuery.length <= 3 -> 1
            normalizedQuery.length <= 6 -> 2
            else -> 3
          }
          val words = normTerm.split(" ") + normTerm
          for (w in words) {
            val dist = levenshtein(normalizedQuery, w)
            if (dist in 1..maxAllowedDist) {
              val s = (74 - dist * 10).coerceAtLeast(40)
              if (s > bestScore) {
                bestScore = s
                bestType = SearchMatchType.TYPO_CORRECTED
              }
            }
          }
        }
      }

      if (bestType == null) return@mapNotNull null

      val entryPrices = pricesByKey[entry.canonicalKey]
        ?: pricesByKey[normalizeProductKey(entry.displayEn)]
        ?: emptyList()
      val minPrice = entryPrices.minByOrNull { it.price }

      SmartProductSearchResult(
        canonicalKey = entry.canonicalKey,
        displayEn = entry.displayEn,
        displayTa = entry.displayTa,
        category = entry.category,
        suggestedUnit = entry.defaultUnit,
        matchType = bestType,
        relevanceScore = bestScore,
        savedShopPriceCount = entryPrices.size,
        lowestSavedPriceLabel = minPrice?.let { "${it.shopName}: ${it.price.toPlainString()} ${it.currencyCode}/${it.unit}" },
      )
    }

    return scored
      .sortedWith(compareByDescending<SmartProductSearchResult> { it.relevanceScore }.thenBy { it.displayEn })
      .take(maxResults)
  }

  fun normalizeProductKey(name: String): String {
    val norm = normalizeText(name)
    return norm.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifBlank { "custom_item" }
  }

  private fun buildExtraEntries(
    existingItems: List<ShoppingItemEntity>,
    savedShopPrices: List<ShoppingShopPriceEntity>,
  ): List<ProductCatalogEntry> {
    val knownKeys = mutableSetOf<String>()
    val extras = mutableListOf<ProductCatalogEntry>()
    for (item in existingItems) {
      val key = normalizeProductKey(item.name)
      if (key !in knownKeys) {
        knownKeys.add(key)
        extras.add(
          ProductCatalogEntry(
            canonicalKey = key,
            displayEn = item.name,
            displayTa = item.name,
            category = item.categoryName,
            defaultUnit = item.unit,
            tanglishAliases = emptyList(),
          ),
        )
      }
    }
    for (p in savedShopPrices) {
      val key = normalizeProductKey(p.productDisplayName)
      if (key !in knownKeys) {
        knownKeys.add(key)
        extras.add(
          ProductCatalogEntry(
            canonicalKey = key,
            displayEn = p.productDisplayName,
            displayTa = p.productDisplayName,
            category = "Custom",
            defaultUnit = p.unit,
            tanglishAliases = emptyList(),
          ),
        )
      }
    }
    return extras
  }

  private fun normalizeText(input: String): String =
    input.trim().lowercase().replace(Regex("\\s+"), " ")

  private fun containsTamilScript(text: String): Boolean =
    text.any { it in '\u0B80'..'\u0BFF' }

  /**
   * Canonicalizes English + Tanglish phonetic variants so spellings like
   * thakkali / takkali / takali collapse to the same key ("tkli").
   */
  internal fun phoneticKey(raw: String): String {
    var s = raw.lowercase().replace(Regex("[^a-z]"), "")
    if (s.isEmpty()) return ""
    s = s.replace("th", "t")
      .replace("dh", "d")
      .replace("ph", "f")
      .replace("sh", "s")
      .replace("zh", "l")
      .replace("kh", "k")
      .replace("bh", "b")
      .replace("c", "k")
      .replace("w", "v")
      .replace("z", "s")
      .replace(Regex("(.)\\1+"), "$1") // collapse double letters (tt -> t, pp -> p, aa -> a, oo -> o)
    if (s.length <= 2) return s
    val first = s.first()
    val rest = s.drop(1).replace(Regex("[aeiou]"), "")
    return (first + rest).take(8)
  }

  internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in a.indices) {
      curr[0] = i + 1
      for (j in b.indices) {
        val cost = if (a[i] == b[j]) 0 else 1
        curr[j + 1] = min(min(curr[j] + 1, prev[j + 1] + 1), prev[j] + cost)
      }
      System.arraycopy(curr, 0, prev, 0, b.length + 1)
    }
    return prev[b.length]
  }
}
