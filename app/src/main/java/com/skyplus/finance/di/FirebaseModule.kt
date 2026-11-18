package com.skyplus.finance.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

  @Provides
  @Singleton
  fun provideFirebaseApp(
    @ApplicationContext context: Context,
  ): FirebaseApp? {
    return runCatching {
      FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
    }.getOrNull()
  }

  @Provides
  @Singleton
  fun provideFirebaseAuth(
    firebaseApp: FirebaseApp?,
  ): FirebaseAuth? {
    if (firebaseApp == null) return null
    return runCatching { FirebaseAuth.getInstance(firebaseApp) }.getOrNull()
  }

  @Provides
  @Singleton
  fun provideFirebaseFirestore(
    @ApplicationContext context: Context,
    firebaseApp: FirebaseApp?,
  ): FirebaseFirestore? {
    if (firebaseApp == null) return null
    return runCatching {
      val dbResId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
      val customDbId = if (dbResId != 0) context.getString(dbResId).trim() else ""
      val firestore = if (customDbId.isNotEmpty() && !customDbId.equals("(default)", ignoreCase = true)) {
        FirebaseFirestore.getInstance(firebaseApp, customDbId)
      } else {
        FirebaseFirestore.getInstance(firebaseApp)
      }
      runCatching {
        val settings = FirebaseFirestoreSettings.Builder()
          .setLocalCacheSettings(
            PersistentCacheSettings.newBuilder()
              .setSizeBytes(40L * 1024L * 1024L)
              .build(),
          )
          .build()
        firestore.firestoreSettings = settings
      }
      firestore
    }.getOrNull()
  }
}
