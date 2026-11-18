package com.skyplus.finance.data.security

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object SqlCipherSupportFactoryProvider {
  @Volatile
  private var nativeLoaded = false

  fun ensureNativeLibraryLoaded() {
    if (!nativeLoaded) {
      synchronized(this) {
        if (!nativeLoaded) {
          System.loadLibrary("sqlcipher")
          nativeLoaded = true
        }
      }
    }
  }

  fun createEncryptedFactory(keystoreCryptoManager: KeystoreCryptoManager): SupportSQLiteOpenHelper.Factory {
    ensureNativeLibraryLoaded()
    val passphrase = keystoreCryptoManager.getOrCreateDatabasePassphrase()
    return SupportOpenHelperFactory(passphrase, null, false)
  }
}
