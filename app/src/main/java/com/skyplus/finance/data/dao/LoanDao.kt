package com.skyplus.finance.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanDao {
  @Query("SELECT * FROM loans ORDER BY status ASC, nextDueDateEpochDay ASC, id ASC")
  fun observeAllLoans(): Flow<List<LoanEntity>>

  @Query("SELECT * FROM loans ORDER BY id ASC")
  suspend fun getAllLoansOnce(): List<LoanEntity>

  @Query("SELECT * FROM loans WHERE id = :id")
  suspend fun getLoanById(id: Long): LoanEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertLoan(loan: LoanEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllLoans(loans: List<LoanEntity>)

  @Update
  suspend fun updateLoan(loan: LoanEntity)

  @Delete
  suspend fun deleteLoan(loan: LoanEntity)

  @Query("SELECT * FROM emi_payments WHERE loanId = :loanId ORDER BY paymentDateEpochDay DESC, id DESC")
  fun observePaymentsForLoan(loanId: Long): Flow<List<EmiPaymentEntity>>

  @Query("SELECT * FROM emi_payments ORDER BY paymentDateEpochDay DESC, id DESC")
  fun observeAllEmiPayments(): Flow<List<EmiPaymentEntity>>

  @Query("SELECT * FROM emi_payments ORDER BY paymentDateEpochDay DESC, id DESC")
  suspend fun getAllEmiPaymentsOnce(): List<EmiPaymentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEmiPayment(payment: EmiPaymentEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAllEmiPayments(payments: List<EmiPaymentEntity>)

  @Query("DELETE FROM emi_payments")
  suspend fun deleteAllEmiPayments()

  @Query("DELETE FROM loans")
  suspend fun deleteAllLoans()
}
