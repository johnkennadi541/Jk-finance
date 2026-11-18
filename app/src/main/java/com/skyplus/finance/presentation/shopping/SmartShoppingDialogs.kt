package com.skyplus.finance.presentation.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.localization.LocalAppStrings
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal

internal val SHOPPING_UNITS = listOf("kg", "g", "litre", "ml", "piece", "packet", "dozen", "box")

internal data class ShoppingCategoryOption(
  val name: String,
  val icon: ImageVector,
)

internal val SHOPPING_CATEGORY_OPTIONS = listOf(
  ShoppingCategoryOption("Groceries", Icons.Filled.ShoppingCart),
  ShoppingCategoryOption("Vegetables", Icons.Filled.Eco),
  ShoppingCategoryOption("Fruits", Icons.Filled.LocalFlorist),
  ShoppingCategoryOption("Dairy Products", Icons.Filled.LocalCafe),
  ShoppingCategoryOption("Meat & Seafood", Icons.Filled.Restaurant),
  ShoppingCategoryOption("Bakery", Icons.Filled.BakeryDining),
  ShoppingCategoryOption("Beverages", Icons.Filled.LocalDrink),
  ShoppingCategoryOption("Household Items", Icons.Filled.Home),
  ShoppingCategoryOption("Personal Care", Icons.Filled.Spa),
  ShoppingCategoryOption("Medicines & Health", Icons.Filled.MedicalServices),
  ShoppingCategoryOption("Electronics", Icons.Filled.Devices),
  ShoppingCategoryOption("Clothing", Icons.Filled.Checkroom),
  ShoppingCategoryOption("Transport", Icons.Filled.DirectionsCar),
  ShoppingCategoryOption("Education", Icons.Filled.School),
  ShoppingCategoryOption("Rent", Icons.Filled.Apartment),
  ShoppingCategoryOption("Bills & Utilities", Icons.Filled.Lightbulb),
  ShoppingCategoryOption("Entertainment", Icons.Filled.Movie),
  ShoppingCategoryOption("Other", Icons.Filled.Category),
)

internal val SHOPPING_CATEGORIES = SHOPPING_CATEGORY_OPTIONS.map { it.name }

internal fun shoppingCategoryIcon(categoryName: String): ImageVector =
  SHOPPING_CATEGORY_OPTIONS.firstOrNull {
    it.name.equals(categoryName, ignoreCase = true)
  }?.icon ?: Icons.Filled.Category

