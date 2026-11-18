package com.skyplus.finance.di

import android.content.Context
import androidx.room.Room
import com.skyplus.finance.data.database.DatabaseMigrations
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.security.BackupCryptoManager
import com.skyplus.finance.data.security.KeystoreCryptoManager
import com.skyplus.finance.data.security.SqlCipherSupportFactoryProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

  @Provides
  @Singleton
  fun provideKeystoreCryptoManager(
    @ApplicationContext context: Context,
  ): KeystoreCryptoManager = KeystoreCryptoManager(context)

  @Provides
  @Singleton
  fun provideBackupCryptoManager(): BackupCryptoManager = BackupCryptoManager()

  @Provides
  @Singleton
  fun provideSkyPlusDatabase(
    @ApplicationContext context: Context,
    keystoreCryptoManager: KeystoreCryptoManager,
  ): SkyPlusDatabase {
    val factory = SqlCipherSupportFactoryProvider.createEncryptedFactory(keystoreCryptoManager)
    return Room.databaseBuilder(
      context,
      SkyPlusDatabase::class.java,
      SkyPlusDatabase.DATABASE_NAME,
    )
      .openHelperFactory(factory)
      .addMigrations(*DatabaseMigrations.allMigrations())
      .build()
  }
}
