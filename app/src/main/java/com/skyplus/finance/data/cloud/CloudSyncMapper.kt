package com.skyplus.finance.data.cloud

import com.skyplus.finance.data.cloud.dto.CloudAccountPayload
import com.skyplus.finance.data.cloud.dto.CloudAppSettingsPayload
import com.skyplus.finance.data.cloud.dto.CloudBudgetPayload
import com.skyplus.finance.data.cloud.dto.CloudCategoryPayload
import com.skyplus.finance.data.cloud.dto.CloudCurrencyPayload
import com.skyplus.finance.data.cloud.dto.CloudEmiPaymentPayload
import com.skyplus.finance.data.cloud.dto.CloudInvestmentPayload
import com.skyplus.finance.data.cloud.dto.CloudLoanPayload
import com.skyplus.finance.data.cloud.dto.CloudSavingsGoalPayload
import com.skyplus.finance.data.cloud.dto.CloudShoppingItemPayload
import com.skyplus.finance.data.cloud.dto.CloudShoppingListPayload
import com.skyplus.finance.data.cloud.dto.CloudShoppingShopPricePayload
import com.skyplus.finance.data.cloud.dto.CloudTransactionPayload
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.AccountEntity
import com.skyplus.finance.data.entities.AppSettingsEntity
import com.skyplus.finance.data.entities.BudgetEntity
import com.skyplus.finance.data.entities.CategoryEntity
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.InvestmentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.SavingsGoalEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import com.skyplus.finance.data.entities.SyncEntityMapEntity
import com.skyplus.finance.data.entities.SyncEntityTypes
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.util.DecimalUtils
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class CloudSyncMapper @Inject constructor() {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun deterministicRecordId(entityType: String, localId: String): String = when (entityType) {
    SyncEntityTypes.APP_SETTINGS -> "settings_primary"
    SyncEntityTypes.CURRENCY -> "cur_${localId.uppercase()}"
    else -> UUID.randomUUID().toString()
  }

  suspend fun serializeEntity(
    entityType: String,
    localId: String,
    database: SkyPlusDatabase,
    ownerUid: String,
    deviceId: String,
  ): String? {
    val syncDao = database.syncDao()
    return when (entityType) {
      SyncEntityTypes.ACCOUNT -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.accountDao().getAllAccountsOnce().firstOrNull { it.id == id } ?: return null
        json.encodeToString(
          CloudAccountPayload.serializer(),
          CloudAccountPayload(
            localId = entity.id,
            accountName = entity.accountName,
            accountType = entity.accountType,
            primaryCurrencyCode = entity.primaryCurrencyCode,
            openingBalance = DecimalUtils.toCanonicalString(entity.openingBalance),
            isGuestLocal = entity.isGuestLocal,
            createdAtEpochMillis = entity.createdAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.CATEGORY -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.categoryDao().getCategoryById(id) ?: return null
        json.encodeToString(
          CloudCategoryPayload.serializer(),
          CloudCategoryPayload(
            localId = entity.id,
            nameKey = entity.nameKey,
            displayNameEn = entity.displayNameEn,
            displayNameTa = entity.displayNameTa,
            type = entity.type.name,
            iconKey = entity.iconKey,
            colorHex = entity.colorHex,
            isSystemDefault = entity.isSystemDefault,
            sortOrder = entity.sortOrder,
          ),
        )
      }

      SyncEntityTypes.CURRENCY -> {
        val entity = database.currencyDao().getCurrencyByCode(localId.uppercase()) ?: return null
        json.encodeToString(
          CloudCurrencyPayload.serializer(),
          CloudCurrencyPayload(
            code = entity.code,
            numericCode = entity.numericCode,
            displayName = entity.displayName,
            symbol = entity.symbol,
            decimalPrecision = entity.decimalPrecision,
            isActiveInWallet = entity.isActiveInWallet,
            cachedRateToBase = entity.cachedRateToBase?.let { DecimalUtils.toCanonicalString(it) },
            rateSource = entity.rateSource,
            rateUpdatedAtEpochMillis = entity.rateUpdatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.APP_SETTINGS -> {
        val entity = database.appSettingsDao().getSettingsOnce() ?: return null
        json.encodeToString(
          CloudAppSettingsPayload.serializer(),
          CloudAppSettingsPayload(
            primaryCurrencyCode = entity.primaryCurrencyCode,
            languageCode = "en",
            themeMode = entity.themeMode,
            reservedAmount = DecimalUtils.toCanonicalString(entity.reservedAmount),
            monthlySavingsAllocation = DecimalUtils.toCanonicalString(entity.monthlySavingsAllocation),
            confirmedRemainingIncome = DecimalUtils.toCanonicalString(entity.confirmedRemainingIncome),
            monthlyEssentialExpenses = DecimalUtils.toCanonicalString(entity.monthlyEssentialExpenses),
            dailySpendingSummaryEnabled = entity.dailySpendingSummaryEnabled,
            spendingSummaryHour = entity.spendingSummaryHour,
            spendingSummaryMinute = entity.spendingSummaryMinute,
            budgetAlertsEnabled = entity.budgetAlertsEnabled,
            billEmiRemindersEnabled = entity.billEmiRemindersEnabled,
            shoppingBudgetRemindersEnabled = entity.shoppingBudgetRemindersEnabled,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.TRANSACTION -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.transactionDao().getTransactionById(id) ?: return null
        val accountRecordId = ensureRecordIdForParent(
          ownerUid = ownerUid,
          entityType = SyncEntityTypes.ACCOUNT,
          localId = entity.accountId.toString(),
          database = database,
          deviceId = deviceId,
        )
        val cat = database.categoryDao().getCategoryById(entity.categoryId)
        val goalRecordId = entity.linkedGoalId?.let {
          ensureRecordIdForParent(ownerUid, SyncEntityTypes.SAVINGS_GOAL, it.toString(), database, deviceId)
        }
        val loanRecordId = entity.linkedLoanId?.let {
          ensureRecordIdForParent(ownerUid, SyncEntityTypes.LOAN, it.toString(), database, deviceId)
        }
        json.encodeToString(
          CloudTransactionPayload.serializer(),
          CloudTransactionPayload(
            localId = entity.id,
            accountRecordId = accountRecordId,
            categoryNameKey = cat?.nameKey ?: "",
            categoryId = entity.categoryId,
            type = entity.type.name,
            amount = DecimalUtils.toCanonicalString(entity.amount),
            currencyCode = entity.currencyCode,
            convertedPrimaryAmount = entity.convertedPrimaryAmount?.let { DecimalUtils.toCanonicalString(it) },
            primaryCurrencyAtEntry = entity.primaryCurrencyAtEntry,
            exchangeRateSource = entity.exchangeRateSource,
            description = entity.description,
            transactionDateEpochDay = entity.transactionDateEpochDay,
            isRecurring = entity.isRecurring,
            recurrenceIntervalDays = entity.recurrenceIntervalDays,
            isUpcomingBill = entity.isUpcomingBill,
            isPaid = entity.isPaid,
            linkedGoalRecordId = goalRecordId,
            linkedLoanRecordId = loanRecordId,
            deduplicationKey = entity.deduplicationKey,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.BUDGET -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.budgetDao().getAllBudgetsOnce().firstOrNull { it.id == id } ?: return null
        json.encodeToString(
          CloudBudgetPayload.serializer(),
          CloudBudgetPayload(
            localId = entity.id,
            categoryId = entity.categoryId,
            yearMonth = entity.yearMonth,
            limitAmount = DecimalUtils.toCanonicalString(entity.limitAmount),
            currencyCode = entity.currencyCode,
            rolloverEnabled = entity.rolloverEnabled,
            rolloverAmount = DecimalUtils.toCanonicalString(entity.rolloverAmount),
            warningThresholdPercent = entity.warningThresholdPercent,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.LOAN -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.loanDao().getLoanById(id) ?: return null
        json.encodeToString(
          CloudLoanPayload.serializer(),
          CloudLoanPayload(
            localId = entity.id,
            loanName = entity.loanName,
            lender = entity.lender,
            originalPrincipal = DecimalUtils.toCanonicalString(entity.originalPrincipal),
            outstandingPrincipal = DecimalUtils.toCanonicalString(entity.outstandingPrincipal),
            annualInterestRatePercent = DecimalUtils.toCanonicalString(entity.annualInterestRatePercent),
            emiAmount = DecimalUtils.toCanonicalString(entity.emiAmount),
            currencyCode = entity.currencyCode,
            paymentDueDayOfMonth = entity.paymentDueDayOfMonth,
            nextDueDateEpochDay = entity.nextDueDateEpochDay,
            remainingTenureMonths = entity.remainingTenureMonths,
            startDateEpochDay = entity.startDateEpochDay,
            status = entity.status.name,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.EMI_PAYMENT -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.loanDao().getAllEmiPaymentsOnce().firstOrNull { it.id == id } ?: return null
        val loanRecordId = ensureRecordIdForParent(
          ownerUid = ownerUid,
          entityType = SyncEntityTypes.LOAN,
          localId = entity.loanId.toString(),
          database = database,
          deviceId = deviceId,
        )
        json.encodeToString(
          CloudEmiPaymentPayload.serializer(),
          CloudEmiPaymentPayload(
            localId = entity.id,
            loanLocalId = entity.loanId,
            loanRecordId = loanRecordId,
            paymentDateEpochDay = entity.paymentDateEpochDay,
            totalPaidAmount = DecimalUtils.toCanonicalString(entity.totalPaidAmount),
            principalComponent = DecimalUtils.toCanonicalString(entity.principalComponent),
            interestComponent = DecimalUtils.toCanonicalString(entity.interestComponent),
            extraPrepaymentAmount = DecimalUtils.toCanonicalString(entity.extraPrepaymentAmount),
            remainingBalanceAfter = DecimalUtils.toCanonicalString(entity.remainingBalanceAfter),
            currencyCode = entity.currencyCode,
            note = entity.note,
            createdAtEpochMillis = entity.createdAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.SAVINGS_GOAL -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.savingsGoalDao().getGoalById(id) ?: return null
        json.encodeToString(
          CloudSavingsGoalPayload.serializer(),
          CloudSavingsGoalPayload(
            localId = entity.id,
            goalName = entity.goalName,
            goalCategory = entity.goalCategory.name,
            targetAmount = DecimalUtils.toCanonicalString(entity.targetAmount),
            currentSavedAmount = DecimalUtils.toCanonicalString(entity.currentSavedAmount),
            currencyCode = entity.currencyCode,
            targetDateEpochDay = entity.targetDateEpochDay,
            isEmergencyFund = entity.isEmergencyFund,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.INVESTMENT -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.investmentDao().getAllInvestmentsOnce().firstOrNull { it.id == id } ?: return null
        json.encodeToString(
          CloudInvestmentPayload.serializer(),
          CloudInvestmentPayload(
            localId = entity.id,
            investmentName = entity.investmentName,
            investmentType = entity.investmentType,
            amountInvested = DecimalUtils.toCanonicalString(entity.amountInvested),
            investmentDateEpochDay = entity.investmentDateEpochDay,
            currentValue = entity.currentValue?.let { DecimalUtils.toCanonicalString(it) },
            notes = entity.notes,
            currencyCode = entity.currencyCode,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.SHOPPING_LIST -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.shoppingDao().getAllShoppingListsOnce().firstOrNull { it.id == id } ?: return null
        json.encodeToString(
          CloudShoppingListPayload.serializer(),
          CloudShoppingListPayload(
            localId = entity.id,
            name = entity.name,
            budgetAmount = DecimalUtils.toCanonicalString(entity.budgetAmount),
            currencyCode = entity.currencyCode,
            notes = entity.notes,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.SHOPPING_ITEM -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.shoppingDao().getAllShoppingItemsOnce().firstOrNull { it.id == id } ?: return null
        val listRecordId = ensureRecordIdForParent(
          ownerUid = ownerUid,
          entityType = SyncEntityTypes.SHOPPING_LIST,
          localId = entity.listId.toString(),
          database = database,
          deviceId = deviceId,
        )
        json.encodeToString(
          CloudShoppingItemPayload.serializer(),
          CloudShoppingItemPayload(
            localId = entity.id,
            listId = entity.listId,
            listRecordId = listRecordId,
            name = entity.name,
            categoryName = entity.categoryName,
            quantity = DecimalUtils.toCanonicalString(entity.quantity),
            unit = entity.unit,
            estimatedUnitPrice = DecimalUtils.toCanonicalString(entity.estimatedUnitPrice),
            actualUnitPrice = entity.actualUnitPrice?.let { DecimalUtils.toCanonicalString(it) },
            isPurchased = entity.isPurchased,
            notes = entity.notes,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      SyncEntityTypes.SHOPPING_SHOP_PRICE -> {
        val id = localId.toLongOrNull() ?: return null
        val entity = database.shoppingDao().getAllShopPricesOnce().firstOrNull { it.id == id } ?: return null
        json.encodeToString(
          CloudShoppingShopPricePayload.serializer(),
          CloudShoppingShopPricePayload(
            localId = entity.id,
            itemNameNormalized = entity.itemNameNormalized,
            productDisplayName = entity.productDisplayName,
            shopName = entity.shopName,
            shopLocation = entity.shopLocation,
            unit = entity.unit,
            price = DecimalUtils.toCanonicalString(entity.price),
            currencyCode = entity.currencyCode,
            priceSource = entity.priceSource,
            isLiveFeedAvailable = entity.isLiveFeedAvailable,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
          ),
        )
      }

      else -> {
        syncDao.findMapByLocalId(ownerUid, entityType, localId)?.recordId
        null
      }
    }
  }

  private suspend fun ensureRecordIdForParent(
    ownerUid: String,
    entityType: String,
    localId: String,
    database: SkyPlusDatabase,
    deviceId: String,
  ): String {
    val existing = database.syncDao().findMapByLocalId(ownerUid, entityType, localId)
    if (existing != null) return existing.recordId
    val newRecordId = deterministicRecordId(entityType, localId)
    database.syncDao().upsertMap(
      SyncEntityMapEntity(
        ownerUid = ownerUid,
        entityType = entityType,
        localId = localId,
        recordId = newRecordId,
        syncVersion = 1L,
        lastSyncedVersion = 0L,
        lastSyncedAtEpochMillis = 0L,
        isDirty = true,
        isDeleted = false,
        updatedByDeviceId = deviceId,
        updatedAtEpochMillis = System.currentTimeMillis(),
      ),
    )
    return newRecordId
  }

  fun buildSummary(entityType: String, payloadJson: String): String {
    if (payloadJson.isBlank() || payloadJson == "{}") return "$entityType (Deleted)"
    return runCatching {
      when (entityType) {
        SyncEntityTypes.TRANSACTION -> {
          val p = json.decodeFromString(CloudTransactionPayload.serializer(), payloadJson)
          val date = LocalDate.ofEpochDay(p.transactionDateEpochDay)
          val label = p.description.ifBlank { p.categoryNameKey.ifBlank { p.type } }
          "${p.type} ${p.currencyCode} ${p.amount} • $label ($date)"
        }
        SyncEntityTypes.BUDGET -> {
          val p = json.decodeFromString(CloudBudgetPayload.serializer(), payloadJson)
          "Budget ${p.yearMonth} • ${p.currencyCode} ${p.limitAmount}"
        }
        SyncEntityTypes.LOAN -> {
          val p = json.decodeFromString(CloudLoanPayload.serializer(), payloadJson)
          "Loan '${p.loanName}' • Bal ${p.currencyCode} ${p.outstandingPrincipal}"
        }
        SyncEntityTypes.EMI_PAYMENT -> {
          val p = json.decodeFromString(CloudEmiPaymentPayload.serializer(), payloadJson)
          "EMI Payment ${p.currencyCode} ${p.totalPaidAmount}"
        }
        SyncEntityTypes.SAVINGS_GOAL -> {
          val p = json.decodeFromString(CloudSavingsGoalPayload.serializer(), payloadJson)
          "Goal '${p.goalName}' • Saved ${p.currencyCode} ${p.currentSavedAmount}/${p.targetAmount}"
        }
        SyncEntityTypes.INVESTMENT -> {
          val p = json.decodeFromString(CloudInvestmentPayload.serializer(), payloadJson)
          "Investment '${p.investmentName}' • ${p.currencyCode} ${p.amountInvested}"
        }
        SyncEntityTypes.SHOPPING_LIST -> {
          val p = json.decodeFromString(CloudShoppingListPayload.serializer(), payloadJson)
          "Shopping List '${p.name}' • ${p.currencyCode} ${p.budgetAmount}"
        }
        SyncEntityTypes.SHOPPING_ITEM -> {
          val p = json.decodeFromString(CloudShoppingItemPayload.serializer(), payloadJson)
          "Item '${p.name}' (${p.quantity} ${p.unit})"
        }
        SyncEntityTypes.SHOPPING_SHOP_PRICE -> {
          val p = json.decodeFromString(CloudShoppingShopPricePayload.serializer(), payloadJson)
          "Shop Price '${p.productDisplayName}' @ ${p.shopName}: ${p.currencyCode} ${p.price}"
        }
        SyncEntityTypes.ACCOUNT -> {
          val p = json.decodeFromString(CloudAccountPayload.serializer(), payloadJson)
          "Account '${p.accountName}' (${p.primaryCurrencyCode})"
        }
        SyncEntityTypes.APP_SETTINGS -> {
          val p = json.decodeFromString(CloudAppSettingsPayload.serializer(), payloadJson)
          "Settings (${p.primaryCurrencyCode}, ${p.themeMode})"
        }
        SyncEntityTypes.CURRENCY -> {
          val p = json.decodeFromString(CloudCurrencyPayload.serializer(), payloadJson)
          "Currency ${p.code} (wallet=${p.isActiveInWallet})"
        }
        SyncEntityTypes.CATEGORY -> {
          val p = json.decodeFromString(CloudCategoryPayload.serializer(), payloadJson)
          "Category '${p.displayNameEn}' (${p.type})"
        }
        else -> "$entityType record"
      }
    }.getOrElse { "$entityType record" }
  }

  /**
   * Applies a decrypted remote payload into Room directly via DAOs (bypassing Outbox hooks).
   * Returns the resolved localId in Room.
   */
  suspend fun applyRemotePayload(
    ownerUid: String,
    entityType: String,
    recordId: String,
    payloadJson: String,
    isDeleted: Boolean,
    database: SkyPlusDatabase,
    syncVersion: Long,
    updatedByDeviceId: String,
    updatedAtEpochMillis: Long,
    asCopyKeepBoth: Boolean = false,
  ): String {
    val syncDao = database.syncDao()
    val existingMap = if (asCopyKeepBoth) null else syncDao.findMapByRecordId(ownerUid, entityType, recordId)
    val existingLocalId = existingMap?.localId

    if (isDeleted) {
      if (existingLocalId != null) {
        deleteLocalEntity(entityType, existingLocalId, database)
      }
      if (existingMap != null) {
        syncDao.upsertMap(
          existingMap.copy(
            syncVersion = syncVersion,
            lastSyncedVersion = syncVersion,
            lastSyncedAtEpochMillis = System.currentTimeMillis(),
            isDirty = false,
            isDeleted = true,
            updatedByDeviceId = updatedByDeviceId,
            updatedAtEpochMillis = updatedAtEpochMillis,
          ),
        )
      }
      return existingLocalId.orEmpty()
    }

    val resolvedLocalId: String = when (entityType) {
      SyncEntityTypes.CATEGORY -> {
        val p = json.decodeFromString(CloudCategoryPayload.serializer(), payloadJson)
        val type = CategoryType.valueOf(p.type)
        val match = existingLocalId?.toLongOrNull()?.let { database.categoryDao().getCategoryById(it) }
          ?: database.categoryDao().getAllCategoriesOnce().firstOrNull { it.nameKey == p.nameKey && it.type == type }
        val newId = if (match != null && !asCopyKeepBoth) {
          database.categoryDao().upsert(
            match.copy(
              displayNameEn = p.displayNameEn,
              displayNameTa = p.displayNameTa,
              iconKey = p.iconKey,
              colorHex = p.colorHex,
              sortOrder = p.sortOrder,
            ),
          )
          match.id
        } else {
          database.categoryDao().upsert(
            CategoryEntity(
              id = 0L,
              nameKey = if (asCopyKeepBoth) "${p.nameKey}_${UUID.randomUUID().toString().take(4)}" else p.nameKey,
              displayNameEn = p.displayNameEn,
              displayNameTa = p.displayNameTa,
              type = type,
              iconKey = p.iconKey,
              colorHex = p.colorHex,
              isSystemDefault = p.isSystemDefault,
              sortOrder = p.sortOrder,
            ),
          )
        }
        newId.toString()
      }

      SyncEntityTypes.CURRENCY -> {
        val p = json.decodeFromString(CloudCurrencyPayload.serializer(), payloadJson)
        val existing = database.currencyDao().getCurrencyByCode(p.code.uppercase())
        if (existing != null) {
          database.currencyDao().upsert(
            existing.copy(
              isActiveInWallet = p.isActiveInWallet,
              cachedRateToBase = p.cachedRateToBase?.let { DecimalUtils.fromStringOrZero(it) },
              rateSource = p.rateSource,
              rateUpdatedAtEpochMillis = p.rateUpdatedAtEpochMillis,
            ),
          )
        }
        p.code.uppercase()
      }

      SyncEntityTypes.APP_SETTINGS -> {
        val p = json.decodeFromString(CloudAppSettingsPayload.serializer(), payloadJson)
        val existing = database.appSettingsDao().getSettingsOnce()
        val entity = AppSettingsEntity(
          id = 1,
          primaryCurrencyCode = p.primaryCurrencyCode,
          languageCode = "en",
          themeMode = p.themeMode,
          reservedAmount = DecimalUtils.fromStringOrZero(p.reservedAmount),
          monthlySavingsAllocation = DecimalUtils.fromStringOrZero(p.monthlySavingsAllocation),
          confirmedRemainingIncome = DecimalUtils.fromStringOrZero(p.confirmedRemainingIncome),
          monthlyEssentialExpenses = DecimalUtils.fromStringOrZero(p.monthlyEssentialExpenses),
          // Never overwrite device-local biometric preferences from cloud
          appLockEnabled = existing?.appLockEnabled ?: false,
          lockOnLaunch = existing?.lockOnLaunch ?: true,
          lockOnBackgroundReturn = existing?.lockOnBackgroundReturn ?: true,
          biometricPreferred = existing?.biometricPreferred ?: true,
          guestProfileName = existing?.guestProfileName ?: "Guest Mode",
          dailySpendingSummaryEnabled = p.dailySpendingSummaryEnabled,
          spendingSummaryHour = p.spendingSummaryHour,
          spendingSummaryMinute = p.spendingSummaryMinute,
          budgetAlertsEnabled = p.budgetAlertsEnabled,
          billEmiRemindersEnabled = p.billEmiRemindersEnabled,
          shoppingBudgetRemindersEnabled = p.shoppingBudgetRemindersEnabled,
          updatedAtEpochMillis = p.updatedAtEpochMillis,
        )
        database.appSettingsDao().upsertSettings(entity)
        "1"
      }

      SyncEntityTypes.ACCOUNT -> {
        val p = json.decodeFromString(CloudAccountPayload.serializer(), payloadJson)
        val existingId = existingLocalId?.toLongOrNull()
        val existing = if (existingId != null) {
          database.accountDao().getAllAccountsOnce().firstOrNull { it.id == existingId }
        } else {
          database.accountDao().getPrimaryAccountOnce()
        }
        val finalId = if (existing != null && !asCopyKeepBoth) {
          database.accountDao().updateAccount(
            existing.copy(
              accountName = p.accountName,
              accountType = p.accountType,
              primaryCurrencyCode = p.primaryCurrencyCode,
              openingBalance = DecimalUtils.fromStringOrZero(p.openingBalance),
              isGuestLocal = false,
            ),
          )
          existing.id
        } else {
          database.accountDao().insertAccount(
            AccountEntity(
              id = 0L,
              accountName = p.accountName,
              accountType = p.accountType,
              primaryCurrencyCode = p.primaryCurrencyCode,
              openingBalance = DecimalUtils.fromStringOrZero(p.openingBalance),
              isGuestLocal = false,
              createdAtEpochMillis = p.createdAtEpochMillis,
            ),
          )
        }
        finalId.toString()
      }

      SyncEntityTypes.LOAN -> {
        val p = json.decodeFromString(CloudLoanPayload.serializer(), payloadJson)
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: 0L)
        val entity = LoanEntity(
          id = targetId,
          loanName = if (asCopyKeepBoth) "${p.loanName} (Cloud Copy)" else p.loanName,
          lender = p.lender,
          originalPrincipal = DecimalUtils.fromStringOrZero(p.originalPrincipal),
          outstandingPrincipal = DecimalUtils.fromStringOrZero(p.outstandingPrincipal),
          annualInterestRatePercent = DecimalUtils.fromStringOrZero(p.annualInterestRatePercent),
          emiAmount = DecimalUtils.fromStringOrZero(p.emiAmount),
          currencyCode = p.currencyCode,
          paymentDueDayOfMonth = p.paymentDueDayOfMonth,
          nextDueDateEpochDay = p.nextDueDateEpochDay,
          remainingTenureMonths = p.remainingTenureMonths,
          startDateEpochDay = p.startDateEpochDay,
          status = runCatching { LoanStatus.valueOf(p.status) }.getOrDefault(LoanStatus.ACTIVE),
          createdAtEpochMillis = p.createdAtEpochMillis,
          updatedAtEpochMillis = p.updatedAtEpochMillis,
        )
        database.loanDao().insertLoan(entity).toString()
      }

      SyncEntityTypes.EMI_PAYMENT -> {
        val p = json.decodeFromString(CloudEmiPaymentPayload.serializer(), payloadJson)
        val mappedLoanId = p.loanRecordId
          ?.let { syncDao.findMapByRecordId(ownerUid, SyncEntityTypes.LOAN, it)?.localId?.toLongOrNull() }
          ?: database.loanDao().getLoanById(p.loanLocalId)?.id
          ?: database.loanDao().getAllLoansOnce().firstOrNull()?.id
          ?: error("Missing parent loan for synced EMI payment.")
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: 0L)
        val insertedId = database.loanDao().insertEmiPayment(
          EmiPaymentEntity(
            id = targetId,
            loanId = mappedLoanId,
            paymentDateEpochDay = p.paymentDateEpochDay,
            totalPaidAmount = DecimalUtils.fromStringOrZero(p.totalPaidAmount),
            principalComponent = DecimalUtils.fromStringOrZero(p.principalComponent),
            interestComponent = DecimalUtils.fromStringOrZero(p.interestComponent),
            extraPrepaymentAmount = DecimalUtils.fromStringOrZero(p.extraPrepaymentAmount),
            remainingBalanceAfter = DecimalUtils.fromStringOrZero(p.remainingBalanceAfter),
            currencyCode = p.currencyCode,
            note = p.note,
            createdAtEpochMillis = p.createdAtEpochMillis,
          ),
        )
        insertedId.toString()
      }

      SyncEntityTypes.SAVINGS_GOAL -> {
        val p = json.decodeFromString(CloudSavingsGoalPayload.serializer(), payloadJson)
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: 0L)
        val insertedId = database.savingsGoalDao().insertGoal(
          SavingsGoalEntity(
            id = targetId,
            goalName = if (asCopyKeepBoth) "${p.goalName} (Cloud Copy)" else p.goalName,
            goalCategory = runCatching { SavingsGoalType.valueOf(p.goalCategory) }.getOrDefault(SavingsGoalType.PERSONAL_SAVINGS),
            targetAmount = DecimalUtils.fromStringOrZero(p.targetAmount),
            currentSavedAmount = DecimalUtils.fromStringOrZero(p.currentSavedAmount),
            currencyCode = p.currencyCode,
            targetDateEpochDay = p.targetDateEpochDay,
            isEmergencyFund = p.isEmergencyFund,
            createdAtEpochMillis = p.createdAtEpochMillis,
            updatedAtEpochMillis = p.updatedAtEpochMillis,
          ),
        )
        insertedId.toString()
      }

      SyncEntityTypes.BUDGET -> {
        val p = json.decodeFromString(CloudBudgetPayload.serializer(), payloadJson)
        val categories = database.categoryDao().getAllCategoriesOnce()
        val resolvedCatId = categories.firstOrNull { it.id == p.categoryId }?.id
          ?: categories.firstOrNull { it.type == CategoryType.EXPENSE }?.id
          ?: categories.firstOrNull()?.id
          ?: 1L
        val existingUnique = if (!asCopyKeepBoth) {
          existingLocalId?.toLongOrNull()?.let { id ->
            database.budgetDao().getAllBudgetsOnce().firstOrNull { it.id == id }
          } ?: database.budgetDao().findBudget(resolvedCatId, p.yearMonth, p.currencyCode)
        } else null

        val insertedId = database.budgetDao().upsert(
          BudgetEntity(
            id = existingUnique?.id ?: 0L,
            categoryId = resolvedCatId,
            yearMonth = p.yearMonth,
            limitAmount = DecimalUtils.fromStringOrZero(p.limitAmount),
            currencyCode = p.currencyCode,
            rolloverEnabled = p.rolloverEnabled,
            rolloverAmount = DecimalUtils.fromStringOrZero(p.rolloverAmount),
            warningThresholdPercent = p.warningThresholdPercent,
            createdAtEpochMillis = p.createdAtEpochMillis,
            updatedAtEpochMillis = p.updatedAtEpochMillis,
          ),
        )
        (existingUnique?.id ?: insertedId).toString()
      }

      SyncEntityTypes.INVESTMENT -> {
        val p = json.decodeFromString(CloudInvestmentPayload.serializer(), payloadJson)
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: 0L)
        val insertedId = database.investmentDao().insertInvestment(
          InvestmentEntity(
            id = targetId,
            investmentName = if (asCopyKeepBoth) "${p.investmentName} (Cloud Copy)" else p.investmentName,
            investmentType = p.investmentType,
            amountInvested = DecimalUtils.fromStringOrZero(p.amountInvested),
            investmentDateEpochDay = p.investmentDateEpochDay,
            currentValue = p.currentValue?.let { DecimalUtils.fromStringOrZero(it) },
            notes = p.notes,
            currencyCode = p.currencyCode,
            createdAtEpochMillis = p.createdAtEpochMillis,
            updatedAtEpochMillis = p.updatedAtEpochMillis,
          ),
        )
        insertedId.toString()
      }

      SyncEntityTypes.TRANSACTION -> {
        val p = json.decodeFromString(CloudTransactionPayload.serializer(), payloadJson)
        val resolvedAccountId = p.accountRecordId.takeIf { it.isNotBlank() }
          ?.let { syncDao.findMapByRecordId(ownerUid, SyncEntityTypes.ACCOUNT, it)?.localId?.toLongOrNull() }
          ?: database.accountDao().getPrimaryAccountOnce()?.id
          ?: 1L
        val categories = database.categoryDao().getAllCategoriesOnce()
        val resolvedCategoryId = categories.firstOrNull {
          p.categoryNameKey.isNotBlank() && it.nameKey == p.categoryNameKey
        }?.id ?: categories.firstOrNull { it.id == p.categoryId }?.id
          ?: categories.firstOrNull()?.id
          ?: 1L
        val resolvedGoalId = p.linkedGoalRecordId
          ?.let { syncDao.findMapByRecordId(ownerUid, SyncEntityTypes.SAVINGS_GOAL, it)?.localId?.toLongOrNull() }
        val resolvedLoanId = p.linkedLoanRecordId
          ?.let { syncDao.findMapByRecordId(ownerUid, SyncEntityTypes.LOAN, it)?.localId?.toLongOrNull() }

        val dedupExisting = if (!asCopyKeepBoth && !p.deduplicationKey.isNullOrBlank()) {
          database.transactionDao().findByDeduplicationKey(p.deduplicationKey)
        } else null
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: dedupExisting?.id ?: 0L)
        val finalDedupKey = if (asCopyKeepBoth && !p.deduplicationKey.isNullOrBlank()) {
          "${p.deduplicationKey}_copy_${UUID.randomUUID().toString().take(6)}"
        } else {
          p.deduplicationKey
        }
        val entity = TransactionEntity(
          id = targetId,
          accountId = resolvedAccountId,
          categoryId = resolvedCategoryId,
          type = TransactionType.valueOf(p.type),
          amount = DecimalUtils.fromStringOrZero(p.amount),
          currencyCode = p.currencyCode,
          convertedPrimaryAmount = p.convertedPrimaryAmount?.let { DecimalUtils.fromStringOrZero(it) },
          primaryCurrencyAtEntry = p.primaryCurrencyAtEntry,
          exchangeRateSource = p.exchangeRateSource,
          description = p.description,
          transactionDateEpochDay = p.transactionDateEpochDay,
          isRecurring = p.isRecurring,
          recurrenceIntervalDays = p.recurrenceIntervalDays,
          isUpcomingBill = p.isUpcomingBill,
          isPaid = p.isPaid,
          linkedGoalId = resolvedGoalId,
          linkedLoanId = resolvedLoanId,
          deduplicationKey = finalDedupKey,
          createdAtEpochMillis = p.createdAtEpochMillis,
          updatedAtEpochMillis = p.updatedAtEpochMillis,
        )
        val finalId = if (targetId > 0L && database.transactionDao().getTransactionById(targetId) != null) {
          database.transactionDao().update(entity)
          targetId
        } else {
          database.transactionDao().insert(entity.copy(id = 0L))
        }
        finalId.toString()
      }

      SyncEntityTypes.SHOPPING_LIST -> {
        val p = json.decodeFromString(CloudShoppingListPayload.serializer(), payloadJson)
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: 0L)
        val insertedId = database.shoppingDao().insertList(
          ShoppingListEntity(
            id = targetId,
            name = if (asCopyKeepBoth) "${p.name} (Cloud Copy)" else p.name,
            budgetAmount = DecimalUtils.fromStringOrZero(p.budgetAmount),
            currencyCode = p.currencyCode,
            notes = p.notes,
            createdAtEpochMillis = p.createdAtEpochMillis,
            updatedAtEpochMillis = p.updatedAtEpochMillis,
          ),
        )
        insertedId.toString()
      }

      SyncEntityTypes.SHOPPING_ITEM -> {
        val p = json.decodeFromString(CloudShoppingItemPayload.serializer(), payloadJson)
        val mappedListId = p.listRecordId
          ?.let { syncDao.findMapByRecordId(ownerUid, SyncEntityTypes.SHOPPING_LIST, it)?.localId?.toLongOrNull() }
          ?: database.shoppingDao().getAllShoppingListsOnce().firstOrNull { it.id == p.listId }?.id
          ?: database.shoppingDao().getAllShoppingListsOnce().firstOrNull()?.id
          ?: error("Missing parent shopping list for synced item.")
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: 0L)
        val insertedId = database.shoppingDao().insertItem(
          ShoppingItemEntity(
            id = targetId,
            listId = mappedListId,
            name = p.name,
            categoryName = p.categoryName,
            quantity = DecimalUtils.fromStringOrZero(p.quantity),
            unit = p.unit,
            estimatedUnitPrice = DecimalUtils.fromStringOrZero(p.estimatedUnitPrice),
            actualUnitPrice = p.actualUnitPrice?.let { DecimalUtils.fromStringOrZero(it) },
            isPurchased = p.isPurchased,
            notes = p.notes,
            createdAtEpochMillis = p.createdAtEpochMillis,
            updatedAtEpochMillis = p.updatedAtEpochMillis,
          ),
        )
        insertedId.toString()
      }

      SyncEntityTypes.SHOPPING_SHOP_PRICE -> {
        val p = json.decodeFromString(CloudShoppingShopPricePayload.serializer(), payloadJson)
        val targetId = if (asCopyKeepBoth) 0L else (existingLocalId?.toLongOrNull() ?: 0L)
        val insertedId = database.shoppingDao().insertShopPrice(
          ShoppingShopPriceEntity(
            id = targetId,
            itemNameNormalized = p.itemNameNormalized,
            productDisplayName = p.productDisplayName,
            shopName = p.shopName,
            shopLocation = p.shopLocation,
            unit = p.unit,
            price = DecimalUtils.fromStringOrZero(p.price),
            currencyCode = p.currencyCode,
            priceSource = p.priceSource,
            isLiveFeedAvailable = p.isLiveFeedAvailable,
            updatedAtEpochMillis = p.updatedAtEpochMillis,
          ),
        )
        insertedId.toString()
      }

      else -> error("Unsupported sync entity type: $entityType")
    }

    val effectiveRecordId = if (asCopyKeepBoth) UUID.randomUUID().toString() else recordId
    val existingFinalMap = syncDao.findMapByLocalId(ownerUid, entityType, resolvedLocalId)
    syncDao.upsertMap(
      SyncEntityMapEntity(
        id = existingFinalMap?.id ?: 0L,
        ownerUid = ownerUid,
        entityType = entityType,
        localId = resolvedLocalId,
        recordId = effectiveRecordId,
        syncVersion = syncVersion,
        lastSyncedVersion = if (asCopyKeepBoth) 0L else syncVersion,
        lastSyncedAtEpochMillis = if (asCopyKeepBoth) 0L else System.currentTimeMillis(),
        isDirty = asCopyKeepBoth,
        isDeleted = false,
        updatedByDeviceId = updatedByDeviceId,
        updatedAtEpochMillis = updatedAtEpochMillis,
      ),
    )
    return resolvedLocalId
  }

  private suspend fun deleteLocalEntity(
    entityType: String,
    localId: String,
    database: SkyPlusDatabase,
  ) {
    val numericId = localId.toLongOrNull() ?: return
    when (entityType) {
      SyncEntityTypes.TRANSACTION -> database.transactionDao().deleteById(numericId)
      SyncEntityTypes.BUDGET -> database.budgetDao().deleteById(numericId)
      SyncEntityTypes.LOAN -> {
        database.loanDao().getLoanById(numericId)?.let { database.loanDao().deleteLoan(it) }
      }
      SyncEntityTypes.SAVINGS_GOAL -> {
        database.savingsGoalDao().getGoalById(numericId)?.let { database.savingsGoalDao().deleteGoal(it) }
      }
      SyncEntityTypes.INVESTMENT -> database.investmentDao().deleteInvestmentById(numericId)
      SyncEntityTypes.SHOPPING_LIST -> database.shoppingDao().deleteListById(numericId)
      SyncEntityTypes.SHOPPING_ITEM -> database.shoppingDao().deleteItemById(numericId)
      SyncEntityTypes.SHOPPING_SHOP_PRICE -> database.shoppingDao().deleteShopPriceById(numericId)
      else -> Unit
    }
  }
}
