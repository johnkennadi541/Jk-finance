package com.skyplus.finance.presentation

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.skyplus.finance.data.cloud.GuestMigrationPreview
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusBrandTheme
import com.skyplus.finance.designsystem.SkyPlusErrorState
import com.skyplus.finance.designsystem.SkyPlusLoadingState
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.localization.AppStringsProvider
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.budgets.BudgetsLoansHubScreen
import com.skyplus.finance.presentation.budgets.BudgetsLoansSubTab
import com.skyplus.finance.presentation.components.QuickTransactionBottomSheet
import com.skyplus.finance.presentation.home.HomeScreen
import com.skyplus.finance.presentation.profile.GuestMigrationDialog
import com.skyplus.finance.presentation.profile.ProfileScreen
import com.skyplus.finance.presentation.reports.ReportsScreen
import com.skyplus.finance.presentation.security.AppLockGateScreen
import com.skyplus.finance.presentation.security.BiometricAuthHelper
import com.skyplus.finance.presentation.settings.PrivacyContractScreen
import com.skyplus.finance.presentation.shopping.SmartShoppingScreen
import com.skyplus.finance.presentation.transactions.AutoDetectTransactionSheet
import com.skyplus.finance.presentation.transactions.TransactionsScreen
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object TransactionsRoute

@Serializable
object BudgetsLoansRoute

@Serializable
object SmartShoppingRoute

@Serializable
object ReportsRoute

@Serializable
object ProfileRoute

@Serializable
object PrivacyContractRoute

