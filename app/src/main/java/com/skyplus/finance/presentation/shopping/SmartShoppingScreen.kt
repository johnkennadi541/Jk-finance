package com.skyplus.finance.presentation.shopping

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.CurrencyEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.designsystem.SkyPlusBrandColors
import com.skyplus.finance.designsystem.SkyPlusEmptyState
import com.skyplus.finance.designsystem.SkyPlusFonts
import com.skyplus.finance.designsystem.SkyPlusGoldBadge
import com.skyplus.finance.designsystem.SkyPlusPortfolioCard
import com.skyplus.finance.designsystem.SkyPlusPrimaryButton
import com.skyplus.finance.designsystem.SkyPlusSecondaryButton
import com.skyplus.finance.designsystem.SkyPlusSpacingTokens
import com.skyplus.finance.domain.shopping.ShopComparisonGroup
import com.skyplus.finance.domain.shopping.ShoppingBudgetStatus
import com.skyplus.finance.domain.shopping.ShoppingPlannerOverview
import com.skyplus.finance.domain.shopping.SmartProductSearchResult
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.util.CurrencyFormatter
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SmartShoppingSubTab {
  LISTS,
  SEARCH,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartShoppingScreen(
  shoppingLists: List<ShoppingListEntity>,
  shoppingItems: List<ShoppingItemEntity>,
  shopComparisonGroups: List<ShopComparisonGroup>,
  plannerOverview: ShoppingPlannerOverview,
  primaryCurrency: CurrencyEntity?,
  primaryCurrencyCode: String,
  onUpsertList: (id: Long, name: String, budgetText: String, currencyCode: String, notes: String) -> Unit,
  onDeleteList: (Long) -> Unit,
  onUpsertItem: (
    id: Long,
    listId: Long,
    name: String,
    category: String,
    qtyText: String,
    unit: String,
    estPriceText: String,
    actualPriceText: String,
  ) -> Unit,
  onToggleItemPurchased: (ShoppingItemEntity) -> Unit,
  onDeleteItem: (Long) -> Unit,
  onUpsertShopPrice: (
    productName: String,
    shopName: String,
    shopLocation: String,
    unit: String,
    priceText: String,
    currencyCode: String,
    priceSource: String,
  ) -> Unit,
  onDeleteShopPrice: (Long) -> Unit,
  onSearchProducts: (String) -> List<SmartProductSearchResult>,
  modifier: Modifier = Modifier,
) {
  val strings = LocalAppStrings.current
  val spacing = SkyPlusSpacingTokens()
  val context = LocalContext.current

  var activeTab by remember { mutableStateOf(SmartShoppingSubTab.LISTS) }
  var selectedListId by remember(shoppingLists) {
    mutableLongStateOf(shoppingLists.firstOrNull()?.id ?: 0L)
  }
  if (shoppingLists.none { it.id == selectedListId } && shoppingLists.isNotEmpty()) {
    selectedListId = shoppingLists.first().id
  }

  var showListDialog by remember { mutableStateOf(false) }
  var editingList by remember { mutableStateOf<ShoppingListEntity?>(null) }

  var showItemDialog by remember { mutableStateOf(false) }
  var editingItem by remember { mutableStateOf<ShoppingItemEntity?>(null) }
  var prefillName by remember { mutableStateOf("") }
  var prefillCategory by remember { mutableStateOf("Groceries") }
  var prefillUnit by remember { mutableStateOf("piece") }
  var prefillSource by remember { mutableStateOf("Manual Entry") }

  var showPriceDialog by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var searchSourceUsed by remember { mutableStateOf("Manual Entry") }
  var searchNotice by remember { mutableStateOf<String?>(null) }

  val searchResults = remember(searchQuery, shoppingItems, shopComparisonGroups) {
    onSearchProducts(searchQuery)
  }

  if (showListDialog) {
    ShoppingListDialog(
      existingList = editingList,
      defaultCurrencyCode = primaryCurrencyCode,
      onSave = { id, name, budgetText, notes ->
        onUpsertList(id, name, budgetText, primaryCurrencyCode, notes)
        showListDialog = false
        editingList = null
      },
      onDismiss = {
        showListDialog = false
        editingList = null
      },
    )
  }

  if (showItemDialog && selectedListId > 0L) {
    ShoppingItemDialog(
      existingItem = editingItem,
      prefilledName = prefillName,
      prefilledCategory = prefillCategory,
      prefilledUnit = prefillUnit,
      prefilledSource = prefillSource,
      currencyCode = primaryCurrencyCode,
      onSave = { id, name, category, qty, unit, est, actual, shopName, sourceRef ->
        onUpsertItem(id, selectedListId, name, category, qty, unit, est, actual)
        val parsedPrice = DecimalUtils.parsePositiveOrNull(est)
        if (shopName.isNotBlank() && parsedPrice != null) {
          onUpsertShopPrice(
            name,
            shopName,
            "",
            unit,
            parsedPrice.toPlainString(),
            primaryCurrencyCode,
            sourceRef,
          )
        }
        showItemDialog = false
        editingItem = null
        prefillName = ""
      },
      onDismiss = {
        showItemDialog = false
        editingItem = null
        prefillName = ""
      },
    )
  }

  if (showPriceDialog) {
    ShopPriceDialog(
      initialProductName = searchQuery.trim(),
      initialSource = searchSourceUsed,
      currencyCode = primaryCurrencyCode,
      onSave = { product, shop, loc, unit, price, source ->
        onUpsertShopPrice(product, shop, loc, unit, price, primaryCurrencyCode, source)
        showPriceDialog = false
      },
      onDismiss = { showPriceDialog = false },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = spacing.maxContentWidth),
      contentPadding = PaddingValues(horizontal = spacing.md, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Text(
          text = strings.navShopping,
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.testTag("shopping_screen_title"),
        )
      }

      item {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          val tabs = listOf(
            SmartShoppingSubTab.LISTS to strings.shoppingTabLists,
            SmartShoppingSubTab.SEARCH to strings.shoppingTabSearch,
          )
          tabs.forEachIndexed { idx, (tab, label) ->
            SegmentedButton(
              selected = activeTab == tab,
              onClick = { activeTab = tab },
              shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
              modifier = Modifier.testTag("shopping_tab_${tab.name.lowercase()}"),
            ) {
              Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }

      when (activeTab) {
        SmartShoppingSubTab.LISTS -> {
          item {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                shoppingLists.forEach { list ->
                  FilterChip(
                    selected = list.id == selectedListId,
                    onClick = { selectedListId = list.id },
                    label = { Text(list.name, maxLines = 1) },
                  )
                }
              }
              Spacer(modifier = Modifier.width(8.dp))
              SkyPlusPrimaryButton(
                text = strings.createShoppingList,
                icon = Icons.Filled.Add,
                onClick = {
                  editingList = null
                  showListDialog = true
                },
                modifier = Modifier.testTag("shopping_new_list_button"),
              )
            }
          }

          val activeSummary = plannerOverview.listSummaries.firstOrNull { it.list.id == selectedListId }
          if (activeSummary == null) {
            item {
              SkyPlusEmptyState(
                icon = Icons.Filled.ShoppingCart,
                title = "No shopping lists yet",
                subtitle = "Create a shopping list with quantities, units, and a spending budget.",
                actionLabel = strings.createShoppingList,
                onAction = {
                  editingList = null
                  showListDialog = true
                },
              )
            }
          } else {
            val activeList = activeSummary.list
            item {
              SkyPlusPortfolioCard(modifier = Modifier.testTag("shopping_active_list_card")) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(activeList.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (activeList.notes.isNotBlank()) {
                      Text(activeList.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                  Row {
                    IconButton(onClick = {
                      editingList = activeList
                      showListDialog = true
                    }) {
                      Icon(Icons.Filled.Edit, contentDescription = "Edit list")
                    }
                    IconButton(onClick = { onDeleteList(activeList.id) }) {
                      Icon(Icons.Filled.DeleteOutline, contentDescription = strings.delete, tint = SkyPlusBrandColors.ErrorCoral)
                    }
                  }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Column {
                    Text("Planned Estimate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                      text = CurrencyFormatter.formatWithEntity(activeSummary.totalPlanned, primaryCurrency, activeList.currencyCode),
                      style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
                      fontWeight = FontWeight.Bold,
                    )
                  }
                  Column(horizontalAlignment = Alignment.End) {
                    Text(strings.shoppingBudgetRemaining, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val remColor = when (activeSummary.budgetStatus) {
                      ShoppingBudgetStatus.OVER_BUDGET -> SkyPlusBrandColors.ErrorCoral
                      ShoppingBudgetStatus.APPROACHING -> SkyPlusBrandColors.WarningAmber
                      else -> SkyPlusBrandColors.EmeraldGreen
                    }
                    Text(
                      text = CurrencyFormatter.formatWithEntity(activeSummary.remainingBudget, primaryCurrency, activeList.currencyCode),
                      style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
                      color = remColor,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }

                SkyPlusPrimaryButton(
                  text = strings.addShoppingItem,
                  icon = Icons.Filled.Add,
                  onClick = {
                    editingItem = null
                    prefillName = ""
                    prefillCategory = "Groceries"
                    prefillUnit = "piece"
                    prefillSource = "Manual Entry"
                    showItemDialog = true
                  },
                  modifier = Modifier.fillMaxWidth().testTag("shopping_add_item_button"),
                )
              }
            }

            if (activeSummary.items.isEmpty()) {
              item {
                SkyPlusEmptyState(
                  icon = Icons.Filled.ShoppingBag,
                  title = "List is empty",
                  subtitle = "Add items directly or use Search to look up products on Google and record real prices.",
                )
              }
            } else {
              items(activeSummary.items, key = { it.id }) { item ->
                val hasPrice = item.estimatedUnitPrice > BigDecimal.ZERO || item.actualUnitPrice != null
                val effectiveUnitPrice = item.actualUnitPrice ?: item.estimatedUnitPrice
                val lineEst = DecimalUtils.scaleToCurrency(
                  item.quantity.multiply(effectiveUnitPrice, DecimalUtils.FINANCIAL_MATH_CONTEXT),
                  primaryCurrency?.decimalPrecision ?: 2,
                )
                SkyPlusPortfolioCard(modifier = Modifier.testTag("shopping_item_${item.id}")) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Checkbox(
                      checked = item.isPurchased,
                      onCheckedChange = { onToggleItemPurchased(item) },
                    )
                    Icon(
                      imageVector = shoppingCategoryIcon(item.categoryName),
                      contentDescription = item.categoryName,
                      tint = SkyPlusBrandColors.RoyalBlue,
                      modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (item.isPurchased) TextDecoration.LineThrough else TextDecoration.None,
                      )
                      val unitPricePart = if (hasPrice) {
                        " × ${CurrencyFormatter.formatWithEntity(effectiveUnitPrice, primaryCurrency, activeList.currencyCode)}"
                      } else {
                        " • ${strings.priceUnavailable}"
                      }
                      Text(
                        text = "${DecimalUtils.toCanonicalString(item.quantity)} ${item.unit}$unitPricePart • ${item.categoryName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                      Text(
                        text = if (hasPrice) {
                          CurrencyFormatter.formatWithEntity(lineEst, primaryCurrency, activeList.currencyCode)
                        } else {
                          "—"
                        },
                        style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.Bold,
                        color = if (hasPrice) SkyPlusBrandColors.Teal else MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                      Row {
                        IconButton(onClick = {
                          editingItem = item
                          prefillSource = "Manual Entry"
                          showItemDialog = true
                        }) {
                          Icon(Icons.Filled.Edit, contentDescription = "Edit item", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDeleteItem(item.id) }) {
                          Icon(Icons.Filled.DeleteOutline, contentDescription = strings.delete, tint = SkyPlusBrandColors.ErrorCoral, modifier = Modifier.size(18.dp))
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }

        SmartShoppingSubTab.SEARCH -> {
          item {
            SkyPlusPortfolioCard {
              OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                  searchQuery = it
                  searchNotice = null
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(strings.smartSearchHint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("shopping_search_input"),
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                SkyPlusPrimaryButton(
                  text = strings.searchOnGoogle,
                  icon = Icons.AutoMirrored.Filled.OpenInNew,
                  onClick = {
                    val trimmed = searchQuery.trim()
                    if (trimmed.isEmpty()) {
                      searchNotice = "Enter a product name first to search on Google."
                    } else {
                      searchSourceUsed = "Google Search"
                      val opened = openExternalUrl(
                        context = context,
                        url = "https://www.google.com/search?q=${Uri.encode(trimmed)}",
                      )
                      searchNotice = if (opened) {
                        "Opened Google Search. Return here and tap \"${strings.addToShoppingList}\" to save the verified seller & price."
                      } else {
                        "No browser app available on this device. You can still add the item and price manually."
                      }
                    }
                  },
                  modifier = Modifier.weight(1f).testTag("google_search_button"),
                )
                OutlinedButton(
                  onClick = {
                    val q = searchQuery.trim().ifBlank { "grocery store near me" }
                    val opened = openExternalUrl(
                      context = context,
                      url = "geo:0,0?q=${Uri.encode(q)}",
                    )
                    if (!opened) {
                      searchNotice = "No maps app available to show nearby shops."
                    }
                  },
                  modifier = Modifier.height(48.dp).testTag("shopping_nearby_shops_button"),
                ) {
                  Icon(Icons.Filled.LocationOn, contentDescription = strings.findNearbyShops, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(strings.findNearbyShops, maxLines = 1)
                }
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                SkyPlusSecondaryButton(
                  text = strings.addToShoppingList,
                  icon = Icons.Filled.Add,
                  onClick = {
                    if (shoppingLists.isEmpty()) {
                      editingList = null
                      showListDialog = true
                    } else {
                      editingItem = null
                      prefillName = searchQuery.trim()
                      prefillCategory = "Groceries"
                      prefillUnit = "piece"
                      prefillSource = searchSourceUsed
                      showItemDialog = true
                    }
                  },
                  modifier = Modifier.weight(1f).testTag("search_add_to_list_button"),
                )
                OutlinedButton(
                  onClick = { showPriceDialog = true },
                  modifier = Modifier.height(48.dp).testTag("shopping_add_price_button"),
                ) {
                  Icon(Icons.Filled.Storefront, contentDescription = strings.addShopPrice, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Log Price", maxLines = 1)
                }
              }

              searchNotice?.let { notice ->
                Text(
                  text = notice,
                  style = MaterialTheme.typography.labelSmall,
                  color = SkyPlusBrandColors.Teal,
                )
              }
            }
          }

          if (searchResults.isNotEmpty()) {
            item {
              Text(
                text = "Your Saved Items (${searchResults.size})",
                style = MaterialTheme.typography.titleMedium,
              )
            }
            items(searchResults, key = { it.canonicalKey }) { result ->
              SkyPlusPortfolioCard(modifier = Modifier.testTag("shopping_search_result_${result.canonicalKey}")) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    Icon(
                      imageVector = shoppingCategoryIcon(result.category),
                      contentDescription = result.category,
                      tint = SkyPlusBrandColors.RoyalBlue,
                      modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                      Text(result.displayEn, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                      Text(
                        text = "${result.category} (${result.suggestedUnit})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                      result.lowestSavedPriceLabel?.let { label ->
                        Text(
                          text = "Recorded: $label",
                          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelSmall),
                          color = SkyPlusBrandColors.EmeraldGreen,
                          fontWeight = FontWeight.SemiBold,
                        )
                      }
                    }
                  }
                  TextButton(
                    onClick = {
                      if (shoppingLists.isEmpty()) {
                        editingList = null
                        showListDialog = true
                      } else {
                        editingItem = null
                        prefillName = result.displayEn
                        prefillCategory = result.category
                        prefillUnit = result.suggestedUnit
                        prefillSource = "Saved Item"
                        showItemDialog = true
                      }
                    },
                    modifier = Modifier.testTag("shopping_search_add_${result.canonicalKey}"),
                  ) {
                    Text("+ List")
                  }
                }
              }
            }
          } else if (searchQuery.isNotBlank() && shopComparisonGroups.isEmpty()) {
            item {
              SkyPlusEmptyState(
                icon = Icons.Filled.Search,
                title = "No matching saved items",
                subtitle = "Tap \"${strings.searchOnGoogle}\" to check real prices online or \"${strings.addToShoppingList}\" to add \"${searchQuery.trim()}\" directly.",
              )
            }
          }

          if (shopComparisonGroups.isNotEmpty()) {
            item {
              Text(
                text = "Recorded Seller Prices (${shopComparisonGroups.size})",
                style = MaterialTheme.typography.titleMedium,
              )
            }
            items(shopComparisonGroups, key = { "${it.itemKey}_${it.currencyCode}" }) { group ->
              ShopComparisonGroupCard(
                group = group,
                currencyEntity = primaryCurrency,
                onDeletePrice = onDeleteShopPrice,
              )
            }
          }
        }
      }

      item { Spacer(modifier = Modifier.height(12.dp)) }
    }
  }
}

@Composable
private fun ShopComparisonGroupCard(
  group: ShopComparisonGroup,
  currencyEntity: CurrencyEntity?,
  onDeletePrice: (Long) -> Unit,
) {
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
  }
  SkyPlusPortfolioCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(group.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          text = "Recorded seller prices",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (group.potentialSavings > BigDecimal.ZERO) {
        SkyPlusGoldBadge(
          text = "Save ${CurrencyFormatter.formatWithEntity(group.potentialSavings, currencyEntity, group.currencyCode)}",
        )
      }
    }

    HorizontalDivider()

    group.entriesSortedLowestFirst.forEachIndexed { idx, entry ->
      val updatedText = Instant.ofEpochMilli(entry.updatedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(dateFormatter)
      val locationSuffix = entry.shopLocation
        .takeIf { it.isNotBlank() && it != "Manual Location" }
        ?.let { " ($it)" }
        ?: ""
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            if (idx == 0) SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.08f) else Color.Transparent,
            RoundedCornerShape(8.dp),
          )
          .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "${entry.shopName}$locationSuffix",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Medium,
          )
          Text(
            text = "${entry.priceSource} • $updatedText",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "${CurrencyFormatter.formatWithEntity(entry.price, currencyEntity, entry.currencyCode)}/${entry.unit}",
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelLarge),
            color = if (idx == 0) SkyPlusBrandColors.EmeraldGreen else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
          )
          IconButton(onClick = { onDeletePrice(entry.id) }) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete price", modifier = Modifier.size(18.dp))
          }
        }
      }
    }
  }
}

private fun openExternalUrl(context: Context, url: String): Boolean {
  return runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
  }.getOrDefault(false)
}
