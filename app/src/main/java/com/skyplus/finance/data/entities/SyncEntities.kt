package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object SyncEntityTypes {
  const val ACCOUNT = "ACCOUNT"
  const val TRANSACTION = "TRANSACTION"
  const val BUDGET = "BUDGET"
  const val LOAN = "LOAN"
  const val EMI_PAYMENT = "EMI_PAYMENT"
  const val SAVINGS_GOAL = "SAVINGS_GOAL"
  const val INVESTMENT = "INVESTMENT"
  const val SHOPPING_LIST = "SHOPPING_LIST"
  const val SHOPPING_ITEM = "SHOPPING_ITEM"
  const val SHOPPING_SHOP_PRICE = "SHOPPING_SHOP_PRICE"
  const val CATEGORY = "CATEGORY"
  const val CURRENCY = "CURRENCY"
  const val APP_SETTINGS = "APP_SETTINGS"

  fun firestoreCollectionFor(entityType: String): String = when (entityType) {
    ACCOUNT -> "accounts"
    TRANSACTION -> "transactions"
    BUDGET -> "budgets"
    LOAN -> "loans"
    EMI_PAYMENT -> "emi_payments"
    SAVINGS_GOAL -> "savings_goals"
    INVESTMENT -> "investments"
    SHOPPING_LIST -> "shopping_lists"
    SHOPPING_ITEM -> "shopping_items"
    SHOPPING_SHOP_PRICE -> "shopping_shop_prices"
    CATEGORY -> "categories"
    CURRENCY -> "currencies"
    APP_SETTINGS -> "app_settings"
    else -> error("Unsupported sync entity type: $entityType")
  }

  val orderedCollections: List<Pair<String, String>> = listOf(
    CATEGORY to "categories",
    CURRENCY to "currencies",
    APP_SETTINGS to "app_settings",
    ACCOUNT to "accounts",
    LOAN to "loans",
    EMI_PAYMENT to "emi_payments",
    SAVINGS_GOAL to "savings_goals",
    BUDGET to "budgets",
    INVESTMENT to "investments",
    TRANSACTION to "transactions",
    SHOPPING_LIST to "shopping_lists",
    SHOPPING_ITEM to "shopping_items",
    SHOPPING_SHOP_PRICE to "shopping_shop_prices",
  )
}

enum class SyncOperationType {
  UPSERT,
  DELETE,
}

enum class SyncConflictStatus {
  PENDING,
  RESOLVED_KEEP_LOCAL,
  RESOLVED_USE_CLOUD,
  RESOLVED_KEEP_BOTH,
}

@Entity(
  tableName = "sync_entity_map",
  indices = [
    Index(value = ["ownerUid", "entityType", "localId"], unique = true),
    Index(value = ["ownerUid", "entityType", "recordId"], unique = true),
  ],
)
data class SyncEntityMapEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val ownerUid: String,
  val entityType: String,
  val localId: String,
  val recordId: String,
  val syncVersion: Long,
  val lastSyncedVersion: Long,
  val lastSyncedAtEpochMillis: Long,
  val isDirty: Boolean,
  val isDeleted: Boolean,
  val updatedByDeviceId: String,
  val updatedAtEpochMillis: Long,
)

@Entity(
  tableName = "sync_outbox",
  indices = [
    Index(value = ["idempotencyKey"], unique = true),
    Index(value = ["ownerUid", "nextRetryAtEpochMillis"]),
  ],
)
data class SyncOutboxEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val idempotencyKey: String,
  val ownerUid: String,
  val entityType: String,
  val localId: String,
  val recordId: String,
  val operation: String,
  val payloadJson: String,
  val baseSyncVersion: Long,
  val attemptCount: Int,
  val nextRetryAtEpochMillis: Long,
  val lastError: String? = null,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Entity(
  tableName = "sync_conflicts",
  indices = [
    Index(value = ["ownerUid", "status"]),
    Index(value = ["ownerUid", "entityType", "recordId"], unique = true),
  ],
)
data class SyncConflictEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val ownerUid: String,
  val entityType: String,
  val localId: String,
  val recordId: String,
  val localPayloadJson: String,
  val remotePayloadJson: String,
  val localSummary: String,
  val remoteSummary: String,
  val localUpdatedAtEpochMillis: Long,
  val remoteUpdatedAtEpochMillis: Long,
  val remoteSyncVersion: Long,
  val remoteDeviceId: String,
  val status: String,
  val createdAtEpochMillis: Long,
  val resolvedAtEpochMillis: Long? = null,
)