@Composable
fun ShoppingListDialog(
  existingList: ShoppingListEntity?,
  defaultCurrencyCode: String,
  onSave: (id: Long, name: String, budgetText: String, notes: String) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var name by remember { mutableStateOf(existingList?.name ?: "") }
  var budget by remember {
    mutableStateOf(
      existingList?.let { DecimalUtils.toCanonicalString(it.budgetAmount) } ?: "",
    )
  }
  var notes by remember { mutableStateOf(existingList?.notes ?: "") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (existingList == null) strings.createShoppingList else "Edit Shopping List") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("List Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shopping_list_name_input"),
        )
        OutlinedTextField(
          value = budget,
          onValueChange = { budget = it },
          label = { Text("Budget ($defaultCurrencyCode)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shopping_list_budget_input"),
        )
        OutlinedTextField(
          value = notes,
          onValueChange = { notes = it },
          label = { Text(strings.descriptionOptional) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(existingList?.id ?: 0L, name, budget.ifBlank { "0" }, notes)
        },
        modifier = Modifier.testTag("shopping_list_save_button"),
      ) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingItemDialog(
  existingItem: ShoppingItemEntity?,
  prefilledName: String = "",
  prefilledCategory: String = "Groceries",
  prefilledUnit: String = "piece",
  prefilledSource: String = "Manual Entry",
  currencyCode: String,
  onSave: (
    id: Long,
    name: String,
    category: String,
    qtyText: String,
    unit: String,
    estPriceText: String,
    actualPriceText: String,
    shopName: String,
    sourceReference: String,
  ) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var name by remember { mutableStateOf(existingItem?.name ?: prefilledName) }
  var category by remember {
    val initialCat = existingItem?.categoryName ?: prefilledCategory
    mutableStateOf(if (initialCat in SHOPPING_CATEGORIES) initialCat else "Groceries")
  }
  var qty by remember {
    mutableStateOf(
      existingItem?.let { DecimalUtils.toCanonicalString(it.quantity) } ?: "1",
    )
  }
  var unit by remember {
    val initialUnit = existingItem?.unit ?: prefilledUnit
    mutableStateOf(if (initialUnit in SHOPPING_UNITS) initialUnit else SHOPPING_UNITS.first())
  }
  var estPrice by remember {
    mutableStateOf(
      existingItem?.estimatedUnitPrice
        ?.takeIf { it > BigDecimal.ZERO }
        ?.let { DecimalUtils.toCanonicalString(it) }
        ?: "",
    )
  }
  var shopName by remember { mutableStateOf("") }
  var sourceRef by remember { mutableStateOf(prefilledSource.ifBlank { "Manual Entry" }) }

  var categoryExpanded by remember { mutableStateOf(false) }
  var unitExpanded by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (existingItem == null) strings.addShoppingItem else "Edit Item") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Item Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shopping_item_name_input"),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = qty,
            onValueChange = { qty = it },
            label = { Text("Quantity") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("shopping_item_qty_input"),
          )

          ExposedDropdownMenuBox(
            expanded = unitExpanded,
            onExpandedChange = { unitExpanded = !unitExpanded },
            modifier = Modifier.weight(1f),
          ) {
            OutlinedTextField(
              value = unit,
              onValueChange = {},
              readOnly = true,
              label = { Text("Unit") },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .testTag("shopping_item_unit_dropdown"),
            )
            ExposedDropdownMenu(
              expanded = unitExpanded,
              onDismissRequest = { unitExpanded = false },
            ) {
              SHOPPING_UNITS.forEach { u ->
                DropdownMenuItem(
                  text = { Text(u) },
                  onClick = {
                    unit = u
                    unitExpanded = false
                  },
                )
              }
            }
          }
        }

        ExposedDropdownMenuBox(
          expanded = categoryExpanded,
          onExpandedChange = { categoryExpanded = !categoryExpanded },
          modifier = Modifier.fillMaxWidth(),
        ) {
          OutlinedTextField(
            value = category,
            onValueChange = {},
            readOnly = true,
            label = { Text(strings.categoryLabel) },
            leadingIcon = {
              Icon(
                imageVector = shoppingCategoryIcon(category),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .menuAnchor(MenuAnchorType.PrimaryNotEditable)
              .testTag("shopping_item_category_dropdown"),
          )
          ExposedDropdownMenu(
            expanded = categoryExpanded,
            onDismissRequest = { categoryExpanded = false },
          ) {
            SHOPPING_CATEGORY_OPTIONS.forEach { opt ->
              DropdownMenuItem(
                leadingIcon = {
                  Icon(
                    imageVector = opt.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                },
                text = { Text(opt.name) },
                onClick = {
                  category = opt.name
                  categoryExpanded = false
                },
              )
            }
          }
        }

        OutlinedTextField(
          value = estPrice,
          onValueChange = { estPrice = it },
          label = { Text("${strings.unitPriceOptional} ($currencyCode)") },
          placeholder = { Text(strings.priceUnavailable) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shopping_item_price_input"),
        )

        OutlinedTextField(
          value = shopName,
          onValueChange = { shopName = it },
          label = { Text(strings.shopSellerOptional) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shopping_item_shop_input"),
        )

        OutlinedTextField(
          value = sourceRef,
          onValueChange = { sourceRef = it },
          label = { Text(strings.sourceReferenceLabel) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shopping_item_source_input"),
        )

        Text(
          text = "Leave price blank if unverified; date and time are recorded automatically.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      Button(
        enabled = name.isNotBlank(),
        onClick = {
          val safePrice = estPrice.trim().ifBlank { BigDecimal.ZERO.toPlainString() }
          onSave(
            existingItem?.id ?: 0L,
            name,
            category,
            qty.ifBlank { "1" },
            unit,
            safePrice,
            "",
            shopName.trim(),
            sourceRef.trim().ifBlank { "Manual Entry" },
          )
        },
        modifier = Modifier.testTag("shopping_item_save_button"),
      ) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopPriceDialog(
  initialProductName: String = "",
  initialLocation: String = "",
  initialSource: String = "Manual Entry",
  currencyCode: String,
  onSave: (
    productName: String,
    shopName: String,
    shopLocation: String,
    unit: String,
    priceText: String,
    sourceReference: String,
  ) -> Unit,
  onDismiss: () -> Unit,
) {
  val strings = LocalAppStrings.current
  var productName by remember { mutableStateOf(initialProductName) }
  var shopName by remember { mutableStateOf("") }
  var shopLocation by remember { mutableStateOf(initialLocation) }
  var unit by remember { mutableStateOf("kg") }
  var priceText by remember { mutableStateOf("") }
  var sourceRef by remember { mutableStateOf(initialSource.ifBlank { "Manual Entry" }) }
  var unitExpanded by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.addShopPrice) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = productName,
          onValueChange = { productName = it },
          label = { Text("Product Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shop_price_product_input"),
        )
        OutlinedTextField(
          value = shopName,
          onValueChange = { shopName = it },
          label = { Text("Shop / Seller Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("shop_price_shop_input"),
        )
        OutlinedTextField(
          value = shopLocation,
          onValueChange = { shopLocation = it },
          label = { Text("Shop Location / Area (Optional)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = priceText,
            onValueChange = { priceText = it },
            label = { Text("Price ($currencyCode)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("shop_price_amount_input"),
          )
          ExposedDropdownMenuBox(
            expanded = unitExpanded,
            onExpandedChange = { unitExpanded = !unitExpanded },
            modifier = Modifier.weight(1f),
          ) {
            OutlinedTextField(
              value = unit,
              onValueChange = {},
              readOnly = true,
              label = { Text("Unit") },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
              expanded = unitExpanded,
              onDismissRequest = { unitExpanded = false },
            ) {
              SHOPPING_UNITS.forEach { u ->
                DropdownMenuItem(
                  text = { Text(u) },
                  onClick = {
                    unit = u
                    unitExpanded = false
                  },
                )
              }
            }
          }
        }
        OutlinedTextField(
          value = sourceRef,
          onValueChange = { sourceRef = it },
          label = { Text(strings.sourceReferenceLabel) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        enabled = productName.isNotBlank() && shopName.isNotBlank() && priceText.isNotBlank(),
        onClick = {
          onSave(
            productName,
            shopName,
            shopLocation,
            unit,
            priceText,
            sourceRef.trim().ifBlank { "Manual Entry" },
          )
        },
        modifier = Modifier.testTag("shop_price_save_button"),
      ) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}
