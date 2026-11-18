package com.skyplus.finance.presentation.intelligence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.designsystem.ForecastChartPoint
import com.skyplus.finance.designsystem.SkyPlusAnimatedProgressBar
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusExpandableCard
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.designsystem.SkyPlusForecastCashflowChart
import com.skyplus.finance.designsystem.SkyPlusHealthScoreGauge
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.designsystem.SkyPlusWeekdayWeekendSplitBar
import com.skyplus.finance.domain.intelligence.AlertSeverity
import com.skyplus.finance.domain.intelligence.BudgetRecommendation
import com.skyplus.finance.domain.intelligence.BudgetRecommendationAction
import com.skyplus.finance.domain.intelligence.CashflowForecastReport
import com.skyplus.finance.domain.intelligence.CategorySpendTrend
import com.skyplus.finance.domain.intelligence.DimensionImpactStatus
import com.skyplus.finance.domain.intelligence.EmergencyFundRecommendation
import com.skyplus.finance.domain.intelligence.FinancialHealthReport
import com.skyplus.finance.domain.intelligence.FinancialHealthStatus
import com.skyplus.finance.domain.intelligence.ForecastConfidence
import com.skyplus.finance.domain.intelligence.ForecastStatus
import com.skyplus.finance.domain.intelligence.GoalContributionRecommendation
import com.skyplus.finance.domain.intelligence.HealthScoreBand
import com.skyplus.finance.domain.intelligence.SpendingDayPreference
import com.skyplus.finance.domain.intelligence.SpendingIntelligenceReport
import com.skyplus.finance.util.CurrencyFormatter
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun FinancialHealthScoreCard(
  report: FinancialHealthReport,
  onOpenFullAnalytics: (() -> Unit)? = null,
  showDetailedMetrics: Boolean = false,
  currencyEntity: CurrencyEntity? = null,
  modifier: Modifier = Modifier,
) {
  val accentColor = when (report.band) {
    HealthScoreBand.EXCELLENT -> SkyPlusBrandColors.EmeraldGreen
    HealthScoreBand.GOOD -> SkyPlusBrandColors.RoyalBlue
    HealthScoreBand.FAIR -> SkyPlusBrandColors.WarningAmber
    HealthScoreBand.CRITICAL -> SkyPlusBrandColors.ErrorCoral
    null -> SkyPlusBrandColors.RoyalBlue
  }
  var expanded by remember(showDetailedMetrics) { mutableStateOf(showDetailedMetrics) }

  SkyPlusPortfolioCard(modifier = modifier.testTag("financial_health_score_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.HealthAndSafety, contentDescription = null, tint = accentColor)
        Text("Financial Health Score", style = MaterialTheme.typography.titleMedium)
      }
      if (onOpenFullAnalytics != null) {
        TextButton(onClick = onOpenFullAnalytics, contentPadding = PaddingValues(horizontal = 8.dp)) {
          Text("Full Analytics", color = SkyPlusBrandColors.RoyalBlue)
        }
      }
    }

    if (report.status == FinancialHealthStatus.INSUFFICIENT_DATA || report.overallScore == null) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        SkyPlusHealthScoreGauge(
          score = null,
          accentColor = accentColor,
          centerSubtitle = "Need Data",
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Surface(
            color = SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.12f),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "Insufficient Data",
              style = MaterialTheme.typography.labelSmall,
              color = SkyPlusBrandColors.RoyalBlue,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
          Text(
            text = report.primaryExplanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      report.priorityImprovements.take(2).forEach { tip ->
        Text(
          text = "• $tip",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        SkyPlusHealthScoreGauge(
          score = report.overallScore,
          accentColor = accentColor,
          centerSubtitle = "/ 100",
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              color = accentColor.copy(alpha = 0.14f),
              shape = RoundedCornerShape(8.dp),
            ) {
              Text(
                text = report.band?.label ?: "Computed",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
            report.scoreChangeDelta?.let { delta ->
              val deltaColor = when {
                delta > 0 -> SkyPlusBrandColors.EmeraldGreen
                delta < 0 -> SkyPlusBrandColors.ErrorCoral
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              }
              val deltaLabel = when {
                delta > 0 -> "+$delta pts"
                delta < 0 -> "$delta pts"
                else -> "0 pts"
              }
              Surface(
                color = deltaColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
              ) {
                Text(
                  text = deltaLabel,
                  style = MaterialTheme.typography.labelSmall.copy(fontFamily = SkyPlusFonts.NumericMono),
                  color = deltaColor,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
              }
            }
          }
          Text(
            text = report.primaryExplanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (report.scoreChangeExplanation.isNotBlank()) {
            Text(
              text = report.scoreChangeExplanation,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        report.pillars.forEach { pillar ->
          val ratio = if (pillar.maxScore > 0) pillar.score.toFloat() / pillar.maxScore.toFloat() else 0f
          val barColor = when {
            ratio >= 0.75f -> SkyPlusBrandColors.EmeraldGreen
            ratio >= 0.50f -> SkyPlusBrandColors.RoyalBlue
            ratio >= 0.25f -> SkyPlusBrandColors.WarningAmber
            else -> SkyPlusBrandColors.ErrorCoral
          }
          Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(pillar.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
              Text(
                text = "${pillar.score}/${pillar.maxScore} • ${pillar.metricSummary}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = SkyPlusFonts.NumericMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            SkyPlusAnimatedProgressBar(targetProgress = ratio, barColor = barColor, heightDp = 6)
          }
        }
      }

      if (!showDetailedMetrics && report.dimensions.isNotEmpty()) {
        TextButton(
          onClick = { expanded = !expanded },
          contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
          modifier = Modifier.testTag("health_expand_toggle"),
        ) {
          Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = SkyPlusBrandColors.RoyalBlue,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = if (expanded) "Hide 8-Dimension Breakdown" else "View 8 Dimensions & Actions",
            color = SkyPlusBrandColors.RoyalBlue,
          )
        }
      }

      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          HorizontalDivider()
          val m = report.metrics
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Monthly Surplus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = CurrencyFormatter.formatWithEntity(m.netCashSurplus, currencyEntity, m.evaluatedCurrencyCode),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
              )
            }
            Column(modifier = Modifier.weight(1f)) {
              Text("EMI / Income", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = "${m.emiToIncomePercent}%",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
              )
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
              Text("Emergency Reserve", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                text = CurrencyFormatter.formatWithEntity(m.emergencyFundSaved, currencyEntity, m.evaluatedCurrencyCode),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
              )
            }
          }

          if (report.dimensions.isNotEmpty()) {
            HorizontalDivider()
            Text(
              text = "8 Financial Wellness Dimensions",
              style = MaterialTheme.typography.labelLarge,
              color = SkyPlusBrandColors.RoyalBlue,
            )
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("health_8_dimensions_section"),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              report.dimensions.forEach { dim ->
                val dimColor = when (dim.status) {
                  DimensionImpactStatus.STRENGTH -> SkyPlusBrandColors.EmeraldGreen
                  DimensionImpactStatus.BALANCED -> SkyPlusBrandColors.RoyalBlue
                  DimensionImpactStatus.NEEDS_ATTENTION -> SkyPlusBrandColors.WarningAmber
                  DimensionImpactStatus.BASELINE_FORMING -> SkyPlusBrandColors.Teal
                }
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(10.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(dim.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                      Surface(color = dimColor.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                          text = dim.status.label,
                          style = MaterialTheme.typography.labelSmall,
                          color = dimColor,
                          fontWeight = FontWeight.SemiBold,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                      Text(
                        text = "${dim.score}/100",
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = SkyPlusFonts.NumericMono),
                        color = dimColor,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                  }
                  SkyPlusAnimatedProgressBar(
                    targetProgress = (dim.score / 100f).coerceIn(0f, 1f),
                    barColor = dimColor,
                    heightDp = 5,
                  )
                  Text(
                    text = "${dim.metricSummary} — ${dim.explanation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }

          if (report.positiveContributors.isNotEmpty() || report.negativeContributors.isNotEmpty()) {
            HorizontalDivider()
            Text(
              text = "Key Score Drivers",
              style = MaterialTheme.typography.labelLarge,
              color = SkyPlusBrandColors.RoyalBlue,
            )
            Column(
              modifier = Modifier.fillMaxWidth().testTag("health_contributors_section"),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              report.positiveContributors.forEach { item ->
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Icon(
                    Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = null,
                    tint = SkyPlusBrandColors.EmeraldGreen,
                    modifier = Modifier.size(16.dp),
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "${item.title} (${item.impactSummary})",
                      style = MaterialTheme.typography.labelMedium,
                      color = SkyPlusBrandColors.EmeraldGreen,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      text = item.explanation,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
              report.negativeContributors.forEach { item ->
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Icon(
                    Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null,
                    tint = SkyPlusBrandColors.WarningAmber,
                    modifier = Modifier.size(16.dp),
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "${item.title} (${item.impactSummary})",
                      style = MaterialTheme.typography.labelMedium,
                      color = SkyPlusBrandColors.WarningAmber,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      text = item.explanation,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }

          if (report.scoreHistory.isNotEmpty()) {
            HorizontalDivider()
            Text(
              text = "Score History & Trend",
              style = MaterialTheme.typography.labelLarge,
              color = SkyPlusBrandColors.RoyalBlue,
            )
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("health_score_history_row"),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              report.scoreHistory.forEach { pt ->
                val ptColor = when {
                  pt.score >= 80 -> SkyPlusBrandColors.EmeraldGreen
                  pt.score >= 60 -> SkyPlusBrandColors.RoyalBlue
                  pt.score >= 40 -> SkyPlusBrandColors.WarningAmber
                  else -> SkyPlusBrandColors.ErrorCoral
                }
                Column(
                  modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ptColor.copy(alpha = 0.10f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Text(
                    text = pt.monthLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = "${pt.score}",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
                    color = ptColor,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text = "${pt.savingsRatePercent.setScale(0, java.math.RoundingMode.HALF_UP)}% sav",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }

          if (report.practicalActions.isNotEmpty()) {
            HorizontalDivider()
            Text(
              text = "Personalized Action Plan",
              style = MaterialTheme.typography.labelLarge,
              color = SkyPlusBrandColors.RoyalBlue,
            )
            report.practicalActions.forEach { action ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(10.dp))
                  .background(SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.08f))
                  .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Icon(
                  Icons.Filled.Lightbulb,
                  contentDescription = null,
                  tint = SkyPlusBrandColors.RoyalBlue,
                  modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(
                    text = "${action.actionTitle} • ${action.dimensionTitle}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    text = action.actionDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          } else if (report.priorityImprovements.isNotEmpty()) {
            Text("Priority Next Actions", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.RoyalBlue)
            report.priorityImprovements.forEach { tip ->
              Text("• $tip", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }

          Text(
            text = "Personal financial wellness indicator based on your recorded data — not a credit score.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
fun SmartAlertsCard(
  spendingReport: SpendingIntelligenceReport,
  currencyEntity: CurrencyEntity?,
  modifier: Modifier = Modifier,
) {
  val alerts = spendingReport.alerts
  SkyPlusPortfolioCard(modifier = modifier.testTag("smart_alerts_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
          imageVector = if (alerts.isEmpty()) Icons.Filled.CheckCircle else Icons.Filled.NotificationsActive,
          contentDescription = null,
          tint = if (alerts.isEmpty()) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.WarningAmber,
        )
        Text("Smart Financial Alerts", style = MaterialTheme.typography.titleMedium)
      }
      Surface(
        color = (if (alerts.isEmpty()) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.WarningAmber).copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text(
          text = if (alerts.isEmpty()) "All Clear" else "${alerts.size} Active",
          style = MaterialTheme.typography.labelSmall,
          color = if (alerts.isEmpty()) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.WarningAmber,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    if (alerts.isEmpty()) {
      Text(
        text = "No unusual spending spikes, budget overruns, or unpaid bills/EMIs due in the next 7 days.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (!spendingReport.hasSufficientAnomalyBaseline) {
        Text(
          text = "Spending anomaly alerts unlock automatically once 2+ prior months of category history exist.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      alerts.take(5).forEach { alert ->
        val badgeColor = when (alert.severity) {
          AlertSeverity.HIGH -> SkyPlusBrandColors.ErrorCoral
          AlertSeverity.WARNING -> SkyPlusBrandColors.WarningAmber
          AlertSeverity.INFO -> SkyPlusBrandColors.RoyalBlue
        }
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(badgeColor.copy(alpha = 0.08f))
            .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.Warning, contentDescription = null, tint = badgeColor, modifier = Modifier.size(16.dp))
              Text(
                text = alert.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            alert.amount?.let { amt ->
              Text(
                text = CurrencyFormatter.formatWithEntity(amt, currencyEntity, alert.currencyCode),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = SkyPlusFonts.NumericMono),
                color = badgeColor,
              )
            }
          }
          Text(alert.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = alert.recommendation,
            style = MaterialTheme.typography.labelSmall,
            color = SkyPlusBrandColors.RoyalBlue,
            fontWeight = FontWeight.Medium,
          )
        }
      }
    }
  }
}

@Composable
fun CashflowForecastCard(
  forecast: CashflowForecastReport,
  currencyEntity: CurrencyEntity?,
  modifier: Modifier = Modifier,
) {
  val code = forecast.evaluatedCurrencyCode
  SkyPlusPortfolioCard(modifier = modifier.testTag("cashflow_forecast_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Timeline, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
        Text("Income & Expense Forecast", style = MaterialTheme.typography.titleMedium)
      }
      forecast.confidence?.let { conf ->
        val cColor = when (conf) {
          ForecastConfidence.HIGH -> SkyPlusBrandColors.EmeraldGreen
          ForecastConfidence.MEDIUM -> SkyPlusBrandColors.RoyalBlue
          ForecastConfidence.LOW -> SkyPlusBrandColors.WarningAmber
        }
        Surface(color = cColor.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
          Text(
            text = conf.label,
            style = MaterialTheme.typography.labelSmall,
            color = cColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
    }

    if (forecast.status == ForecastStatus.INSUFFICIENT_DATA || forecast.endOfMonthForecast == null) {
      Text(
        text = "Insufficient Data",
        style = MaterialTheme.typography.labelLarge,
        color = SkyPlusBrandColors.RoyalBlue,
      )
      Text(
        text = forecast.statusReason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val eom = forecast.endOfMonthForecast
      if (forecast.hasIrregularOrSeasonalIncome) {
        Surface(
          color = SkyPlusBrandColors.WarningAmber.copy(alpha = 0.14f),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = forecast.incomePatternNote,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(10.dp),
          )
        }
      } else {
        Text(
          text = forecast.incomePatternNote,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Realized Income", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = CurrencyFormatter.formatWithEntity(eom.realizedIncomeToDate, currencyEntity, code),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
            color = SkyPlusBrandColors.EmeraldGreen,
          )
          Text(
            text = "+${CurrencyFormatter.formatWithEntity(eom.projectedRemainingIncome, currencyEntity, code)} expected",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text("Est. Month-End Balance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = CurrencyFormatter.formatWithEntity(eom.projectedEndOfMonthBalance, currencyEntity, code),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
            color = if (eom.projectedEndOfMonthBalance >= BigDecimal.ZERO) SkyPlusBrandColors.RoyalBlue else SkyPlusBrandColors.ErrorCoral,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "Bills/EMIs left: ${CurrencyFormatter.formatWithEntity(eom.scheduledUnpaidCommitmentsRemaining, currencyEntity, code)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      val chartPoints = buildList {
        val eomNet = eom.projectedTotalIncome.subtract(eom.projectedTotalExpenses)
        add(
          ForecastChartPoint(
            label = "This Mo",
            projectedIncome = eom.projectedTotalIncome,
            projectedExpenses = eom.projectedTotalExpenses,
            formattedIncome = CurrencyFormatter.formatWithEntity(eom.projectedTotalIncome, currencyEntity, code),
            formattedExpenses = CurrencyFormatter.formatWithEntity(eom.projectedTotalExpenses, currencyEntity, code),
            formattedNet = CurrencyFormatter.formatWithEntity(eomNet, currencyEntity, code),
            formattedEndingBalance = CurrencyFormatter.formatWithEntity(eom.projectedEndOfMonthBalance, currencyEntity, code),
            isNetPositive = eomNet >= BigDecimal.ZERO,
          ),
        )
        forecast.nextThreeMonths.forEach { m ->
          add(
            ForecastChartPoint(
              label = m.monthLabel,
              projectedIncome = m.projectedIncome,
              projectedExpenses = m.projectedExpenses,
              formattedIncome = CurrencyFormatter.formatWithEntity(m.projectedIncome, currencyEntity, code),
              formattedExpenses = CurrencyFormatter.formatWithEntity(m.projectedExpenses, currencyEntity, code),
              formattedNet = CurrencyFormatter.formatWithEntity(m.projectedNetCashflow, currencyEntity, code),
              formattedEndingBalance = CurrencyFormatter.formatWithEntity(m.projectedEndingBalance, currencyEntity, code),
              isNetPositive = m.projectedNetCashflow >= BigDecimal.ZERO,
            ),
          )
        }
      }

      SkyPlusForecastCashflowChart(points = chartPoints)
    }

    Text(
      text = forecast.estimateDisclaimer,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
fun SpendingPatternsCard(
  report: SpendingIntelligenceReport,
  currencyEntity: CurrencyEntity?,
  modifier: Modifier = Modifier,
) {
  val code = report.evaluatedCurrencyCode
  SkyPlusPortfolioCard(modifier = modifier.testTag("spending_patterns_card")) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Filled.AutoGraph, contentDescription = null, tint = SkyPlusBrandColors.EmeraldGreen)
      Text("Spending Patterns & Insights", style = MaterialTheme.typography.titleMedium)
    }

    // Weekday vs Weekend Analysis
    val ww = report.weekdayWeekendAnalysis
    if (ww.preference == SpendingDayPreference.INSUFFICIENT_DATA) {
      Text(
        text = ww.insightSummary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Weekday vs Weekend (${ww.evaluationWindowLabel})", style = MaterialTheme.typography.labelLarge)
        SkyPlusWeekdayWeekendSplitBar(
          weekdaySharePercent = ww.weekdaySharePercent.toFloat(),
          weekendSharePercent = ww.weekendSharePercent.toFloat(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(
            text = "Weekday: ${ww.weekdaySharePercent}% (${CurrencyFormatter.formatWithEntity(ww.weekdayTotal, currencyEntity, code)})",
            style = MaterialTheme.typography.labelSmall,
            color = SkyPlusBrandColors.RoyalBlue,
          )
          Text(
            text = "Weekend: ${ww.weekendSharePercent}% (${CurrencyFormatter.formatWithEntity(ww.weekendTotal, currencyEntity, code)})",
            style = MaterialTheme.typography.labelSmall,
            color = SkyPlusBrandColors.EmeraldGreen,
          )
        }
        Text(ww.insightSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    // Month-over-Month Category Comparisons
    if (report.categoryComparisons.isNotEmpty()) {
      HorizontalDivider()
      Text("This Month vs Last Month", style = MaterialTheme.typography.labelLarge)
      report.categoryComparisons.take(4).forEach { cat ->
        val badgeColor = when (cat.trend) {
          CategorySpendTrend.INCREASED -> SkyPlusBrandColors.WarningAmber
          CategorySpendTrend.DECREASED -> SkyPlusBrandColors.EmeraldGreen
          CategorySpendTrend.NEW_THIS_MONTH -> SkyPlusBrandColors.RoyalBlue
          CategorySpendTrend.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val deltaText = when (cat.trend) {
          CategorySpendTrend.NEW_THIS_MONTH -> "NEW"
          CategorySpendTrend.INCREASED -> "+${cat.deltaPercent?.toPlainString() ?: ""}%"
          CategorySpendTrend.DECREASED -> "${cat.deltaPercent?.toPlainString() ?: ""}%"
          CategorySpendTrend.UNCHANGED -> "0%"
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(cat.categoryName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
              text = "Prev: ${CurrencyFormatter.formatWithEntity(cat.previousMonthSpent, currencyEntity, code)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Column(horizontalAlignment = Alignment.End) {
            Text(
              text = CurrencyFormatter.formatWithEntity(cat.currentMonthSpent, currencyEntity, code),
              style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = deltaText,
              style = MaterialTheme.typography.labelSmall,
              color = badgeColor,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }

    // Recurring Expenses Detected
    if (report.recurringExpenses.isNotEmpty()) {
      HorizontalDivider()
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Repeat, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue, modifier = Modifier.size(16.dp))
        Text("Detected Recurring Commitments", style = MaterialTheme.typography.labelLarge)
      }
      report.recurringExpenses.take(4).forEach { rec ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text(rec.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
              text = "${rec.cadence.label} • Next est: ${LocalDate.ofEpochDay(rec.nextEstimatedDueEpochDay)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            text = CurrencyFormatter.formatWithEntity(rec.averageAmount, currencyEntity, code),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
          )
        }
      }
    }
  }
}

@Composable
fun SmartBudgetRecommendationsCard(
  recommendations: List<BudgetRecommendation>,
  currencyEntity: CurrencyEntity?,
  onApplyRecommendation: (BudgetRecommendation) -> Unit,
  modifier: Modifier = Modifier,
) {
  val actionable = recommendations.filter { it.action != BudgetRecommendationAction.KEEP_CURRENT }
  SkyPlusExpandableCard(
    title = "Smart Budget Recommendations",
    subtitle = if (actionable.isEmpty()) {
      "Your current budgets align with recent spending patterns"
    } else {
      "${actionable.size} suggestion(s) based on 3-month actuals (confirmation required)"
    },
    trailingBadge = if (actionable.isEmpty()) "Aligned" else "${actionable.size} Tips",
    trailingBadgeColor = if (actionable.isEmpty()) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.RoyalBlue,
    initiallyExpanded = actionable.isNotEmpty(),
    modifier = modifier.testTag("smart_budget_recommendations_card"),
  ) {
    if (recommendations.isEmpty()) {
      Text(
        text = "Record expenses over prior months to unlock personalized monthly budget suggestions.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      recommendations.take(5).forEach { rec ->
        val actionLabel = when (rec.action) {
          BudgetRecommendationAction.CREATE_BUDGET -> "Create Budget"
          BudgetRecommendationAction.INCREASE_LIMIT -> "Update Limit"
          BudgetRecommendationAction.LOWER_LIMIT -> "Lower Limit"
          BudgetRecommendationAction.KEEP_CURRENT -> "On Track"
        }
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue, modifier = Modifier.size(16.dp))
              Text(rec.categoryName, style = MaterialTheme.typography.titleMedium)
            }
            Text(
              text = "Suggested: ${CurrencyFormatter.formatWithEntity(rec.suggestedBudgetLimit, currencyEntity, rec.currencyCode)}",
              style = MaterialTheme.typography.labelLarge.copy(fontFamily = SkyPlusFonts.NumericMono),
              color = SkyPlusBrandColors.RoyalBlue,
            )
          }
          Text(rec.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (rec.action != BudgetRecommendationAction.KEEP_CURRENT) {
            OutlinedButton(
              onClick = { onApplyRecommendation(rec) },
              modifier = Modifier
                .align(Alignment.End)
                .testTag("apply_budget_rec_${rec.categoryId}"),
            ) {
              Text(actionLabel)
            }
          }
        }
      }
    }
  }
}

@Composable
fun EmergencyFundAndGoalAdvisorCard(
  emergencyRec: EmergencyFundRecommendation,
  goalRecommendations: List<GoalContributionRecommendation>,
  currencyEntity: CurrencyEntity?,
  modifier: Modifier = Modifier,
) {
  val code = emergencyRec.currencyCode
  SkyPlusPortfolioCard(modifier = modifier.testTag("savings_advisor_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = SkyPlusBrandColors.EmeraldGreen)
        Text("Emergency & Savings Advisor", style = MaterialTheme.typography.titleMedium)
      }
      Text(
        text = "3m / 6m Targets",
        style = MaterialTheme.typography.labelSmall,
        color = SkyPlusBrandColors.EmeraldGreen,
        fontWeight = FontWeight.Bold,
      )
    }

    Text(
      text = emergencyRec.summaryReason,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(modifier = Modifier.weight(1f)) {
        Text("3-Month Reserve", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = CurrencyFormatter.formatWithEntity(emergencyRec.threeMonthTarget, currencyEntity, code),
          style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
        )
        Text(
          text = "Left: ${CurrencyFormatter.formatWithEntity(emergencyRec.remainingForThreeMonthTarget, currencyEntity, code)}",
          style = MaterialTheme.typography.labelSmall,
          color = SkyPlusBrandColors.EmeraldGreen,
        )
      }
      Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
        Text("6-Month Reserve", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = CurrencyFormatter.formatWithEntity(emergencyRec.sixMonthTarget, currencyEntity, code),
          style = MaterialTheme.typography.titleMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
        )
        Text(
          text = "Saved this mo: ${CurrencyFormatter.formatWithEntity(emergencyRec.alreadyAllocatedThisMonth, currencyEntity, code)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (goalRecommendations.isNotEmpty()) {
      HorizontalDivider()
      Text("Recommended Monthly Goal Pace", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.RoyalBlue)
      goalRecommendations.take(4).forEach { goalRec ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text(goalRec.goalName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(goalRec.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "${CurrencyFormatter.formatWithEntity(goalRec.suggestedMonthlyContribution, currencyEntity, code)}/mo",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = SkyPlusFonts.NumericMono),
            color = SkyPlusBrandColors.EmeraldGreen,
          )
        }
      }
    }
  }
}