@Composable
fun SkyPlusApp(
  viewModel: MainViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var pendingMigrationPrompt by remember { mutableStateOf<GuestMigrationPreview?>(null) }

  val saveDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("*/*"),
  ) { destinationUri ->
    val source = pendingSaveFile
    pendingSaveFile = null
    if (destinationUri != null && source != null) {
      viewModel.copyExportedFileToUri(source, destinationUri)
    }
  }

  val driveConsentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartIntentSenderForResult(),
  ) { result ->
    viewModel.onDriveConsentActivityResult(result.data)
  }

  if (pendingMigrationPrompt != null) {
    val preview = pendingMigrationPrompt!!
    GuestMigrationDialog(
      preview = preview,
      onConfirmMigrate = { deduplicate ->
        pendingMigrationPrompt = null
        viewModel.confirmGuestMigration(deduplicate)
      },
      onKeepLocalOnly = {
        pendingMigrationPrompt = null
      },
    )
  }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        is MainUiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
        is MainUiEvent.ShareFile -> {
          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = event.mimeType
            putExtra(Intent.EXTRA_STREAM, event.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          context.startActivity(Intent.createChooser(shareIntent, event.title))
        }

        is MainUiEvent.SaveFileWithPicker -> {
          pendingSaveFile = event.sourceFile
          saveDocumentLauncher.launch(event.suggestedFileName)
        }

        is MainUiEvent.LaunchDriveConsent -> {
          driveConsentLauncher.launch(event.intentSenderRequest)
        }

        is MainUiEvent.PromptGuestMigration -> {
          pendingMigrationPrompt = event.preview
        }
      }
    }
  }

  when (val state = uiState) {
    MainUiState.Loading -> {
      SkyPlusBrandTheme {
        SkyPlusLoadingState()
      }
    }

    is MainUiState.Error -> {
      SkyPlusBrandTheme {
        SkyPlusErrorState(
          title = "Storage Error",
          message = state.message,
        )
      }
    }

    is MainUiState.Ready -> {
      val snapshot = state.snapshot
      val strings = AppStringsProvider.forLanguage(snapshot.settings?.languageCode ?: "en")

      CompositionLocalProvider(LocalAppStrings provides strings) {
        SkyPlusBrandTheme(themeMode = snapshot.themeMode) {
          if (state.isLocked) {
            var lockError by remember { mutableStateOf<String?>(null) }
            val activity = context as? FragmentActivity
            val triggerAuth: () -> Unit = {
              if (activity != null && BiometricAuthHelper.canAuthenticate(activity)) {
                BiometricAuthHelper.authenticate(
                  activity = activity,
                  title = strings.unlockTitle,
                  subtitle = strings.unlockSubtitle,
                  onSuccess = {
                    lockError = null
                    viewModel.unlockLedger()
                  },
                  onError = { err -> lockError = err },
                )
              } else {
                viewModel.unlockLedger()
              }
            }

            LaunchedEffect(Unit) { triggerAuth() }

            AppLockGateScreen(
              authError = lockError,
              onUnlockClick = triggerAuth,
            )
          } else {
            MainScaffoldContent(
              state = state,
              viewModel = viewModel,
              snackbarHostState = snackbarHostState,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MainScaffoldContent(
  state: MainUiState.Ready,
  viewModel: MainViewModel,
  snackbarHostState: SnackbarHostState,
) {
  val strings = LocalAppStrings.current
  val snapshot = state.snapshot
  val context = LocalContext.current
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route

  var activeSubTab by remember { mutableStateOf(BudgetsLoansSubTab.BUDGETS) }
  var showQuickSheet by remember { mutableStateOf(false) }
  var showAutoDetectSheet by remember { mutableStateOf(false) }
  var sheetInitialType by remember { mutableStateOf(TransactionType.EXPENSE) }
  var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }

  if (showAutoDetectSheet) {
    AutoDetectTransactionSheet(
      categories = snapshot.categoriesById.values.toList(),
      currenciesByCode = snapshot.currenciesByCode,
      onAnalyze = { raw, source -> viewModel.analyzeTransactionMessages(raw, source) },
      onConfirmSave = { items -> viewModel.saveDetectedTransactions(items) },
      onDismiss = { showAutoDetectSheet = false },
    )
  }

  if (showQuickSheet) {
    QuickTransactionBottomSheet(
      initialType = sheetInitialType,
      editingTransaction = editingTransaction,
      categories = snapshot.categoriesById.values.toList(),
      currencies = snapshot.allCurrencies,
      defaultCurrencyCode = snapshot.primaryCurrencyCode,
      onSave = { existingId, type, amount, catId, cur, date, desc, upcoming, recurring, token ->
        viewModel.saveTransaction(
          existingId = existingId,
          type = type,
          amountText = amount,
          categoryId = catId,
          currencyCode = cur,
          dateEpochDay = date,
          description = desc,
          isUpcomingBill = upcoming,
          isRecurring = recurring,
          submissionToken = token,
        )
      },
      onDelete = { id -> viewModel.deleteTransaction(id) },
      onDismiss = {
        showQuickSheet = false
        editingTransaction = null
      },
    )
  }

  val showBottomBar = currentRoute != PrivacyContractRoute::class.qualifiedName

  fun navigateTopLevel(route: Any) {
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    floatingActionButton = {
      if (currentRoute == HomeRoute::class.qualifiedName ||
        currentRoute == TransactionsRoute::class.qualifiedName
      ) {
        FloatingActionButton(
          onClick = {
            editingTransaction = null
            sheetInitialType = TransactionType.EXPENSE
            showQuickSheet = true
          },
          containerColor = SkyPlusBrandColors.RoyalBlue,
          contentColor = SkyPlusBrandColors.PureWhite,
          modifier = Modifier.testTag("fab_quick_transaction"),
        ) {
          Icon(Icons.Filled.Add, contentDescription = strings.transactionSheetTitle)
        }
      }
    },
    bottomBar = {
      if (showBottomBar) {
        val navColors = NavigationBarItemDefaults.colors(
          selectedIconColor = SkyPlusBrandColors.RoyalBlue,
          selectedTextColor = SkyPlusBrandColors.RoyalBlue,
          indicatorColor = SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.14f),
          unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
          unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
          val isHome = currentRoute == HomeRoute::class.qualifiedName
          val isTx = currentRoute == TransactionsRoute::class.qualifiedName
          val isBudgets = currentRoute == BudgetsLoansRoute::class.qualifiedName
          val isShop = currentRoute == SmartShoppingRoute::class.qualifiedName
          val isReports = currentRoute == ReportsRoute::class.qualifiedName
          val isProfile = currentRoute == ProfileRoute::class.qualifiedName
          val navLabelStyle = MaterialTheme.typography.labelSmall

          NavigationBarItem(
            selected = isHome,
            onClick = { navigateTopLevel(HomeRoute) },
            icon = {
              Icon(
                imageVector = if (isHome) Icons.Filled.Home else Icons.Outlined.Home,
                contentDescription = strings.navHome,
              )
            },
            label = { Text(strings.navBarHome, style = navLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = navColors,
            modifier = Modifier.testTag("nav_home"),
          )

          NavigationBarItem(
            selected = isTx,
            onClick = { navigateTopLevel(TransactionsRoute) },
            icon = {
              Icon(
                imageVector = if (isTx) Icons.Filled.ReceiptLong else Icons.Outlined.ReceiptLong,
                contentDescription = strings.navTransactions,
              )
            },
            label = { Text(strings.navBarLedger, style = navLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = navColors,
            modifier = Modifier.testTag("nav_transactions"),
          )

          NavigationBarItem(
            selected = isBudgets,
            onClick = { navigateTopLevel(BudgetsLoansRoute) },
            icon = {
              Icon(
                imageVector = if (isBudgets) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet,
                contentDescription = strings.navBudgetsLoans,
              )
            },
            label = { Text(strings.navBarPlan, style = navLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = navColors,
            modifier = Modifier.testTag("nav_budgets_loans"),
          )

          NavigationBarItem(
            selected = isShop,
            onClick = { navigateTopLevel(SmartShoppingRoute) },
            icon = {
              Icon(
                imageVector = if (isShop) Icons.Filled.ShoppingCart else Icons.Outlined.ShoppingCart,
                contentDescription = strings.navShopping,
              )
            },
            label = { Text(strings.navBarShop, style = navLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = navColors,
            modifier = Modifier.testTag("nav_shopping"),
          )

          NavigationBarItem(
            selected = isReports,
            onClick = { navigateTopLevel(ReportsRoute) },
            icon = {
              Icon(
                imageVector = if (isReports) Icons.Filled.Assessment else Icons.Outlined.Assessment,
                contentDescription = strings.navReports,
              )
            },
            label = { Text(strings.navBarReports, style = navLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = navColors,
            modifier = Modifier.testTag("nav_reports"),
          )

          NavigationBarItem(
            selected = isProfile,
            onClick = { navigateTopLevel(ProfileRoute) },
            icon = {
              Icon(
                imageVector = if (isProfile) Icons.Filled.Person else Icons.Outlined.Person,
                contentDescription = strings.navProfile,
              )
            },
            label = { Text(strings.navBarProfile, style = navLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = navColors,
            modifier = Modifier.testTag("nav_profile"),
          )
        }
      }
    },
  ) { innerPadding ->
    NavHost(
      navController = navController,
      startDestination = HomeRoute,
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      composable<HomeRoute> {
        HomeScreen(
          snapshot = snapshot,
          buildCharts = { period, sDay, eDay ->
            viewModel.buildChartsForPeriod(snapshot, period, sDay, eDay)
          },
          onQuickAddTransaction = { type ->
            editingTransaction = null
            sheetInitialType = type
            showQuickSheet = true
          },
          onEditTransaction = { tx ->
            editingTransaction = tx
            sheetInitialType = tx.type
            showQuickSheet = true
          },
          onMarkBillPaid = { id -> viewModel.markBillPaid(id) },
          onNavigateToTransactions = { navigateTopLevel(TransactionsRoute) },
          onNavigateToBudgets = {
            activeSubTab = BudgetsLoansSubTab.BUDGETS
            navigateTopLevel(BudgetsLoansRoute)
          },
          onNavigateToLoans = {
            activeSubTab = BudgetsLoansSubTab.LOANS
            navigateTopLevel(BudgetsLoansRoute)
          },
          onNavigateToSavings = {
            activeSubTab = BudgetsLoansSubTab.SAVINGS
            navigateTopLevel(BudgetsLoansRoute)
          },
          onNavigateToReports = {
            navigateTopLevel(ReportsRoute)
          },
          onUpdateSafeToSpendConfig = { r, s, i, e ->
            viewModel.updateSafeToSpendSettings(r, s, i, e)
          },
        )
      }

      composable<TransactionsRoute> {
        TransactionsScreen(
          snapshot = snapshot,
          onAddTransaction = {
            editingTransaction = null
            sheetInitialType = TransactionType.EXPENSE
            showQuickSheet = true
          },
          onEditTransaction = { tx ->
            editingTransaction = tx
            sheetInitialType = tx.type
            showQuickSheet = true
          },
          onMarkBillPaid = { id -> viewModel.markBillPaid(id) },
          onOpenAutoDetect = { showAutoDetectSheet = true },
        )
      }

      composable<BudgetsLoansRoute> {
        BudgetsLoansHubScreen(
          selectedSubTab = activeSubTab,
          onSelectSubTab = { activeSubTab = it },
          snapshot = snapshot,
          allBudgetHistory = state.allBudgetHistory,
          onUpsertBudget = { catId, ym, limit, cur, rollEnabled, rollAmt ->
            viewModel.upsertBudget(catId, ym, limit, cur, rollEnabled, rollAmt)
          },
          onDeleteBudget = { id -> viewModel.deleteBudget(id) },
          onAddLoan = { name, lender, princ, out, rate, emi, cur, dueDay, months ->
            viewModel.addLoan(name, lender, princ, out, rate, emi, cur, dueDay, months)
          },
          onDeleteLoan = { loan -> viewModel.deleteLoan(loan) },
          onRecordEmi = { loan, pay, extra, note ->
            viewModel.recordEmiPayment(loan, pay, extra, note)
          },
          onEstimateEarlyRepayment = { loan, extra ->
            viewModel.estimateEarlyRepayment(loan, extra)
          },
          onCreateSavingsGoal = { name, type, target, saved, cur, months, isEf ->
            viewModel.createSavingsGoal(name, type, target, saved, cur, months, isEf)
          },
          onContributeGoal = { goal, amt, note ->
            viewModel.contributeToSavingsGoal(goal, amt, note)
          },
          onDeleteGoal = { goal -> viewModel.deleteSavingsGoal(goal) },
          onSimulateExtraEmi = { loan, extra ->
            viewModel.simulateExtraEmiForLoan(loan, extra)
          },
          onCompareDebtStrategy = { extraBudget ->
            viewModel.compareDebtStrategyWithExtraBudget(snapshot, extraBudget)
          },
        )
      }

      composable<SmartShoppingRoute> {
        SmartShoppingScreen(
          shoppingLists = state.shoppingLists,
          shoppingItems = state.shoppingItems,
          shopComparisonGroups = state.shopComparisonGroups,
          plannerOverview = state.shoppingPlannerOverview,
          primaryCurrency = snapshot.primaryCurrency,
          primaryCurrencyCode = snapshot.primaryCurrencyCode,
          onUpsertList = { id, name, budget, cur, notes ->
            viewModel.upsertShoppingList(id, name, budget, cur, notes)
          },
          onDeleteList = { id -> viewModel.deleteShoppingList(id) },
          onUpsertItem = { id, listId, name, cat, qty, unit, est, actual ->
            viewModel.upsertShoppingItem(id, listId, name, cat, qty, unit, est, actual)
          },
          onToggleItemPurchased = { item -> viewModel.toggleShoppingItemPurchased(item) },
          onDeleteItem = { id -> viewModel.deleteShoppingItem(id) },
          onUpsertShopPrice = { product, shop, loc, unit, price, cur, source ->
            viewModel.upsertShopPrice(0L, product, shop, loc, unit, price, cur, source)
          },
          onDeleteShopPrice = { id -> viewModel.deleteShopPrice(id) },
          onSearchProducts = { q -> viewModel.searchShoppingProducts(q) },
        )
      }

      composable<ReportsRoute> {
        ReportsScreen(
          snapshot = snapshot,
          reportPreview = state.reportPreview,
          buildCharts = { period, sDay, eDay ->
            viewModel.buildChartsForPeriod(snapshot, period, sDay, eDay)
          },
          onGeneratePreview = { req -> viewModel.generateReportPreview(req) },
          onDismissPreview = { viewModel.dismissReportPreview() },
          onExportReport = { req, format, saveToDevice ->
            viewModel.exportReport(req, format, saveToDevice)
          },
        )
      }

      composable<ProfileRoute> {
        ProfileScreen(
          snapshot = snapshot,
          lastDriveBackupTimestamp = state.lastDriveBackupTimestamp,
          lastDriveBackupStatus = state.lastDriveBackupStatus,
          googleAuthStatus = state.googleAuthStatus,
          reportPreview = state.reportPreview,
          onSignInWithGoogle = { viewModel.signInWithGoogle(context) },
          onSignOutGoogle = { viewModel.signOutPreservingLocalData() },
          onBackupToGoogleDrive = { token, pass -> viewModel.backupToGoogleDrive(token, pass) },
          onRestoreFromGoogleDrive = { token, pass -> viewModel.restoreFromGoogleDrive(token, pass) },
          onPreviewReport = { req -> viewModel.generateReportPreview(req) },
          onDismissPreview = { viewModel.dismissReportPreview() },
          onExportReport = { req, format, saveToDevice ->
            viewModel.exportReport(req, format, saveToDevice)
          },
          onSetThemeMode = { viewModel.setThemeMode(it) },
          onSetPrimaryCurrency = { viewModel.setPrimaryCurrency(it) },
          onUpdateAppLock = { enabled, launch, bg, bio ->
            viewModel.updateAppLockSettings(enabled, launch, bg, bio)
          },
          onOpenPrivacySecurity = { navController.navigate(PrivacyContractRoute) },
          onEraseAllData = { viewModel.eraseAllFinancialData() },
          onSetLanguageCode = { viewModel.setLanguageCode(it) },
          skyPlusOneStatus = state.skyPlusOneStatus,
          cloudSync = state.cloudSync,
          onSetupOrUnlockCloudKey = { pass, enableAfter ->
            viewModel.setupOrUnlockCloudEncryption(pass, enableAfter)
          },
          onRotateRecoveryPassphrase = { oldPass, newPass ->
            viewModel.rotateRecoveryPassphrase(oldPass, newPass)
          },
          onSetFirestoreSyncEnabled = { enabled ->
            viewModel.setFirestoreCloudSyncEnabled(enabled)
          },
          onSyncNow = { viewModel.syncFirestoreNow() },
          onOpenGuestMigration = { viewModel.openGuestMigrationPreview() },
          onResolveConflict = { conflictId, resolution ->
            viewModel.resolveSyncConflict(conflictId, resolution)
          },
          onRefreshDriveBackups = { viewModel.refreshDriveBackupsList() },
          onRestoreDriveBackupSelection = { fileId, overwrite, pass ->
            viewModel.restoreFromGoogleDrive(fileId, overwrite, pass)
          },
          onSetDriveAutoBackup = { enabled, pass ->
            viewModel.setDriveAutoBackupEnabled(enabled, pass)
          },
          onSetDriveWifiOnly = { wifiOnly ->
            viewModel.setDriveWifiOnly(wifiOnly)
          },
        )
      }

      composable<PrivacyContractRoute> {
        BackHandler { navController.popBackStack() }
        PrivacyContractScreen(onBack = { navController.popBackStack() })
      }
    }
  }
}
