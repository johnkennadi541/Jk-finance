package com.skyplus.finance.data.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skyplus.finance.data.backup.GoogleDriveBackupManager
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.AutoBackupPassphraseStore
import com.skyplus.finance.data.security.CloudKeyManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudSyncWorkerEntryPoint {
  fun firebaseAuthManager(): FirebaseAuthManager
  fun preferencesDataStore(): AppPreferencesDataStore
  fun cloudKeyManager(): CloudKeyManager
  fun cloudSyncEngine(): CloudSyncEngine
  fun driveAuthorizationManager(): DriveAuthorizationManager
  fun googleDriveBackupManager(): GoogleDriveBackupManager
  fun autoBackupPassphraseStore(): AutoBackupPassphraseStore
}

/**
 * WorkManager worker for Option C Firestore encrypted synchronization.
 * Strictly no-ops in Guest Mode or when Firestore Sync is toggled OFF.
 */
class FirestoreOutboxSyncWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val entryPoint = EntryPointAccessors.fromApplication(
      applicationContext,
      CloudSyncWorkerEntryPoint::class.java,
    )
    val user = entryPoint.firebaseAuthManager().currentUser() ?: return Result.success()
    val prefs = entryPoint.preferencesDataStore()
    if (!prefs.isFirestoreSyncEnabled(user.uid)) {
      return Result.success()
    }
    if (!entryPoint.cloudKeyManager().hasLocalUnlockedDek(user.uid)) {
      prefs.recordSyncState(user.uid, 0L, "Unlock Recovery Passphrase to resume background sync")
      return Result.success()
    }

    val syncResult = entryPoint.cloudSyncEngine().syncNow(user.uid)
    return if (syncResult.isSuccess) {
      Result.success()
    } else {
      if (runAttemptCount < 4) Result.retry() else Result.failure()
    }
  }
}

/**
 * WorkManager worker for Option C scheduled encrypted Google Drive backups.
 * Strictly independent of Firestore Cloud Sync.
 * Runs only when user explicitly enables Auto-Backup, respects Wi-Fi preference, and uploads
 * AES-256-GCM v2 backup to visible `SKYPLUS FINANCE Backups` folder.
 */
class DrivePeriodicBackupWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val entryPoint = EntryPointAccessors.fromApplication(
      applicationContext,
      CloudSyncWorkerEntryPoint::class.java,
    )
    val user = entryPoint.firebaseAuthManager().currentUser() ?: return Result.success()
    val prefs = entryPoint.preferencesDataStore()
    if (!prefs.isDriveAutoBackupEnabled(user.uid)) {
      return Result.success()
    }

    val wifiOnly = prefs.isDriveWifiOnly(user.uid)
    val driveMgr = entryPoint.googleDriveBackupManager()
    if (wifiOnly && !driveMgr.isUnmeteredNetwork()) {
      return Result.retry()
    }

    val passphrase = entryPoint.autoBackupPassphraseStore().loadPassphrase(user.uid)
    if (passphrase == null) {
      prefs.recordDriveBackupStateForUid(
        user.uid,
        System.currentTimeMillis(),
        "Auto-backup paused: re-save passphrase in Profile",
      )
      return Result.success()
    }

    return try {
      when (val auth = entryPoint.driveAuthorizationManager().authorizeDriveFileAccess()) {
        is DriveAuthorizationOutcome.Authorized -> {
          val res = driveMgr.uploadEncryptedBackupToDrive(
            oauthAccessToken = auth.accessToken,
            recoveryPassphrase = passphrase,
            uid = user.uid,
            expectedAccountEmail = user.email,
          )
          if (res.isSuccess) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        is DriveAuthorizationOutcome.RequiresUserConsent -> {
          prefs.recordDriveBackupStateForUid(
            user.uid,
            System.currentTimeMillis(),
            "Drive permission required — open Profile to authorize",
          )
          Result.success()
        }
        is DriveAuthorizationOutcome.Unavailable -> {
          if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
      }
    } finally {
      passphrase.fill('\u0000')
    }
  }
}
