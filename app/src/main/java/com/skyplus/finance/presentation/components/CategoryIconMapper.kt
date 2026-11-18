package com.skyplus.finance.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.ui.graphics.vector.ImageVector

object CategoryIconMapper {
  fun iconFor(iconKey: String): ImageVector = when (iconKey) {
    "restaurant" -> Icons.Filled.Restaurant
    "home" -> Icons.Filled.Home
    "account_balance" -> Icons.Filled.AccountBalance
    "directions_car" -> Icons.Filled.DirectionsCar
    "medical_services" -> Icons.Filled.MedicalServices
    "shopping_bag" -> Icons.Filled.ShoppingBag
    "movie" -> Icons.Filled.Movie
    "school" -> Icons.Filled.School
    "payments" -> Icons.Filled.Payments
    "storefront" -> Icons.Filled.Storefront
    "laptop_mac" -> Icons.Filled.LaptopMac
    "trending_up" -> Icons.AutoMirrored.Filled.TrendingUp
    "add_card" -> Icons.Filled.AddCard
    "shield" -> Icons.Filled.Shield
    "flag" -> Icons.Filled.Flag
    "savings" -> Icons.Filled.Savings
    else -> Icons.Filled.Category
  }
}
