package com.skyplus.finance.data.security

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.skyplus.finance.data.cloud.DrivePeriodicBackupWorker
import com.skyplus.finance.data.cloud.FirestoreOutboxSyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class BackupPreparationWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result = Result.success()
}

@Singleton
class BackupWorkScheduler @Inject constructor() {
  companion object {
    const val BACKUP_WORK_NAME = "skyplus_phase3_backup_foundation"
    const val FIRESTORE_PERIODIC_SYNC_WORK = "skyplus_firestore_periodic_sync"
    const val FIRESTORE_IMMEDIATE_SYNC_WORK = "skyplus_firestore_immediate_sync"
    const val DRIVE_PERIODIC_BACKUP_WORK = "skyplus_drive_periodic_backup"
  }

  fun cancelScheduledBackups(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(BACKUP_WORK_NAME)
  }

  fun scheduleLocalBackupPreparation(context: Context, wifiOnly: Boolean = true) {
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
      .setRequiresBatteryNotLow(true)
      .build()

    val request = PeriodicWorkRequestBuilder<BackupPreparationWorker>(24, TimeUnit.HOURS)
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
      .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      BACKUP_WORK_NAME,
      ExistingPeriodicWorkPolicy.KEEP,
      request,
    )
  }

  fun scheduleFirestoreSync(context: Context, triggerImmediate: Boolean = true) {
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()

    val periodicRequest = PeriodicWorkRequestBuilder<FirestoreOutboxSyncWorker>(6, TimeUnit.HOURS)
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      FIRESTORE_PERIODIC_SYNC_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      periodicRequest,
    )

    if (triggerImmediate) {
      val immediateRequest = OneTimeWorkRequestBuilder<FirestoreOutboxSyncWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(context).enqueueUniqueWork(
        FIRESTORE_IMMEDIATE_SYNC_WORK,
        ExistingWorkPolicy.REPLACE,
        immediateRequest,
      )
    }
  }

  fun cancelFirestoreSync(context: Context) {
    val wm = WorkManager.getInstance(context)
    wm.cancelUniqueWork(FIRESTORE_PERIODIC_SYNC_WORK)
    wm.cancelUniqueWork(FIRESTORE_IMMEDIATE_SYNC_WORK)
  }

  fun schedulePeriodicDriveBackup(context: Context, wifiOnly: Boolean) {
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
      .setRequiresBatteryNotLow(true)
      .build()

    val request = PeriodicWorkRequestBuilder<DrivePeriodicBackupWorker>(24, TimeUnit.HOURS)
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
      .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      DRIVE_PERIODIC_BACKUP_WORK,
      ExistingPeriodicWorkPolicy.UPDATE,
      request,
    )
  }

  fun cancelPeriodicDriveBackup(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(DRIVE_PERIODIC_BACKUP_WORK)
  }
}
