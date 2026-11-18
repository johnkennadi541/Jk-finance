package com.skyplus.finance.presentation.loans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.intelligence.DebtPayoffIntelligenceEngine
import com.skyplus.finance.domain.intelligence.ExtraEmiSimulationResult
import com.skyplus.finance.domain.intelligence.SnowballVsAvalancheComparison
import com.skyplus.finance.domain.model.EarlyRepaymentEstimate
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.intelligence.DebtPayoffIntelligenceCard
import com.skyplus.finance.presentation.intelligence.ExtraEmiSimulatorDialog
import com.skyplus.finance.presentation.theme.JetBrainsMonoFamily
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun LoansTab(
  snapshot: DashboardSnapshot,
  onAddLoan: (
    name: String,
    lender: String,
    principal: String,
    outstanding: String,
    rate: String,
    emi: String,
    currency: String,
    dueDay: Int,
    months: Int,
  ) -> Unit,
  onDeleteLoan: (LoanEntity) -> Unit,
  onRecordEmi: (LoanEntity, String, String, String) -> Unit,
  onEstimateEarlyRepayment: (LoanEntity, String) -> EarlyRepaymentEstimate,
  onSimulateExtraEmi: ((LoanEntity, String) -> ExtraEmiSimulationResult)? = null,
  onCompareDebtStrategy: ((String) -> SnowballVsAvalancheComparison)? = null,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  val fallbackDebtEngine = remember { DebtPayoffIntelligenceEngine(LoanEmiCalculator()) }
  var showCreateDialog by remember { mutableStateOf(false) }
  var payingLoan by remember { mutableStateOf<LoanEntity?>(null) }
  var calculatorLoan by remember { mutableStateOf<LoanEntity?>(null) }

  if (showCreateDialog) {
    CreateLoanDialog(
      defaultCurrency = snapshot.primaryCurrencyCode,
      onSave = { name, lender, princ, out, rate, emi, cur, dueDay, months ->
        onAddLoan(name, lender, princ, out, rate, emi, cur, dueDay, months)
        showCreateDialog = false
      },
      onDismiss = { showCreateDialog = false },
    )
  }

  payingLoan?.let { loan ->
    PayEmiDialog(
      loan = loan,
      snapshot = snapshot,
      onPay = { payAmt, extraAmt, note ->
        onRecordEmi(loan, payAmt, extraAmt, note)
        payingLoan = null
      },
      onDismiss = { payingLoan = null },
    )
  }

  calculatorLoan?.let { loan ->
    ExtraEmiSimulatorDialog(
      loan = loan,
      currencyEntity = snapshot.currenciesByCode[loan.currencyCode.uppercase()],
      onSimulate = { targetLoan, extraText ->
        if (onSimulateExtraEmi != null) {
          onSimulateExtraEmi(targetLoan, extraText)
        } else {
          val parsedExtra = extraText.trim().replace(",", "").let {
            if (it.isEmpty()) BigDecimal.ZERO else it.toBigDecimalOrNull() ?: BigDecimal("-1")
          }
          val precision = snapshot.currenciesByCode[targetLoan.currencyCode.uppercase()]?.decimalPrecision ?: 2
          fallbackDebtEngine.simulateExtraEmiPayment(
            loan = targetLoan,
            extraMonthlyPayment = parsedExtra,
            decimalPrecision = precision,
          )
        }
      },
      onDismiss = { calculatorLoan = null },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(strings.loansTab, style = MaterialTheme.typography.titleLarge)
          Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.testTag("create_loan_button"),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(strings.createLoan)
          }
        }
      }

      if (snapshot.loans.any { it.status == LoanStatus.ACTIVE }) {
        val debtReport = snapshot.intelligence?.debtPayoffReport
        if (debtReport != null) {
          item(key = "debt_payoff_intelligence") {
            DebtPayoffIntelligenceCard(
              report = debtReport,
              currencyEntity = snapshot.primaryCurrency,
              onCompareWithExtraBudget = { extraBudgetText ->
                if (onCompareDebtStrategy != null) {
                  onCompareDebtStrategy(extraBudgetText)
                } else {
                  val extra = DecimalUtils.parseNonNegativeOrNull(extraBudgetText) ?: BigDecimal.ZERO
                  val precision = snapshot.primaryCurrency?.decimalPrecision ?: 2
                  fallbackDebtEngine.compareStrategies(
                    loans = snapshot.loans,
                    primaryCurrencyCode = snapshot.primaryCurrencyCode,
                    scale = precision,
                    extraMonthlyBudget = extra,
                  )
                }
              },
            )
          }
        } else {
          item {
            SnowballAvalancheCard(snapshot = snapshot)
          }
        }
      }

      if (snapshot.loans.isEmpty()) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Text(
              text = strings.noActiveLoans,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(20.dp),
            )
          }
        }
      } else {
        items(snapshot.loans, key = { it.id }) { loan ->
          LoanCard(
            loan = loan,
            snapshot = snapshot,
            onPayEmi = { payingLoan = loan },
            onOpenCalculator = { calculatorLoan = loan },
            onDelete = { onDeleteLoan(loan) },
          )
        }
      }

      if (snapshot.emiPayments.isNotEmpty()) {
        item {
          Spacer(modifier = Modifier.height(6.dp))
          Text(strings.emiHistory, style = MaterialTheme.typography.titleMedium)
        }
        items(snapshot.emiPayments.take(15), key = { "emi_${it.id}" }) { payment ->
          val cur = snapshot.currenciesByCode[payment.currencyCode.uppercase()]
          val loanName = snapshot.loans.firstOrNull { it.id == payment.loanId }?.loanName ?: "Loan"
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(loanName, style = MaterialTheme.typography.titleMedium)
                Text(
                  text = "${LocalDate.ofEpochDay(payment.paymentDateEpochDay)} • ${strings.principalLabel}: ${CurrencyFormatter.formatWithEntity(payment.principalComponent, cur, payment.currencyCode)} • ${strings.interestLabel}: ${CurrencyFormatter.formatWithEntity(payment.interestComponent, cur, payment.currencyCode)}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Text(
                text = CurrencyFormatter.formatWithEntity(payment.totalPaidAmount, cur, payment.currencyCode),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = SkyPlusBrandColors.EmeraldGreen,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SnowballAvalancheCard(snapshot: DashboardSnapshot) {
  val strings = LocalAppStrings.current
  val cur = snapshot.primaryCurrency
  val snow = snapshot.snowballPlan
  val aval = snapshot.avalanchePlan

  SkyPlusPortfolioCard {
    Text("${strings.snowballVsAvalanche} (${strings.estimatedLabel})", style = MaterialTheme.typography.titleMedium)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Debt Snowball", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.RoyalBlue)
        Text("${snow.monthsToDebtFree} mo • ${LocalDate.ofEpochDay(snow.estimatedDebtFreeEpochDay)}", style = MaterialTheme.typography.bodyMedium)
        Text(
          text = "Int: ${CurrencyFormatter.formatWithEntity(snow.totalInterestProjected, cur, snapshot.primaryCurrencyCode)}",
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text("Debt Avalanche", style = MaterialTheme.typography.labelLarge, color = SkyPlusBrandColors.EmeraldGreen)
        Text("${aval.monthsToDebtFree} mo • ${LocalDate.ofEpochDay(aval.estimatedDebtFreeEpochDay)}", style = MaterialTheme.typography.bodyMedium)
        Text(
          text = "Int: ${CurrencyFormatter.formatWithEntity(aval.totalInterestProjected, cur, snapshot.primaryCurrencyCode)}",
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
        )
      }
    }
  }
}

@Composable
private fun LoanCard(
  loan: LoanEntity,
  snapshot: DashboardSnapshot,
  onPayEmi: () -> Unit,
  onOpenCalculator: () -> Unit,
  onDelete: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val cur = snapshot.currenciesByCode[loan.currencyCode.uppercase()]

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("loan_card_${loan.id}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(loan.loanName, style = MaterialTheme.typography.titleMedium)
          Text(
            text = "${loan.lender} • ${loan.annualInterestRatePercent.toPlainString()}% APR • ${loan.status.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Filled.Delete, contentDescription = strings.delete)
        }
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
          Text(strings.outstandingPrincipal, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = CurrencyFormatter.formatWithEntity(loan.outstandingPrincipal, cur, loan.currencyCode),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
          )
        }
        Column(horizontalAlignment = Alignment.End) {
          Text(strings.emiAmountLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = CurrencyFormatter.formatWithEntity(loan.emiAmount, cur, loan.currencyCode),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
            color = SkyPlusBrandColors.RoyalBlue,
          )
        }
      }

      Text(
        text = "${strings.nextEmiDue}: ${LocalDate.ofEpochDay(loan.nextDueDateEpochDay)} • ${loan.remainingTenureMonths} mo left",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (loan.status == LoanStatus.ACTIVE) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(
            onClick = onPayEmi,
            modifier = Modifier
              .weight(1f)
              .testTag("pay_emi_button_${loan.id}"),
          ) {
            Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(strings.recordEmiPayment)
          }
          OutlinedButton(
            onClick = onOpenCalculator,
            modifier = Modifier
              .weight(1f)
              .testTag("calc_emi_button_${loan.id}"),
          ) {
            Icon(Icons.Filled.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(strings.earlyRepaymentCalculator, maxLines = 1)
          }
        }
      }
    }
  }
}

