package com.skyplus.finance.data.database

import androidx.room.TypeConverter
import com.skyplus.finance.domain.model.CategoryType
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.SavingsGoalType
import com.skyplus.finance.domain.model.TransactionType
import com.skyplus.finance.util.DecimalUtils
import java.math.BigDecimal

class Converters {
  @TypeConverter
  fun fromBigDecimal(value: BigDecimal?): String? =
    value?.let { DecimalUtils.toCanonicalString(it) }

  @TypeConverter
  fun toBigDecimal(value: String?): BigDecimal? =
    value?.let { DecimalUtils.fromStringOrZero(it) }

  @TypeConverter
  fun fromTransactionType(type: TransactionType): String = type.name

  @TypeConverter
  fun toTransactionType(name: String): TransactionType = TransactionType.valueOf(name)

  @TypeConverter
  fun fromCategoryType(type: CategoryType): String = type.name

  @TypeConverter
  fun toCategoryType(name: String): CategoryType = CategoryType.valueOf(name)

  @TypeConverter
  fun fromLoanStatus(status: LoanStatus): String = status.name

  @TypeConverter
  fun toLoanStatus(name: String): LoanStatus = LoanStatus.valueOf(name)

  @TypeConverter
  fun fromSavingsGoalType(type: SavingsGoalType): String = type.name

  @TypeConverter
  fun toSavingsGoalType(name: String): SavingsGoalType = SavingsGoalType.valueOf(name)
}
