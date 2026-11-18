package com.skyplus.finance.data.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.skyPlusPrefs: DataStore<Preferences> by preferencesDataStore(name = "skyplus_ui_prefs")

@Singleton
class AppPreferencesDataStore @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  companion object {
    private val LAST_BACKGROUND_TIMESTAMP = longPreferencesKey("last_background_timestamp")
    private val AUTO_BACKUP_WIFI_ONLY = booleanPreferencesKey("auto_backup_wifi_only")
    private val LAST_LOCAL_EXPORT_TIMESTAMP = longPreferencesKey("last_local_export_timestamp")
    private val LAST_DRIVE_BACKUP_TIMESTAMP = longPreferencesKey("last_drive_backup_timestamp")
    private val LAST_DRIVE_BACKUP_STATUS = stringPreferencesKey("last_drive_backup_status")
    private val DEVICE_ID = stringPreferencesKey("installation_device_id")

    private fun safeUid(uid: String): String =
      uid.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "guest" }

    private fun firestoreSyncEnabledKey(uid: String) =
      booleanPreferencesKey("optin_firestore_sync_${safeUid(uid)}")

    private fun driveAutoBackupEnabledKey(uid: String) =
      booleanPreferencesKey("optin_drive_auto_backup_${safeUid(uid)}")

    private fun driveWifiOnlyKey(uid: String) =
      booleanPreferencesKey("drive_wifi_only_${safeUid(uid)}")

    private fun driveFolderIdKey(uid: String) =
      stringPreferencesKey("drive_folder_id_${safeUid(uid)}")

    private fun driveAuthorizedEmailKey(uid: String) =
      stringPreferencesKey("drive_authorized_email_${safeUid(uid)}")

    private fun lastSyncTimestampKey(uid: String) =
      longPreferencesKey("last_firestore_sync_ts_${safeUid(uid)}")

    private fun lastSyncStatusKey(uid: String) =
      stringPreferencesKey("last_firestore_sync_status_${safeUid(uid)}")

    private fun lastDriveTimestampForUidKey(uid: String) =
      longPreferencesKey("last_drive_backup_ts_${safeUid(uid)}")

    private fun lastDriveStatusForUidKey(uid: String) =
      stringPreferencesKey("last_drive_backup_status_${safeUid(uid)}")

    private fun guestMigrationCompletedKey(uid: String) =
      booleanPreferencesKey("guest_migration_done_${safeUid(uid)}")
  }

  val lastExportTimestamp: Flow<Long> = context.skyPlusPrefs.data.map { prefs ->
    prefs[LAST_LOCAL_EXPORT_TIMESTAMP] ?: 0L
  }

  val lastDriveBackupTimestamp: Flow<Long> = context.skyPlusPrefs.data.map { prefs ->
    prefs[LAST_DRIVE_BACKUP_TIMESTAMP] ?: 0L
  }

  val lastDriveBackupStatus: Flow<String> = context.skyPlusPrefs.data.map { prefs ->
    prefs[LAST_DRIVE_BACKUP_STATUS] ?: "Not backed up yet"
  }

  val wifiOnlyBackupPreferred: Flow<Boolean> = context.skyPlusPrefs.data.map { prefs ->
    prefs[AUTO_BACKUP_WIFI_ONLY] ?: true
  }

  suspend fun getOrCreateDeviceId(): String {
    val current = context.skyPlusPrefs.data.first()[DEVICE_ID]
    if (!current.isNullOrBlank()) return current
    val generated = "android-${UUID.randomUUID().toString().take(12)}"
    context.skyPlusPrefs.edit { prefs ->
      if (prefs[DEVICE_ID].isNullOrBlank()) {
        prefs[DEVICE_ID] = generated
      }
    }
    return context.skyPlusPrefs.data.first()[DEVICE_ID] ?: generated
  }

  fun observeFirestoreSyncEnabled(uid: String): Flow<Boolean> = context.skyPlusPrefs.data.map { prefs ->
    if (uid.isBlank() || uid == "GUEST") false else (prefs[firestoreSyncEnabledKey(uid)] ?: false)
  }

  suspend fun isFirestoreSyncEnabled(uid: String): Boolean {
    if (uid.isBlank() || uid == "GUEST") return false
    return context.skyPlusPrefs.data.first()[firestoreSyncEnabledKey(uid)] ?: false
  }

  suspend fun setFirestoreSyncEnabled(uid: String, enabled: Boolean) {
    if (uid.isBlank() || uid == "GUEST") return
    context.skyPlusPrefs.edit { prefs ->
      prefs[firestoreSyncEnabledKey(uid)] = enabled
    }
  }

  fun observeDriveAutoBackupEnabled(uid: String): Flow<Boolean> = context.skyPlusPrefs.data.map { prefs ->
    if (uid.isBlank() || uid == "GUEST") false else (prefs[driveAutoBackupEnabledKey(uid)] ?: false)
  }

  suspend fun isDriveAutoBackupEnabled(uid: String): Boolean {
    if (uid.isBlank() || uid == "GUEST") return false
    return context.skyPlusPrefs.data.first()[driveAutoBackupEnabledKey(uid)] ?: false
  }

  suspend fun setDriveAutoBackupEnabled(uid: String, enabled: Boolean) {
    if (uid.isBlank() || uid == "GUEST") return
    context.skyPlusPrefs.edit { prefs ->
      prefs[driveAutoBackupEnabledKey(uid)] = enabled
    }
  }

  fun observeDriveWifiOnly(uid: String): Flow<Boolean> = context.skyPlusPrefs.data.map { prefs ->
    prefs[driveWifiOnlyKey(uid)] ?: (prefs[AUTO_BACKUP_WIFI_ONLY] ?: true)
  }

  suspend fun isDriveWifiOnly(uid: String): Boolean {
    val prefs = context.skyPlusPrefs.data.first()
    return prefs[driveWifiOnlyKey(uid)] ?: (prefs[AUTO_BACKUP_WIFI_ONLY] ?: true)
  }

  suspend fun setDriveWifiOnly(uid: String, wifiOnly: Boolean) {
    context.skyPlusPrefs.edit { prefs ->
      prefs[AUTO_BACKUP_WIFI_ONLY] = wifiOnly
      if (uid.isNotBlank() && uid != "GUEST") {
        prefs[driveWifiOnlyKey(uid)] = wifiOnly
      }
    }
  }

  suspend fun getDriveFolderId(uid: String): String? {
    if (uid.isBlank()) return null
    return context.skyPlusPrefs.data.first()[driveFolderIdKey(uid)]
  }

  suspend fun setDriveFolderId(uid: String, folderId: String?) {
    if (uid.isBlank()) return
    context.skyPlusPrefs.edit { prefs ->
      if (folderId.isNullOrBlank()) {
        prefs.remove(driveFolderIdKey(uid))
      } else {
        prefs[driveFolderIdKey(uid)] = folderId
      }
    }
  }

  suspend fun getDriveAuthorizedEmail(uid: String): String? {
    if (uid.isBlank()) return null
    return context.skyPlusPrefs.data.first()[driveAuthorizedEmailKey(uid)]
  }

  suspend fun setDriveAuthorizedEmail(uid: String, email: String?) {
    if (uid.isBlank()) return
    context.skyPlusPrefs.edit { prefs ->
      if (email.isNullOrBlank()) {
        prefs.remove(driveAuthorizedEmailKey(uid))
      } else {
        prefs[driveAuthorizedEmailKey(uid)] = email
      }
    }
  }

  suspend fun clearDriveCacheForUid(uid: String) {
    if (uid.isBlank()) return
    context.skyPlusPrefs.edit { prefs ->
      prefs.remove(driveFolderIdKey(uid))
      prefs.remove(driveAuthorizedEmailKey(uid))
    }
  }

  fun observeLastSyncTimestamp(uid: String): Flow<Long> = context.skyPlusPrefs.data.map { prefs ->
    if (uid.isBlank() || uid == "GUEST") 0L else (prefs[lastSyncTimestampKey(uid)] ?: 0L)
  }

  suspend fun getLastSyncTimestamp(uid: String): Long {
    if (uid.isBlank() || uid == "GUEST") return 0L
    return context.skyPlusPrefs.data.first()[lastSyncTimestampKey(uid)] ?: 0L
  }

  fun observeLastSyncStatus(uid: String): Flow<String> = context.skyPlusPrefs.data.map { prefs ->
    if (uid.isBlank() || uid == "GUEST") {
      "Guest Mode (Offline Only)"
    } else {
      prefs[lastSyncStatusKey(uid)] ?: "Not synced yet"
    }
  }

  suspend fun recordSyncState(uid: String, timestampMillis: Long, status: String) {
    if (uid.isBlank() || uid == "GUEST") return
    context.skyPlusPrefs.edit { prefs ->
      if (timestampMillis > 0L) {
        prefs[lastSyncTimestampKey(uid)] = timestampMillis
      }
      prefs[lastSyncStatusKey(uid)] = status
    }
  }

  suspend fun isGuestMigrationCompleted(uid: String): Boolean {
    if (uid.isBlank() || uid == "GUEST") return false
    return context.skyPlusPrefs.data.first()[guestMigrationCompletedKey(uid)] ?: false
  }

  suspend fun setGuestMigrationCompleted(uid: String, completed: Boolean) {
    if (uid.isBlank() || uid == "GUEST") return
    context.skyPlusPrefs.edit { prefs ->
      prefs[guestMigrationCompletedKey(uid)] = completed
    }
  }

  suspend fun recordBackgroundTimestamp(timestampMillis: Long) {
    context.skyPlusPrefs.edit { prefs ->
      prefs[LAST_BACKGROUND_TIMESTAMP] = timestampMillis
    }
  }

  suspend fun getLastBackgroundTimestamp(): Long {
    return context.skyPlusPrefs.data.first()[LAST_BACKGROUND_TIMESTAMP] ?: 0L
  }

  suspend fun recordLocalExportTimestamp(timestampMillis: Long) {
    context.skyPlusPrefs.edit { prefs ->
      prefs[LAST_LOCAL_EXPORT_TIMESTAMP] = timestampMillis
    }
  }

  suspend fun recordDriveBackupState(timestampMillis: Long, status: String) {
    context.skyPlusPrefs.edit { prefs ->
      prefs[LAST_DRIVE_BACKUP_TIMESTAMP] = timestampMillis
      prefs[LAST_DRIVE_BACKUP_STATUS] = status
    }
  }

  suspend fun recordDriveBackupStateForUid(uid: String, timestampMillis: Long, status: String) {
    context.skyPlusPrefs.edit { prefs ->
      prefs[LAST_DRIVE_BACKUP_TIMESTAMP] = timestampMillis
      prefs[LAST_DRIVE_BACKUP_STATUS] = status
      if (uid.isNotBlank()) {
        prefs[lastDriveTimestampForUidKey(uid)] = timestampMillis
        prefs[lastDriveStatusForUidKey(uid)] = status
      }
    }
  }
}
