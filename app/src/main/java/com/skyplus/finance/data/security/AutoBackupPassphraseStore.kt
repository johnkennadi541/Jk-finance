package com.skyplus.finance.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class WrappedPassphrasePayload(
  val ivBase64: String,
  val ciphertextBase64: String,
)

/**
 * Hardware/Keystore-backed cache for automatic scheduled Google Drive backups ONLY when the user
 * explicitly enables Drive Auto-Backup. Wiped immediately on Auto-Backup disable or sign-out.
 * Never transmitted off device and never used as cloud recovery authority.
 */
@Singleton
class AutoBackupPassphraseStore @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "skyplus_drive_autobackup_passphrase_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun storageFileForUid(uid: String): File {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(uid.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(24)
    return File(context.noBackupFilesDir, "skyplus_drive_autobackup_$digest.json")
  }

  @Synchronized
  fun savePassphrase(uid: String, passphrase: CharArray) {
    if (uid.isBlank() || passphrase.size < CloudKeyManager.MIN_PASSPHRASE_LENGTH) return
    val secretKey = getOrCreateKeystoreSecretKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val bytes = String(passphrase).toByteArray(StandardCharsets.UTF_8)
    val ciphertext = try {
      cipher.doFinal(bytes)
    } finally {
      bytes.fill(0)
    }
    val payload = WrappedPassphrasePayload(
      ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
      ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
    )
    val target = storageFileForUid(uid)
    target.parentFile?.mkdirs()
    target.writeText(json.encodeToString(WrappedPassphrasePayload.serializer(), payload))
  }

  @Synchronized
  fun loadPassphrase(uid: String): CharArray? {
    if (uid.isBlank()) return null
    val target = storageFileForUid(uid)
    if (!target.exists()) return null
    val key = loadExistingKey() ?: return null
    val payload = runCatching {
      json.decodeFromString<WrappedPassphrasePayload>(target.readText())
    }.getOrNull() ?: return null

    return runCatching {
      val iv = Base64.decode(payload.ivBase64, Base64.NO_WRAP)
      val ciphertext = Base64.decode(payload.ciphertextBase64, Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
      val plaintext = cipher.doFinal(ciphertext)
      val str = String(plaintext, StandardCharsets.UTF_8)
      plaintext.fill(0)
      str.toCharArray()
    }.getOrNull()
  }

  fun hasPassphrase(uid: String): Boolean = uid.isNotBlank() && storageFileForUid(uid).exists()

  @Synchronized
  fun clearPassphrase(uid: String) {
    if (uid.isBlank()) return
    val target = storageFileForUid(uid)
    if (target.exists()) {
      target.delete()
    }
  }

  private fun loadExistingKey(): SecretKey? {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    return entry?.secretKey
  }

  private fun getOrCreateKeystoreSecretKey(): SecretKey {
    loadExistingKey()?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)
      .build()
    generator.init(spec)
    return generator.generateKey()
  }
}