@Composable
private fun CreateLoanDialog(
  defaultCurrency: String,
  onSave: (String, String, String, String, String, String, String, Int, Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var name by remember { mutableStateOf("") }
  var lender by remember { mutableStateOf("") }
  var principal by remember { mutableStateOf("") }
  var outstanding by remember { mutableStateOf("") }
  var rate by remember { mutableStateOf("8.5") }
  var emi by remember { mutableStateOf("") }
  var dueDay by remember { mutableStateOf("5") }
  var months by remember { mutableStateOf("24") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.createLoan) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text(strings.loanName) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("loan_name_input"),
        )
        OutlinedTextField(
          value = lender,
          onValueChange = { lender = it },
          label = { Text(strings.lenderName) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = principal,
          onValueChange = {
            principal = it
            if (outstanding.isEmpty()) outstanding = it
          },
          label = { Text("${strings.originalPrincipal} ($defaultCurrency)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("loan_principal_input"),
        )
        OutlinedTextField(
          value = outstanding,
          onValueChange = { outstanding = it },
          label = { Text("${strings.outstandingPrincipal} ($defaultCurrency)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = rate,
          onValueChange = { rate = it },
          label = { Text(strings.annualInterestRate) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = months,
          onValueChange = { months = it },
          label = { Text(strings.remainingTenureMonths) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = emi,
          onValueChange = { emi = it },
          label = { Text("${strings.emiAmountLabel} (auto if empty)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = dueDay,
          onValueChange = { dueDay = it },
          label = { Text(strings.dueDayOfMonth) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            name,
            lender,
            principal,
            outstanding.ifBlank { principal },
            rate,
            emi,
            defaultCurrency,
            dueDay.toIntOrNull() ?: 5,
            months.toIntOrNull() ?: 12,
          )
        },
        modifier = Modifier.testTag("save_loan_button"),
      ) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}

@Composable
private fun PayEmiDialog(
  loan: LoanEntity,
  snapshot: DashboardSnapshot,
  onPay: (String, String, String) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var paymentText by remember { mutableStateOf(DecimalUtils.toCanonicalString(loan.emiAmount)) }
  var extraText by remember { mutableStateOf("0") }
  var note by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("${strings.recordEmiPayment} — ${loan.loanName}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = paymentText,
          onValueChange = { paymentText = it },
          label = { Text("${strings.emiAmountLabel} (${loan.currencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
        OutlinedTextField(
          value = extraText,
          onValueChange = { extraText = it },
          label = { Text("${strings.extraPrepayment} (${loan.currencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          label = { Text(strings.descriptionOptional) },
          singleLine = true,
        )
      }
    },
    confirmButton = {
      Button(onClick = { onPay(paymentText, extraText, note) }) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}

@Composable
private fun EarlyRepaymentDialog(
  loan: LoanEntity,
  snapshot: DashboardSnapshot,
  onEstimate: (LoanEntity, String) -> EarlyRepaymentEstimate,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  val cur = snapshot.currenciesByCode[loan.currencyCode.uppercase()]
  var extraMonthly by remember { mutableStateOf("1000") }
  val estimate = remember(loan, extraMonthly) { onEstimate(loan, extraMonthly) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("${strings.earlyRepaymentCalculator} (${strings.estimatedLabel})") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = extraMonthly,
          onValueChange = { extraMonthly = it },
          label = { Text("${strings.extraPrepayment} / month (${loan.currencyCode})") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
        )
        HorizontalDivider()
        Text("Baseline tenure: ${estimate.baselineMonthsRemaining} months")
        Text("Accelerated tenure: ${estimate.acceleratedMonthsRemaining} months (saves ${estimate.monthsSaved} mo)")
        Text(
          text = "Estimated Interest Saved: ${CurrencyFormatter.formatWithEntity(estimate.interestSaved, cur, loan.currencyCode)}",
          color = SkyPlusBrandColors.EmeraldGreen,
          fontWeight = FontWeight.Bold,
        )
        Text("Estimated payoff date: ${LocalDate.ofEpochDay(estimate.estimatedPayoffEpochDay)}")
        Text(
          text = "Note: Payoff timelines are estimates assuming consistent monthly payments.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}
