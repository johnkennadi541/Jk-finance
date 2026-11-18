package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.ShoppingShopPriceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingDao {
  @Query("SELECT * FROM shopping_lists ORDER BY updatedAtEpochMillis DESC, id DESC")
  fun observeShoppingLists(): Flow<List<ShoppingListEntity>>

  @Query("SELECT * FROM shopping_items ORDER BY isPurchased ASC, updatedAtEpochMillis DESC, id DESC")
  fun observeAllShoppingItems(): Flow<List<ShoppingItemEntity>>

  @Query("SELECT * FROM shopping_items WHERE listId = :listId ORDER BY isPurchased ASC, id DESC")
  fun observeItemsForList(listId: Long): Flow<List<ShoppingItemEntity>>

  @Query("SELECT * FROM shopping_shop_prices ORDER BY itemNameNormalized ASC, price ASC, updatedAtEpochMillis DESC")
  fun observeAllShopPrices(): Flow<List<ShoppingShopPriceEntity>>

  @Query("SELECT * FROM shopping_lists ORDER BY id ASC")
  suspend fun getAllShoppingListsOnce(): List<ShoppingListEntity>

  @Query("SELECT * FROM shopping_items ORDER BY id ASC")
  suspend fun getAllShoppingItemsOnce(): List<ShoppingItemEntity>

  @Query("SELECT * FROM shopping_shop_prices ORDER BY id ASC")
  suspend fun getAllShopPricesOnce(): List<ShoppingShopPriceEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertList(list: ShoppingListEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllLists(lists: List<ShoppingListEntity>)

  @Update
  suspend fun updateList(list: ShoppingListEntity)

  @Query("DELETE FROM shopping_lists WHERE id = :listId")
  suspend fun deleteListById(listId: Long)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertItem(item: ShoppingItemEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllItems(items: List<ShoppingItemEntity>)

  @Update
  suspend fun updateItem(item: ShoppingItemEntity)

  @Query("DELETE FROM shopping_items WHERE id = :itemId")
  suspend fun deleteItemById(itemId: Long)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertShopPrice(price: ShoppingShopPriceEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllShopPrices(prices: List<ShoppingShopPriceEntity>)

  @Delete
  suspend fun deleteShopPrice(price: ShoppingShopPriceEntity)

  @Query("DELETE FROM shopping_shop_prices WHERE id = :priceId")
  suspend fun deleteShopPriceById(priceId: Long)

  @Query("DELETE FROM shopping_items")
  suspend fun deleteAllItems()

  @Query("DELETE FROM shopping_lists")
  suspend fun deleteAllLists()

  @Query("DELETE FROM shopping_shop_prices")
  suspend fun deleteAllShopPrices()
}
