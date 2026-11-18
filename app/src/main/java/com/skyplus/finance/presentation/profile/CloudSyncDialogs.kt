package com.skyplus.finance.presentation.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.backup.DriveBackupMetadataItem
import com.skyplus.finance.data.cloud.GuestMigrationPreview
import com.skyplus.finance.data.entities.SyncConflictEntity
import com.skyplus.finance.data.entities.SyncConflictStatus
import com.skyplus.finance.designsystem.SkyPlusBrandColors

@Composable
fun RecoveryPassphraseSetupDialog(
  hasExistingRemoteKey: Boolean,
  title: String,
  subtitle: String,
  confirmLabel: String,
  requireConfirmationField: Boolean,
  onConfirm: (passphrase: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var passphrase by remember { mutableStateOf("") }
  var confirmPassphrase by remember { mutableStateOf("") }

  val isValid = passphrase.length >= 8 && (!requireConfirmationField || passphrase == confirmPassphrase)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        if (!hasExistingRemoteKey && requireConfirmationField) {
          Surface(
            color = SkyPlusBrandColors.WarningAmber.copy(alpha = 0.14f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "Zero-knowledge encryption: your passphrase is never uploaded or stored in plaintext. Save it safely to recover on new devices.",
              style = MaterialTheme.typography.labelSmall,
              color = SkyPlusBrandColors.WarningAmber,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
        OutlinedTextField(
          value = passphrase,
          onValueChange = { passphrase = it },
          label = { Text("Recovery Passphrase (8+ chars)") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("recovery_passphrase_input"),
        )
        if (requireConfirmationField) {
          OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = { confirmPassphrase = it },
            label = { Text("Confirm Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            isError = confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase,
            modifier = Modifier.fillMaxWidth().testTag("recovery_passphrase_confirm_input"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        enabled = isValid,
        onClick = { onConfirm(passphrase) },
        modifier = Modifier.testTag("recovery_passphrase_submit_button"),
      ) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
fun RotatePassphraseDialog(
  onConfirm: (oldPass: String, newPass: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var oldPass by remember { mutableStateOf("") }
  var newPass by remember { mutableStateOf("") }
  var confirmNew by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Change Recovery Passphrase") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Re-wraps your account's 256-bit encryption key with a new passphrase without re-uploading existing financial records.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          value = oldPass,
          onValueChange = { oldPass = it },
          label = { Text("Current Passphrase") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = newPass,
          onValueChange = { newPass = it },
          label = { Text("New Passphrase (8+ chars)") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = confirmNew,
          onValueChange = { confirmNew = it },
          label = { Text("Confirm New Passphrase") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          isError = confirmNew.isNotEmpty() && confirmNew != newPass,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        enabled = oldPass.isNotEmpty() && newPass.length >= 8 && newPass == confirmNew,
        onClick = { onConfirm(oldPass, newPass) },
      ) {
        Text("Update Passphrase")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
fun GuestMigrationDialog(
  preview: GuestMigrationPreview,
  onConfirmMigrate: (deduplicateWithCloud: Boolean) -> Unit,
  onKeepLocalOnly: () -> Unit,
) {
  var deduplicate by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onKeepLocalOnly,
    title = { Text("Migrate Offline Guest Data?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "You have local offline records that have not yet been linked to this Google account:",
          style = MaterialTheme.typography.bodyMedium,
        )
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("• Transactions: ${preview.transactionCount}", style = MaterialTheme.typography.labelLarge)
            Text("• Budgets: ${preview.budgetCount}", style = MaterialTheme.typography.labelLarge)
            Text("• Loans: ${preview.loanCount}", style = MaterialTheme.typography.labelLarge)
            Text("• Savings Goals: ${preview.savingsGoalCount}", style = MaterialTheme.typography.labelLarge)
            Text("• Investments: ${preview.investmentCount}", style = MaterialTheme.typography.labelLarge)
            Text("• Shopping Lists: ${preview.shoppingListCount}", style = MaterialTheme.typography.labelLarge)
          }
        }
        if (preview.hasRemoteData) {
          Text(
            "Cloud data already exists on this account (${preview.estimatedDuplicateCount} potential duplicate transaction match(es) detected).",
            style = MaterialTheme.typography.labelMedium,
            color = SkyPlusBrandColors.WarningAmber,
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { deduplicate = !deduplicate },
          ) {
            Checkbox(checked = deduplicate, onCheckedChange = { deduplicate = it })
            Text(
              "Merge & skip exact duplicate transactions instead of creating copies",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Text(
          "Local records remain preserved either way.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirmMigrate(deduplicate) },
        modifier = Modifier.testTag("confirm_guest_migration_button"),
      ) {
        Text("Migrate ${preview.totalRecordsToMigrate} Records")
      }
    },
    dismissButton = {
      TextButton(onClick = onKeepLocalOnly) { Text("Keep Offline for Now") }
    },
  )
}

@Composable
fun SyncConflictResolutionDialog(
  conflicts: List<SyncConflictEntity>,
  onResolve: (conflictId: Long, resolution: SyncConflictStatus) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Resolve Sync Conflicts (${conflicts.size})") },
    text = {
      if (conflicts.isEmpty()) {
        Text("No pending sync conflicts.")
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(conflicts, key = { it.id }) { conflict ->
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = conflict.entityType.replace('_', ' '),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = SkyPlusBrandColors.RoyalBlue,
                )
                Text("This device: ${conflict.localSummary}", style = MaterialTheme.typography.bodySmall)
                Text("Cloud (v${conflict.remoteSyncVersion}): ${conflict.remoteSummary}", style = MaterialTheme.typography.bodySmall)
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  OutlinedButton(
                    onClick = { onResolve(conflict.id, SyncConflictStatus.RESOLVED_KEEP_LOCAL) },
                    modifier = Modifier.weight(1f).testTag("conflict_keep_local_${conflict.id}"),
                  ) {
                    Text("Local", maxLines = 1)
                  }
                  OutlinedButton(
                    onClick = { onResolve(conflict.id, SyncConflictStatus.RESOLVED_USE_CLOUD) },
                    modifier = Modifier.weight(1f).testTag("conflict_use_cloud_${conflict.id}"),
                  ) {
                    Text("Cloud", maxLines = 1)
                  }
                  OutlinedButton(
                    onClick = { onResolve(conflict.id, SyncConflictStatus.RESOLVED_KEEP_BOTH) },
                    modifier = Modifier.weight(1f).testTag("conflict_keep_both_${conflict.id}"),
                  ) {
                    Text("Both", maxLines = 1)
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}

@Composable
fun DriveRestorePickerDialog(
  backups: List<DriveBackupMetadataItem>,
  onRefreshList: () -> Unit,
  onConfirmRestore: (fileId: String?, overwriteExisting: Boolean, passphrase: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var selectedFileId by remember(backups) { mutableStateOf(backups.firstOrNull()?.id) }
  var overwriteExisting by remember { mutableStateOf(false) }
  var passphrase by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Restore from Google Drive") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            "Folder: SKYPLUS FINANCE Backups",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(onClick = onRefreshList) { Text("Refresh") }
        }

        if (backups.isEmpty()) {
          Text(
            "No backup list loaded yet or no encrypted backups found. Tap Refresh to inspect your Google Drive folder.",
            style = MaterialTheme.typography.bodySmall,
          )
        } else {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 160.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(backups, key = { it.id }) { item ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { selectedFileId = item.id },
              ) {
                RadioButton(
                  selected = selectedFileId == item.id,
                  onClick = { selectedFileId = item.id },
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(item.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                  Text(
                    item.createdTime ?: item.modifiedTime ?: "Encrypted Archive",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        HorizontalDivider()

        Text("Restore Strategy", style = MaterialTheme.typography.labelLarge)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().clickable { overwriteExisting = false },
        ) {
          RadioButton(selected = !overwriteExisting, onClick = { overwriteExisting = false })
          Text("Merge with current local data (recommended)", style = MaterialTheme.typography.bodySmall)
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().clickable { overwriteExisting = true },
        ) {
          RadioButton(selected = overwriteExisting, onClick = { overwriteExisting = true })
          Text("Overwrite local data (saves safety snapshot first)", style = MaterialTheme.typography.bodySmall)
        }

        if (overwriteExisting) {
          Surface(
            color = SkyPlusBrandColors.ErrorCoral.copy(alpha = 0.12f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              "Warning: Overwrite replaces local records after taking an encrypted pre-restore safety backup.",
              style = MaterialTheme.typography.labelSmall,
              color = SkyPlusBrandColors.ErrorCoral,
              modifier = Modifier.padding(8.dp),
            )
          }
        }

        OutlinedTextField(
          value = passphrase,
          onValueChange = { passphrase = it },
          label = { Text("Backup Recovery Passphrase (8+ chars)") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("drive_restore_passphrase_input"),
        )
      }
    },
    confirmButton = {
      Button(
        enabled = passphrase.length >= 8 && (backups.isEmpty() || selectedFileId != null),
        onClick = { onConfirmRestore(selectedFileId, overwriteExisting, passphrase) },
        colors = if (overwriteExisting) {
          ButtonDefaults.buttonColors(containerColor = SkyPlusBrandColors.ErrorCoral)
        } else {
          ButtonDefaults.buttonColors()
        },
        modifier = Modifier.testTag("drive_restore_confirm_button"),
      ) {
        Text(if (overwriteExisting) "Overwrite & Restore" else "Merge Restore")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
