package com.skyplus.finance.domain.monetization

/**
 * Architecture boundary for future SKYPLUS ONE capability expansion.
 * Phase 3 remains strictly ₹0 (free, zero ads, zero billing SDKs, zero paywalls).
 */
enum class SkyPlusTier {
  FREE_CORE,
  SKYPLUS_ONE,
}

data class SkyPlusOneStatus(
  val activeTier: SkyPlusTier = SkyPlusTier.FREE_CORE,
  val isUpgradeRequiredNow: Boolean = false,
  val plannedCapabilities: List<String> = listOf(
    "Automated Multi-Device Encrypted Sync",
    "Extended Multi-Year Wealth Archive",
    "Family Portfolio Collaboration",
  ),
)

interface SkyPlusOneEntitlementGateway {
  fun currentStatus(): SkyPlusOneStatus
}

class OfflineFreeEntitlementGateway : SkyPlusOneEntitlementGateway {
  override fun currentStatus(): SkyPlusOneStatus = SkyPlusOneStatus()
}
