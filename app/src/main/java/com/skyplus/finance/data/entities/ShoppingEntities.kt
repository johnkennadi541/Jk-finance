package com.skyplus.finance.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "shopping_lists")
data class ShoppingListEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val budgetAmount: BigDecimal,
  val currencyCode: String,
  val notes: String = "",
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Entity(
  tableName = "shopping_items",
  foreignKeys = [
    ForeignKey(
      entity = ShoppingListEntity::class,
      parentColumns = ["id"],
      childColumns = ["listId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["listId"])],
)
data class ShoppingItemEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val listId: Long,
  val name: String,
  val categoryName: String,
  val quantity: BigDecimal,
  val unit: String,
  val estimatedUnitPrice: BigDecimal,
  val actualUnitPrice: BigDecimal? = null,
  val isPurchased: Boolean = false,
  val notes: String = "",
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

@Entity(
  tableName = "shopping_shop_prices",
  indices = [Index(value = ["itemNameNormalized"])],
)
data class ShoppingShopPriceEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val itemNameNormalized: String,
  val productDisplayName: String,
  val shopName: String,
  val shopLocation: String,
  val unit: String,
  val price: BigDecimal,
  val currencyCode: String,
  val priceSource: String = "Manual Entry",
  val isLiveFeedAvailable: Boolean = false,
  val updatedAtEpochMillis: Long,
)
