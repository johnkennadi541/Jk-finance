package com.skyplus.finance.presentation.intelligence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusExpandableCard
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceReport
import com.skyplus.finance.domain.intelligence.DebtRiskLevel
import com.skyplus.finance.domain.intelligence.ExtraEmiSimulationResult
import com.skyplus.finance.domain.intelligence.SnowballVsAvalancheComparison
import com.skyplus.finance.domain.model.DebtPayoffStrategy
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.util.CurrencyFormatter
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun DebtPayoffIntelligenceCard(
  report: DebtPayoffIntelligenceReport,
  currencyEntity: CurrencyEntity?,
  onCompareWithExtraBudget: (String) -> SnowballVsAvalancheComparison,
  modifier: Modifier = Modifier,
) {
  var extraBudgetText by remember { mutableStateOf("0") }
  val liveComparison = remember(report, extraBudgetText) {
    val parsed = extraBudgetText.trim()
    if (parsed.isEmpty() || parsed == "0") {
      report.strategyComparison
    } else {
      onCompareWithExtraBudget(extraBudgetText)
    }
  }
  val code = liveComparison.evaluatedCurrencyCode
  val snow = liveComparison.snowball
  val aval = liveComparison.avalanche

  val riskColor = when (report.debtRiskLevel) {
    DebtRiskLevel.NONE, DebtRiskLevel.HEALTHY -> SkyPlusBrandColors.EmeraldGreen
    DebtRiskLevel.MODERATE -> SkyPlusBrandColors.RoyalBlue
    DebtRiskLevel.HIGH -> SkyPlusBrandColors.WarningAmber
    DebtRiskLevel.CRITICAL -> SkyPlusBrandColors.ErrorCoral
  }

  SkyPlusPortfolioCard(modifier = modifier.testTag("debt_payoff_intelligence_card")) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.CompareArrows, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
        Text("Snowball vs Avalanche", style = MaterialTheme.typography.titleMedium)
      }
      Surface(color = riskColor.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
        Text(
          text = report.debtRiskLevel.label,
          style = MaterialTheme.typography.labelSmall,
          color = riskColor,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }

    Text(report.riskSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // Interactive Extra Monthly Budget Simulator across Loan Portfolio
    OutlinedTextField(
      value = extraBudgetText,
      onValueChange = { extraBudgetText = it },
      label = { Text("Simulate Extra Monthly Debt Budget ($code)") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("portfolio_extra_budget_input"),
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Column(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(12.dp))
          .background(SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.08f))
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text("Snowball", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.RoyalBlue)
        Text("Smallest Balance First", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = "${snow.monthsToDebtFree} mo • ${LocalDate.ofEpochDay(snow.estimatedDebtFreeEpochDay)}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "Int: ${CurrencyFormatter.formatWithEntity(snow.totalInterestProjected, currencyEntity, code)}",
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
        )
        Text(
          text = "Order: ${snow.orderedLoanNames.joinToString(" → ")}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Column(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(12.dp))
          .background(SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.08f))
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text("Avalanche", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.EmeraldGreen)
        Text("Highest Rate First", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          text = "${aval.monthsToDebtFree} mo • ${LocalDate.ofEpochDay(aval.estimatedDebtFreeEpochDay)}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "Int: ${CurrencyFormatter.formatWithEntity(aval.totalInterestProjected, currencyEntity, code)}",
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
        )
        Text(
          text = "Order: ${aval.orderedLoanNames.joinToString(" → ")}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Surface(
      color = SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.10f),
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val recLabel = if (liveComparison.recommendedStrategy == DebtPayoffStrategy.AVALANCHE) {
          "Recommended: Avalanche"
        } else {
          "Recommended: Snowball"
        }
        Text(recLabel, style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.RoyalBlue)
        Text(liveComparison.comparisonSummary, style = MaterialTheme.typography.bodyMedium)
      }
    }

    SkyPlusExpandableCard(
      title = "Strategy Pros, Cons & Payoff Timeline",
      subtitle = "Tap to inspect payoff milestones and assumptions",
      initiallyExpanded = false,
      modifier = Modifier.testTag("strategy_pros_cons_expandable"),
    ) {
      Text("Snowball Advantages", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.RoyalBlue)
      snow.advantages.forEach { pro ->
        Text("• $pro", style = MaterialTheme.typography.bodyMedium)
      }
      Text("Snowball Trade-offs", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.WarningAmber)
      snow.disadvantages.forEach { con ->
        Text("• $con", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      HorizontalDivider()

      Text("Avalanche Advantages", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.EmeraldGreen)
      aval.advantages.forEach { pro ->
        Text("• $pro", style = MaterialTheme.typography.bodyMedium)
      }
      Text("Avalanche Trade-offs", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.WarningAmber)
      aval.disadvantages.forEach { con ->
        Text("• $con", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      if (aval.loanPayoffTimeline.isNotEmpty()) {
        HorizontalDivider()
        Text("Estimated Avalanche Payoff Order", style = MaterialTheme.typography.labelLarge)
        aval.loanPayoffTimeline.forEach { m ->
          Text(
            text = "#${m.payoffOrder} ${m.loanName} (${m.annualInterestRatePercent}% APR) • ${m.estimatedMonthsToPayoff} mo (${LocalDate.ofEpochDay(m.estimatedPayoffEpochDay)}) • Int: ${CurrencyFormatter.formatWithEntity(m.projectedInterestPaid, currencyEntity, m.currencyCode)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      HorizontalDivider()
      Text("Assumptions", style = MaterialTheme.typography.labelLarge)
      report.calculationAssumptions.forEach { a ->
        Text("• $a", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    Text(
      text = report.bankPayoffDisclaimer,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
fun ExtraEmiSimulatorDialog(
  loan: LoanEntity,
  currencyEntity: CurrencyEntity?,
  onSimulate: (LoanEntity, String) -> ExtraEmiSimulationResult,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var extraMonthly by remember { mutableStateOf("1000") }
  val sim = remember(loan, extraMonthly) { onSimulate(loan, extraMonthly) }
  val est = sim.earlyRepaymentEstimate
  val code = sim.currencyCode

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Extra EMI Simulator — ${loan.loanName}") },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 460.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = extraMonthly,
          onValueChange = { extraMonthly = it },
          label = { Text("${strings.extraPrepayment} / month ($code)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("extra_emi_simulator_input"),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("0", "500", "1000", "2500").forEach { preset ->
            FilterChip(
              selected = extraMonthly == preset,
              onClick = { extraMonthly = preset },
              label = { Text("+$preset", maxLines = 1) },
              modifier = Modifier.weight(1f),
            )
          }
        }

        sim.validationWarning?.let { warn ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(SkyPlusBrandColors.WarningAmber.copy(alpha = 0.14f))
              .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = SkyPlusBrandColors.WarningAmber, modifier = Modifier.size(16.dp))
            Text(warn, style = MaterialTheme.typography.labelSmall)
          }
        }

        HorizontalDivider()

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Normal EMI Plan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              text = "${CurrencyFormatter.formatWithEntity(sim.regularMonthlyEmi, currencyEntity, code)}/mo",
              style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
            )
            Text("${est.baselineMonthsRemaining} months", style = MaterialTheme.typography.labelSmall)
            Text("Payoff: ${LocalDate.ofEpochDay(sim.normalPayoffEpochDay)}", style = MaterialTheme.typography.labelSmall)
            Text(
              text = "Int: ${CurrencyFormatter.formatWithEntity(est.baselineTotalInterest, currencyEntity, code)}",
              style = MaterialTheme.typography.labelSmall,
            )
          }
          Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("With Extra EMI", style = MaterialTheme.typography.labelSmall, color = SkyPlusBrandColors.EmeraldGreen)
            Text(
              text = "${CurrencyFormatter.formatWithEntity(sim.combinedMonthlyPayment, currencyEntity, code)}/mo",
              style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SkyPlusFonts.NumericMono),
              color = SkyPlusBrandColors.EmeraldGreen,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = "${est.acceleratedMonthsRemaining} mo (saves ${est.monthsSaved} mo)",
              style = MaterialTheme.typography.labelSmall,
              color = SkyPlusBrandColors.EmeraldGreen,
            )
            Text("Payoff: ${LocalDate.ofEpochDay(sim.acceleratedPayoffEpochDay)}", style = MaterialTheme.typography.labelSmall)
            Text(
              text = "Int: ${CurrencyFormatter.formatWithEntity(est.acceleratedTotalInterest, currencyEntity, code)}",
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }

        Surface(
          color = SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.12f),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = "Estimated Interest Saved: ${CurrencyFormatter.formatWithEntity(est.interestSaved, currencyEntity, code)} (${sim.interestSavedPercent}%)",
              style = MaterialTheme.typography.labelLarge,
              color = SkyPlusBrandColors.EmeraldGreen,
              modifier = Modifier.testTag("extra_emi_interest_saved"),
            )
            Text(sim.comparisonSummary, style = MaterialTheme.typography.bodyMedium)
          }
        }

        if (sim.firstMonthNormalSplit.interestComponent > BigDecimal.ZERO || sim.firstMonthExtraPrincipalPreview > BigDecimal.ZERO) {
          Text(
            text = "Next Month Split: Principal ${CurrencyFormatter.formatWithEntity(sim.firstMonthNormalSplit.principalComponent, currencyEntity, code)} (+${CurrencyFormatter.formatWithEntity(sim.firstMonthExtraPrincipalPreview, currencyEntity, code)} extra) • Interest ${CurrencyFormatter.formatWithEntity(sim.firstMonthNormalSplit.interestComponent, currencyEntity, code)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Text(
          text = "Read-only simulation: changing amounts recalculates estimates immediately without altering your actual loan records or triggering payments.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Done") }
    },
  )
}
