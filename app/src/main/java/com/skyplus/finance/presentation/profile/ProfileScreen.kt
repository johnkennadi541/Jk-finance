package com.skyplus.finance.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.backup.GoogleAuthConfigStatus
import com.skyplus.finance.data.entities.SyncConflictStatus
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.designsystem.SkyPlusPrimaryButton
import com.skyplus.finance.designsystem.SkyPlusSecondaryButton
import com.skyplus.finance.designsystem.SkyPlusSpacingTokens
import com.skyplus.finance.domain.model.AppThemeMode
import com.skyplus.finance.domain.monetization.OfflineFreeEntitlementGateway
import com.skyplus.finance.domain.monetization.SkyPlusOneStatus
import com.skyplus.finance.domain.report.FinancialReportData
import com.skyplus.finance.domain.report.ReportExportFormat
import com.skyplus.finance.domain.report.ReportRequest
import com.skyplus.finance.domain.usecase.DashboardSnapshot
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.presentation.CloudSyncUiState
import com.skyplus.finance.presentation.components.CurrencyPickerDialog
import com.skyplus.finance.presentation.components.ExportReportModalSheet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
  snapshot: DashboardSnapshot,
  lastDriveBackupTimestamp: Long,
  lastDriveBackupStatus: String,
  googleAuthStatus: GoogleAuthConfigStatus,
  reportPreview: FinancialReportData?,
  onSignInWithGoogle: () -> Unit,
  onSignOutGoogle: () -> Unit,
  onBackupToGoogleDrive: (oauthToken: String?, passphrase: String) -> Unit,
  onRestoreFromGoogleDrive: (oauthToken: String?, passphrase: String) -> Unit,
  onPreviewReport: (ReportRequest) -> Unit,
  onDismissPreview: () -> Unit,
  onExportReport: (ReportRequest, ReportExportFormat, saveToDevice: Boolean) -> Unit,
  onSetThemeMode: (AppThemeMode) -> Unit,
  onSetPrimaryCurrency: (String) -> Unit,
  onUpdateAppLock: (
    enabled: Boolean?,
    lockOnLaunch: Boolean?,
    lockOnBackgroundReturn: Boolean?,
    biometricPreferred: Boolean?,
  ) -> Unit,
  onOpenPrivacySecurity: () -> Unit,
  onEraseAllData: () -> Unit,
  @Suppress("UNUSED_PARAMETER") onSetLanguageCode: (String) -> Unit = {},
  @Suppress("UNUSED_PARAMETER") skyPlusOneStatus: SkyPlusOneStatus = OfflineFreeEntitlementGateway().currentStatus(),
  cloudSync: CloudSyncUiState = CloudSyncUiState(),
  onSetupOrUnlockCloudKey: (passphrase: String, enableSyncAfterUnlock: Boolean) -> Unit = { _, _ -> },
  onRotateRecoveryPassphrase: (oldPass: String, newPass: String) -> Unit = { _, _ -> },
  onSetFirestoreSyncEnabled: (Boolean) -> Unit = {},
  onSyncNow: () -> Unit = {},
  onOpenGuestMigration: () -> Unit = {},
  onResolveConflict: (Long, SyncConflictStatus) -> Unit = { _, _ -> },
  onRefreshDriveBackups: () -> Unit = {},
  onRestoreDriveBackupSelection: (fileId: String?, overwrite: Boolean, passphrase: String) -> Unit = { _, _, _ -> },
  onSetDriveAutoBackup: (enabled: Boolean, passphrase: String?) -> Unit = { _, _ -> },
  onSetDriveWifiOnly: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  val spacing = SkyPlusSpacingTokens()
  val settings = snapshot.settings

  var showCurrencyPicker by remember { mutableStateOf(false) }
  var showExportSheet by remember { mutableStateOf(false) }
  var showDriveBackupDialog by remember { mutableStateOf(false) }
  var showDriveAutoBackupDialog by remember { mutableStateOf(false) }
  var showDriveRestoreDialog by remember { mutableStateOf(false) }
  var showRecoveryKeyDialog by remember { mutableStateOf(false) }
  var enableSyncAfterKeyUnlock by remember { mutableStateOf(false) }
  var showRotatePassphraseDialog by remember { mutableStateOf(false) }
  var showConflictsDialog by remember { mutableStateOf(false) }
  var showEraseConfirmStep1 by remember { mutableStateOf(false) }
  var showEraseConfirmStep2 by remember { mutableStateOf(false) }

  if (showCurrencyPicker) {
    CurrencyPickerDialog(
      currencies = snapshot.allCurrencies,
      selectedCode = snapshot.primaryCurrencyCode,
      onSelect = { onSetPrimaryCurrency(it.code) },
      onDismiss = { showCurrencyPicker = false },
    )
  }

  if (showExportSheet) {
    ExportReportModalSheet(
      allCurrencies = snapshot.allCurrencies,
      defaultCurrencyCode = snapshot.primaryCurrencyCode,
      onPreview = onPreviewReport,
      onExport = onExportReport,
      onDismiss = { showExportSheet = false },
    )
  }

  if (reportPreview != null) {
    AlertDialog(
      onDismissRequest = onDismissPreview,
      title = { Text(reportPreview.title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Period: ${reportPreview.dateRangeText}")
          Text("Income: ${reportPreview.totalIncomeFormatted}")
          Text("Expenses: ${reportPreview.totalExpensesFormatted}")
          Text("Net Savings: ${reportPreview.netSavingsFormatted}")
          Text("Closing Balance: ${reportPreview.closingBalanceFormatted}")
        }
      },
      confirmButton = {
        TextButton(onClick = onDismissPreview) { Text("Close") }
      },
    )
  }

  if (showRecoveryKeyDialog) {
    val isNewSetup = !cloudSync.hasRemoteRecoveryMetadata
    RecoveryPassphraseSetupDialog(
      hasExistingRemoteKey = cloudSync.hasRemoteRecoveryMetadata,
      title = if (isNewSetup) "Create Cloud Recovery Passphrase" else "Unlock Cloud Encryption Key",
      subtitle = if (isNewSetup) {
        "Set a strong passphrase to wrap your 256-bit cloud key. All synced data in Firestore is end-to-end encrypted before leaving your device."
      } else {
        "Enter your existing Recovery Passphrase to unlock encrypted Firestore sync on this device."
      },
      confirmLabel = if (enableSyncAfterKeyUnlock) "Unlock & Enable Sync" else "Unlock Key",
      requireConfirmationField = isNewSetup,
      onConfirm = { pass ->
        showRecoveryKeyDialog = false
        onSetupOrUnlockCloudKey(pass, enableSyncAfterKeyUnlock)
      },
      onDismiss = { showRecoveryKeyDialog = false },
    )
  }

  if (showRotatePassphraseDialog) {
    RotatePassphraseDialog(
      onConfirm = { oldPass, newPass ->
        showRotatePassphraseDialog = false
        onRotateRecoveryPassphrase(oldPass, newPass)
      },
      onDismiss = { showRotatePassphraseDialog = false },
    )
  }

  if (showConflictsDialog) {
    SyncConflictResolutionDialog(
      conflicts = cloudSync.pendingConflicts,
      onResolve = onResolveConflict,
      onDismiss = { showConflictsDialog = false },
    )
  }

  if (showDriveBackupDialog) {
    DrivePassphraseDialog(
      title = "Back Up to Google Drive",
      subtitle = "Your financial archive is encrypted with AES-256-GCM before uploading to your \"SKYPLUS FINANCE Backups\" Google Drive folder.",
      confirmLabel = "Back Up Now",
      onConfirm = { pass ->
        showDriveBackupDialog = false
        onBackupToGoogleDrive(null, pass)
      },
      onDismiss = { showDriveBackupDialog = false },
    )
  }

  if (showDriveAutoBackupDialog) {
    DrivePassphraseDialog(
      title = "Enable Daily Drive Auto-Backup",
      subtitle = "Your passphrase is protected locally in Android Keystore only while Auto-Backup is enabled and cleared immediately if you turn it off.",
      confirmLabel = "Enable Auto-Backup",
      onConfirm = { pass ->
        showDriveAutoBackupDialog = false
        onSetDriveAutoBackup(true, pass)
      },
      onDismiss = { showDriveAutoBackupDialog = false },
    )
  }

  if (showDriveRestoreDialog) {
    DriveRestorePickerDialog(
      backups = cloudSync.driveBackupsList,
      onRefreshList = onRefreshDriveBackups,
      onConfirmRestore = { fileId, overwrite, pass ->
        showDriveRestoreDialog = false
        onRestoreDriveBackupSelection(fileId, overwrite, pass)
      },
      onDismiss = { showDriveRestoreDialog = false },
    )
  }

  if (showEraseConfirmStep1) {
    AlertDialog(
      onDismissRequest = { showEraseConfirmStep1 = false },
      title = { Text(strings.eraseAllData) },
      text = { Text(strings.eraseWarning) },
      confirmButton = {
        Button(
          onClick = {
            showEraseConfirmStep1 = false
            showEraseConfirmStep2 = true
          },
          colors = ButtonDefaults.buttonColors(containerColor = SkyPlusBrandColors.ErrorCoral),
        ) { Text("Continue") }
      },
      dismissButton = {
        TextButton(onClick = { showEraseConfirmStep1 = false }) { Text(strings.cancel) }
      },
    )
  }

  if (showEraseConfirmStep2) {
    var typedConfirm by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showEraseConfirmStep2 = false },
      title = { Text("Final Confirmation") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Type DELETE to permanently erase all financial records.")
          OutlinedTextField(
            value = typedConfirm,
            onValueChange = { typedConfirm = it },
            singleLine = true,
            label = { Text("Type DELETE") },
          )
        }
      },
      confirmButton = {
        Button(
          enabled = typedConfirm.trim().equals("DELETE", ignoreCase = true),
          onClick = {
            showEraseConfirmStep2 = false
            onEraseAllData()
          },
          colors = ButtonDefaults.buttonColors(containerColor = SkyPlusBrandColors.ErrorCoral),
        ) { Text(strings.delete) }
      },
      dismissButton = {
        TextButton(onClick = { showEraseConfirmStep2 = false }) { Text(strings.cancel) }
      },
    )
  }

  val lastBackupFormatted = remember(lastDriveBackupTimestamp) {
    formatProfileTimestamp(lastDriveBackupTimestamp)
  }
  val lastSyncFormatted = remember(cloudSync.lastSyncTimestamp) {
    formatProfileTimestamp(cloudSync.lastSyncTimestamp)
  }

  val activeAccountEmail = cloudSync.email ?: snapshot.linkedGoogleEmail
  val isGoogleLinked = cloudSync.isSignedIn || activeAccountEmail != null

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = spacing.maxContentWidth),
      contentPadding = PaddingValues(horizontal = spacing.md, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Text(
          text = strings.profileTitle,
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.testTag("profile_screen_title"),
        )
      }

      // 1. Account Section (Guest Mode or Google Account)
      item {
        SkyPlusPortfolioCard(modifier = Modifier.testTag("profile_account_card")) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Box(
              modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.14f)),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = SkyPlusBrandColors.RoyalBlue,
                modifier = Modifier.size(28.dp),
              )
            }
            Column(modifier = Modifier.weight(1f)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = activeAccountEmail ?: strings.guestModeLabel,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                )
                Surface(
                  color = SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.14f),
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = if (isGoogleLinked) "Signed In" else "Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyPlusBrandColors.EmeraldGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
              Text(
                text = if (isGoogleLinked) {
                  "Google account linked. Cloud Sync and Google Drive Backup remain independent and optional — neither turns on automatically."
                } else {
                  strings.guestModeSubtitle
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          if (!isGoogleLinked) {
            SkyPlusPrimaryButton(
              text = strings.signInWithGoogle,
              onClick = onSignInWithGoogle,
              modifier = Modifier.fillMaxWidth().testTag("google_sign_in_button"),
            )
          } else {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              SkyPlusSecondaryButton(
                text = "Sign Out",
                icon = Icons.AutoMirrored.Filled.Logout,
                onClick = onSignOutGoogle,
                modifier = Modifier.weight(1f).testTag("google_sign_out_button"),
              )
              OutlinedButton(
                onClick = onSignInWithGoogle,
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("google_switch_account_button"),
              ) {
                Text("Switch Account", maxLines = 1)
              }
            }
          }
        }
      }

      // 2. Independent Feature A: Firestore Cloud Sync (End-to-End Encrypted, Opt-In)
      item {
        SkyPlusPortfolioCard(modifier = Modifier.testTag("profile_firestore_sync_card")) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.Sync, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
              Column {
                Text("Firestore Cloud Sync", style = MaterialTheme.typography.titleMedium)
                Text(
                  text = if (!cloudSync.isSignedIn) {
                    "Sign in with Google to enable optional encrypted multi-device sync."
                  } else if (cloudSync.firestoreSyncEnabled) {
                    "End-to-end encrypted sync active"
                  } else {
                    "Off — local database remains offline-only"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Switch(
              checked = cloudSync.firestoreSyncEnabled,
              enabled = cloudSync.isSignedIn,
              onCheckedChange = { wantEnabled ->
                if (!wantEnabled) {
                  onSetFirestoreSyncEnabled(false)
                } else if (!cloudSync.isRecoveryKeyUnlocked) {
                  enableSyncAfterKeyUnlock = true
                  showRecoveryKeyDialog = true
                } else {
                  onSetFirestoreSyncEnabled(true)
                }
              },
              modifier = Modifier.testTag("switch_firestore_sync"),
            )
          }

          if (cloudSync.isSignedIn) {
            Text(
              text = "Status: ${cloudSync.lastSyncStatus}  •  Last sync: $lastSyncFormatted  •  Pending queue: ${cloudSync.pendingOutboxCount}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              if (!cloudSync.isRecoveryKeyUnlocked) {
                SkyPlusPrimaryButton(
                  text = if (cloudSync.hasRemoteRecoveryMetadata) "Unlock Key" else "Set Recovery Key",
                  icon = Icons.Filled.Key,
                  onClick = {
                    enableSyncAfterKeyUnlock = false
                    showRecoveryKeyDialog = true
                  },
                  modifier = Modifier.weight(1f).testTag("unlock_cloud_key_button"),
                )
              } else {
                SkyPlusPrimaryButton(
                  text = "Sync Now",
                  icon = Icons.Filled.Sync,
                  enabled = cloudSync.firestoreSyncEnabled,
                  onClick = onSyncNow,
                  modifier = Modifier.weight(1f).testTag("firestore_sync_now_button"),
                )
                SkyPlusSecondaryButton(
                  text = "Passphrase",
                  icon = Icons.Filled.Key,
                  onClick = { showRotatePassphraseDialog = true },
                  modifier = Modifier.weight(1f).testTag("rotate_passphrase_button"),
                )
              }
            }

            val pendingMigCount = cloudSync.guestMigrationPreview?.totalRecordsToMigrate ?: 0
            if (pendingMigCount > 0) {
              OutlinedButton(
                onClick = onOpenGuestMigration,
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("open_guest_migration_button"),
              ) {
                Text("Migrate Offline Local Records ($pendingMigCount)")
              }
            }

            if (cloudSync.pendingConflicts.isNotEmpty()) {
              Surface(
                color = SkyPlusBrandColors.WarningAmber.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { showConflictsDialog = true }
                  .testTag("resolve_conflicts_banner"),
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(Icons.Filled.Warning, contentDescription = null, tint = SkyPlusBrandColors.WarningAmber)
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "${cloudSync.pendingConflicts.size} Sync Conflict(s) Need Review",
                      style = MaterialTheme.typography.labelLarge,
                      color = SkyPlusBrandColors.WarningAmber,
                      fontWeight = FontWeight.Bold,
                    )
                    Text(
                      text = "Tap to choose Local, Cloud, or Keep Both.",
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // 3. Independent Feature B: Google Drive Encrypted Backup (Visible folder, AES-256-GCM)
      item {
        SkyPlusPortfolioCard(modifier = Modifier.testTag("profile_drive_backup_card")) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.CloudDone, contentDescription = null, tint = SkyPlusBrandColors.EmeraldGreen)
            Column(modifier = Modifier.weight(1f)) {
              Text(strings.googleDriveBackupTitle, style = MaterialTheme.typography.titleMedium)
              Text(
                text = "Folder: SKYPLUS FINANCE Backups (AES-256-GCM)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Text(
            text = "Status: $lastDriveBackupStatus  •  Last backup: $lastBackupFormatted",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (googleAuthStatus is GoogleAuthConfigStatus.Blocker) {
            Surface(
              color = SkyPlusBrandColors.WarningAmber.copy(alpha = 0.14f),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = googleAuthStatus.reason,
                  style = MaterialTheme.typography.labelLarge,
                  color = SkyPlusBrandColors.WarningAmber,
                )
                googleAuthStatus.requiredSteps.forEach { step ->
                  Text("• $step", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyPlusPrimaryButton(
              text = "Backup Now",
              icon = Icons.Filled.CloudUpload,
              onClick = { showDriveBackupDialog = true },
              modifier = Modifier.weight(1f).testTag("drive_backup_now_button"),
            )
            SkyPlusSecondaryButton(
              text = "Restore",
              icon = Icons.Filled.Restore,
              onClick = {
                onRefreshDriveBackups()
                showDriveRestoreDialog = true
              },
              modifier = Modifier.weight(1f).testTag("drive_restore_button"),
            )
          }

          HorizontalDivider()

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Daily Auto-Backup", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
              Text(
                text = if (cloudSync.isSignedIn) {
                  "Independent daily encrypted upload to your Google Drive."
                } else {
                  "Sign in with Google to enable automatic Drive backups."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = cloudSync.driveAutoBackupEnabled,
              enabled = cloudSync.isSignedIn,
              onCheckedChange = { wantEnabled ->
                if (!wantEnabled) {
                  onSetDriveAutoBackup(false, null)
                } else {
                  showDriveAutoBackupDialog = true
                }
              },
              modifier = Modifier.testTag("switch_drive_auto_backup"),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Wi-Fi Only for Auto-Backup",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
            Switch(
              checked = cloudSync.driveWifiOnly,
              onCheckedChange = onSetDriveWifiOnly,
              modifier = Modifier.testTag("switch_drive_wifi_only"),
            )
          }
        }
      }

      // 4. Unified EXPORT Action (PDF, DOCX, CSV — no local backup menu)
      item {
        SkyPlusPortfolioCard(modifier = Modifier.testTag("profile_export_card")) {
          Text("Financial Statements & Reports", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Export Weekly, Monthly, Yearly, or Custom Date Range financial reports as PDF, Microsoft Word (.docx), or CSV.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SkyPlusPrimaryButton(
            text = strings.exportButtonLabel,
            icon = Icons.Filled.Download,
            onClick = { showExportSheet = true },
            modifier = Modifier.fillMaxWidth().testTag("profile_export_button"),
          )
        }
      }

      // 5. Settings (Appearance, Primary Currency, App Lock & Biometrics)
      item {
        SkyPlusPortfolioCard {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
            Text(strings.settingsTitle, style = MaterialTheme.typography.titleMedium)
          }

          Text(strings.themeSetting, style = MaterialTheme.typography.labelLarge)
          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf(
              AppThemeMode.SYSTEM to strings.themeSystem,
              AppThemeMode.LIGHT to strings.themeLight,
              AppThemeMode.DARK to strings.themeDark,
            )
            modes.forEachIndexed { idx, (mode, label) ->
              SegmentedButton(
                selected = snapshot.themeMode == mode,
                onClick = { onSetThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size),
                modifier = Modifier.testTag("theme_${mode.name.lowercase()}"),
              ) {
                Text(label, maxLines = 1)
              }
            }
          }

          HorizontalDivider()

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .minimumInteractiveComponentSize()
              .clickable { showCurrencyPicker = true }
              .padding(vertical = 4.dp)
              .testTag("primary_currency_setting"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Icon(Icons.Filled.Paid, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
              Column {
                Text(strings.primaryCurrencySetting, style = MaterialTheme.typography.titleMedium)
                Text(
                  text = "${snapshot.primaryCurrencyCode} • ${snapshot.primaryCurrency?.displayName ?: ""} (${snapshot.primaryCurrency?.symbol ?: ""})",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
          }

          HorizontalDivider()

          val lockEnabled = settings?.appLockEnabled ?: false
          val lockLaunch = settings?.lockOnLaunch ?: true
          val lockBg = settings?.lockOnBackgroundReturn ?: true

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = SkyPlusBrandColors.EmeraldGreen)
              Column {
                Text(strings.appLockSetting, style = MaterialTheme.typography.titleMedium)
                Text(strings.biometricUnlock, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
            Switch(
              checked = lockEnabled,
              onCheckedChange = { onUpdateAppLock(it, null, null, null) },
              modifier = Modifier.testTag("switch_app_lock"),
            )
          }

          if (lockEnabled) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(strings.lockOnLaunch, modifier = Modifier.weight(1f))
              Switch(
                checked = lockLaunch,
                onCheckedChange = { onUpdateAppLock(null, it, null, null) },
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(strings.lockOnBackground, modifier = Modifier.weight(1f))
              Switch(
                checked = lockBg,
                onCheckedChange = { onUpdateAppLock(null, null, it, null) },
              )
            }
          }
        }
      }

      // 6. Privacy & Security + Erase All Data
      item {
        SkyPlusPortfolioCard {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .minimumInteractiveComponentSize()
              .clickable(onClick = onOpenPrivacySecurity)
              .padding(vertical = 4.dp)
              .testTag("open_privacy_contract_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Icon(Icons.Filled.PrivacyTip, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
              Text(strings.privacySecurityTitle, style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
          }
          HorizontalDivider()
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .minimumInteractiveComponentSize()
              .clickable { showEraseConfirmStep1 = true }
              .padding(vertical = 4.dp)
              .testTag("erase_all_data_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = SkyPlusBrandColors.ErrorCoral)
            Text(strings.eraseAllData, style = MaterialTheme.typography.titleMedium, color = SkyPlusBrandColors.ErrorCoral)
          }
        }
      }

      // 7. About SKYPLUS
      item {
        SkyPlusPortfolioCard(modifier = Modifier.testTag("profile_about_card")) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = SkyPlusBrandColors.RoyalBlue)
            Text(strings.aboutTitle, style = MaterialTheme.typography.titleMedium)
          }
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            strings.aboutLines.forEach { line ->
              Text(
                text = "• $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      item { Spacer(modifier = Modifier.height(12.dp)) }
    }
  }
}

private fun formatProfileTimestamp(epochMillis: Long): String {
  if (epochMillis <= 0L) return "Never"
  return Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

@Composable
private fun DrivePassphraseDialog(
  title: String,
  subtitle: String,
  confirmLabel: String,
  onConfirm: (passphrase: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var passphrase by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
          value = passphrase,
          onValueChange = { passphrase = it },
          label = { Text("Recovery Passphrase (8+ chars)") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("drive_backup_passphrase_input"),
        )
      }
    },
    confirmButton = {
      Button(
        enabled = passphrase.length >= 8,
        onClick = { onConfirm(passphrase) },
        modifier = Modifier.testTag("drive_backup_confirm_button"),
      ) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
