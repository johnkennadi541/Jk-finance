package com.skyplus.finance.di

import com.skyplus.finance.data.cloud.CloudSyncRemoteDataSource
import com.skyplus.finance.data.cloud.FirestoreSyncRemoteDataSource
import com.skyplus.finance.data.export.FinancialExportManager
import com.skyplus.finance.data.repository.OfflineFinanceRepository
import com.skyplus.finance.domain.repository.CloudBackupGateway
import com.skyplus.finance.domain.repository.FinanceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

  @Binds
  @Singleton
  abstract fun bindFinanceRepository(
    impl: OfflineFinanceRepository,
  ): FinanceRepository

  @Binds
  @Singleton
  abstract fun bindCloudBackupGateway(
    impl: FinancialExportManager,
  ): CloudBackupGateway

  @Binds
  @Singleton
  abstract fun bindCloudSyncRemoteDataSource(
    impl: FirestoreSyncRemoteDataSource,
  ): CloudSyncRemoteDataSource
}
