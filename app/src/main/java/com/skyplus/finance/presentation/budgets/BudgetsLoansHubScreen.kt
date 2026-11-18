package com.skyplus.finance.presentation.budgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.domain.intelligence.ExtraEmiSimulationResult
import com.skyplus.finance.domain.intelligence.SnowballVsAvalancheComparison
import com.skyplus.finance.domain.model.BudgetStatusItem
import com.skyplus.finance.domain.model.EarlyRepaymentEstimate
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.loans.LoansTab
import com.skyplus.finance.presentation.savings.SavingsTab

enum class BudgetsLoansSubTab {
  BUDGETS,
  LOANS,
  SAVINGS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsLoansHubScreen(
  selectedSubTab: BudgetsLoansSubTab,
  onSelectSubTab: (BudgetsLoansSubTab) -> Unit,
  snapshot: DashboardSnapshot,
  allBudgetHistory: List<BudgetStatusItem>,
  onUpsertBudget: (Long, String, String, String, Boolean, String) -> Unit,
  onDeleteBudget: (Long) -> Unit,
  onAddLoan: (String, String, String, String, String, String, String, Int, Int) -> Unit,
  onDeleteLoan: (LoanEntity) -> Unit,
  onRecordEmi: (LoanEntity, String, String, String) -> Unit,
  onEstimateEarlyRepayment: (LoanEntity, String) -> EarlyRepaymentEstimate,
  onCreateSavingsGoal: (String, SavingsGoalType, String, String, String, Int?, Boolean) -> Unit,
  onContributeGoal: (SavingsGoalEntity, String, String) -> Unit,
  onDeleteGoal: (SavingsGoalEntity) -> Unit,
  onSimulateExtraEmi: ((LoanEntity, String) -> ExtraEmiSimulationResult)? = null,
  onCompareDebtStrategy: ((String) -> SnowballVsAvalancheComparison)? = null,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  Column(modifier = modifier.fillMaxSize()) {
    SingleChoiceSegmentedButtonRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      val tabs = listOf(
        BudgetsLoansSubTab.BUDGETS to strings.budgetsTab,
        BudgetsLoansSubTab.LOANS to strings.loansTab,
        BudgetsLoansSubTab.SAVINGS to strings.savingsTab,
      )
      tabs.forEachIndexed { idx, (tab, label) ->
        SegmentedButton(
          selected = selectedSubTab == tab,
          onClick = { onSelectSubTab(tab) },
          shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
          modifier = Modifier.testTag("subtab_${tab.name.lowercase()}"),
        ) {
          Text(label, maxLines = 1)
        }
      }
    }

    when (selectedSubTab) {
      BudgetsLoansSubTab.BUDGETS -> BudgetsTab(
        snapshot = snapshot,
        allBudgetHistory = allBudgetHistory,
        onUpsertBudget = onUpsertBudget,
        onDeleteBudget = onDeleteBudget,
        modifier = Modifier.weight(1f),
      )

      BudgetsLoansSubTab.LOANS -> LoansTab(
        snapshot = snapshot,
        onAddLoan = onAddLoan,
        onDeleteLoan = onDeleteLoan,
        onRecordEmi = onRecordEmi,
        onEstimateEarlyRepayment = onEstimateEarlyRepayment,
        onSimulateExtraEmi = onSimulateExtraEmi,
        onCompareDebtStrategy = onCompareDebtStrategy,
        modifier = Modifier.weight(1f),
      )

      BudgetsLoansSubTab.SAVINGS -> SavingsTab(
        snapshot = snapshot,
        onCreateGoal = onCreateSavingsGoal,
        onContribute = onContributeGoal,
        onDeleteGoal = onDeleteGoal,
        modifier = Modifier.weight(1f),
      )
    }
  }
}
