package com.skyplus.finance.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit Room migration registry preserving user financial data across schema upgrades.
 * Destructive fallback is strictly forbidden.
 */
object DatabaseMigrations {
  val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `shopping_lists` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `budgetAmount` TEXT NOT NULL,
          `currencyCode` TEXT NOT NULL,
          `notes` TEXT NOT NULL,
          `createdAtEpochMillis` INTEGER NOT NULL,
          `updatedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `shopping_items` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `listId` INTEGER NOT NULL,
          `name` TEXT NOT NULL,
          `categoryName` TEXT NOT NULL,
          `quantity` TEXT NOT NULL,
          `unit` TEXT NOT NULL,
          `estimatedUnitPrice` TEXT NOT NULL,
          `actualUnitPrice` TEXT,
          `isPurchased` INTEGER NOT NULL,
          `notes` TEXT NOT NULL,
          `createdAtEpochMillis` INTEGER NOT NULL,
          `updatedAtEpochMillis` INTEGER NOT NULL,
          FOREIGN KEY(`listId`) REFERENCES `shopping_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_shopping_items_listId` ON `shopping_items` (`listId`)",
      )
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `shopping_shop_prices` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `itemNameNormalized` TEXT NOT NULL,
          `productDisplayName` TEXT NOT NULL,
          `shopName` TEXT NOT NULL,
          `shopLocation` TEXT NOT NULL,
          `unit` TEXT NOT NULL,
          `price` TEXT NOT NULL,
          `currencyCode` TEXT NOT NULL,
          `priceSource` TEXT NOT NULL,
          `isLiveFeedAvailable` INTEGER NOT NULL,
          `updatedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_shopping_shop_prices_itemNameNormalized` ON `shopping_shop_prices` (`itemNameNormalized`)",
      )
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `schema_audit_log` (
          `version` INTEGER NOT NULL PRIMARY KEY,
          `migratedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      db.execSQL(
        "INSERT OR REPLACE INTO `schema_audit_log` (`version`, `migratedAtEpochMillis`) VALUES (2, ${System.currentTimeMillis()})",
      )
    }
  }

  val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `investments` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `investmentName` TEXT NOT NULL,
          `investmentType` TEXT NOT NULL,
          `amountInvested` TEXT NOT NULL,
          `investmentDateEpochDay` INTEGER NOT NULL,
          `currentValue` TEXT,
          `notes` TEXT NOT NULL,
          `currencyCode` TEXT NOT NULL,
          `createdAtEpochMillis` INTEGER NOT NULL,
          `updatedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      try { db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `dailySpendingSummaryEnabled` INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
      try { db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `spendingSummaryHour` INTEGER NOT NULL DEFAULT 20") } catch (_: Exception) {}
      try { db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `spendingSummaryMinute` INTEGER NOT NULL DEFAULT 30") } catch (_: Exception) {}
      try { db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `budgetAlertsEnabled` INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}
      try { db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `billEmiRemindersEnabled` INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}
      try { db.execSQL("ALTER TABLE `app_settings` ADD COLUMN `shoppingBudgetRemindersEnabled` INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}

      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `schema_audit_log` (
          `version` INTEGER NOT NULL PRIMARY KEY,
          `migratedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      try {
        db.execSQL(
          "INSERT OR REPLACE INTO `schema_audit_log` (`version`, `migratedAtEpochMillis`) VALUES (3, ${System.currentTimeMillis()})",
        )
      } catch (_: Exception) {}
    }
  }

  val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sync_entity_map` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `ownerUid` TEXT NOT NULL,
          `entityType` TEXT NOT NULL,
          `localId` TEXT NOT NULL,
          `recordId` TEXT NOT NULL,
          `syncVersion` INTEGER NOT NULL,
          `lastSyncedVersion` INTEGER NOT NULL,
          `lastSyncedAtEpochMillis` INTEGER NOT NULL,
          `isDirty` INTEGER NOT NULL,
          `isDeleted` INTEGER NOT NULL,
          `updatedByDeviceId` TEXT NOT NULL,
          `updatedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_entity_map_ownerUid_entityType_localId` ON `sync_entity_map` (`ownerUid`, `entityType`, `localId`)",
      )
      db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_entity_map_ownerUid_entityType_recordId` ON `sync_entity_map` (`ownerUid`, `entityType`, `recordId`)",
      )

      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sync_outbox` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `idempotencyKey` TEXT NOT NULL,
          `ownerUid` TEXT NOT NULL,
          `entityType` TEXT NOT NULL,
          `localId` TEXT NOT NULL,
          `recordId` TEXT NOT NULL,
          `operation` TEXT NOT NULL,
          `payloadJson` TEXT NOT NULL,
          `baseSyncVersion` INTEGER NOT NULL,
          `attemptCount` INTEGER NOT NULL,
          `nextRetryAtEpochMillis` INTEGER NOT NULL,
          `lastError` TEXT,
          `createdAtEpochMillis` INTEGER NOT NULL,
          `updatedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_idempotencyKey` ON `sync_outbox` (`idempotencyKey`)",
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_ownerUid_nextRetryAtEpochMillis` ON `sync_outbox` (`ownerUid`, `nextRetryAtEpochMillis`)",
      )

      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sync_conflicts` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `ownerUid` TEXT NOT NULL,
          `entityType` TEXT NOT NULL,
          `localId` TEXT NOT NULL,
          `recordId` TEXT NOT NULL,
          `localPayloadJson` TEXT NOT NULL,
          `remotePayloadJson` TEXT NOT NULL,
          `localSummary` TEXT NOT NULL,
          `remoteSummary` TEXT NOT NULL,
          `localUpdatedAtEpochMillis` INTEGER NOT NULL,
          `remoteUpdatedAtEpochMillis` INTEGER NOT NULL,
          `remoteSyncVersion` INTEGER NOT NULL,
          `remoteDeviceId` TEXT NOT NULL,
          `status` TEXT NOT NULL,
          `createdAtEpochMillis` INTEGER NOT NULL,
          `resolvedAtEpochMillis` INTEGER
        )
        """.trimIndent(),
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_sync_conflicts_ownerUid_status` ON `sync_conflicts` (`ownerUid`, `status`)",
      )
      db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_conflicts_ownerUid_entityType_recordId` ON `sync_conflicts` (`ownerUid`, `entityType`, `recordId`)",
      )

      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `schema_audit_log` (
          `version` INTEGER NOT NULL PRIMARY KEY,
          `migratedAtEpochMillis` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      try {
        db.execSQL(
          "INSERT OR REPLACE INTO `schema_audit_log` (`version`, `migratedAtEpochMillis`) VALUES (4, ${System.currentTimeMillis()})",
        )
      } catch (_: Exception) {}
    }
  }

  fun allMigrations(): Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
  )
}
