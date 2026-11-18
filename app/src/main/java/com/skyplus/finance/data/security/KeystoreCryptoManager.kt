package com.skyplus.finance.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KeystoreInitializationException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
private data class WrappedKeyPayload(
  val version: Int = 1,
  val ivBase64: String,
  val ciphertextBase64: String,
  val isStrongBoxBacked: Boolean = false,
)

/**
 * Manages the AES-256 SQLCipher database passphrase wrapped by an Android Keystore AES-256-GCM key.
 * - Prefers StrongBox hardware protection when available on API 28+.
 * - Safely falls back to standard TEE Android Keystore if StrongBox is unavailable.
 * - Never stores plaintext passphrases on disk.
 */
class KeystoreCryptoManager(
  private val context: Context,
) {
  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "skyplus_finance_db_master_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val PASSPHRASE_BYTES = 32
    private const val KEY_FILE_NAME = "skyplus_wrapped_db_key.json"
  }

  private val json = Json { ignoreUnknownKeys = true }
  private val keyFile: File
    get() = File(context.noBackupFilesDir, KEY_FILE_NAME)

  @Synchronized
  fun getOrCreateDatabasePassphrase(): ByteArray {
    return try {
      if (keyFile.exists()) {
        unwrapPassphrase()
      } else {
        createAndWrapPassphrase()
      }
    } catch (e: KeystoreInitializationException) {
      throw e
    } catch (e: Exception) {
      throw KeystoreInitializationException(
        "Failed to initialize encrypted storage key: ${e.message}",
        e,
      )
    }
  }

  fun isKeyInitialized(): Boolean = keyFile.exists()

  fun isStrongBoxHardwareSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
  }

  fun isCurrentKeyStrongBoxBacked(): Boolean {
    if (!keyFile.exists()) return false
    return runCatching {
      val payload = json.decodeFromString<WrappedKeyPayload>(keyFile.readText())
      payload.isStrongBoxBacked
    }.getOrDefault(false)
  }

  private fun createAndWrapPassphrase(): ByteArray {
    val (secretKey, usedStrongBox) = getOrCreateKeystoreSecretKey()
    val rawKey = ByteArray(PASSPHRASE_BYTES)
    SecureRandom().nextBytes(rawKey)

    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val encryptedBytes = cipher.doFinal(rawKey)
    val iv = cipher.iv

    val payload = WrappedKeyPayload(
      version = 1,
      ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
      ciphertextBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
      isStrongBoxBacked = usedStrongBox,
    )
    keyFile.parentFile?.mkdirs()
    keyFile.writeText(json.encodeToString(WrappedKeyPayload.serializer(), payload))

    return toHexBytes(rawKey).also {
      rawKey.fill(0)
    }
  }

  private fun unwrapPassphrase(): ByteArray {
    val payload = json.decodeFromString<WrappedKeyPayload>(keyFile.readText())
    val secretKey = loadExistingSecretKey()
      ?: throw KeystoreInitializationException(
        "Android Keystore master key is missing while encrypted key envelope exists. Refusing to overwrite financial data."
      )

    val iv = Base64.decode(payload.ivBase64, Base64.NO_WRAP)
    val ciphertext = Base64.decode(payload.ciphertextBase64, Base64.NO_WRAP)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
    val rawKey = cipher.doFinal(ciphertext)

    return toHexBytes(rawKey).also {
      rawKey.fill(0)
    }
  }

  private fun loadExistingSecretKey(): SecretKey? {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    return entry?.secretKey
  }

  private fun getOrCreateKeystoreSecretKey(): Pair<SecretKey, Boolean> {
    loadExistingSecretKey()?.let { return it to isCurrentKeyStrongBoxBacked() }

    val tryStrongBox = isStrongBoxHardwareSupported()
    if (tryStrongBox) {
      try {
        return generateAesKey(useStrongBox = true) to true
      } catch (_: StrongBoxUnavailableException) {
        // Safe fallback to TEE-backed Android Keystore
      } catch (_: Exception) {
        // Some devices throw ProviderException instead of StrongBoxUnavailableException
      }
    }
    return generateAesKey(useStrongBox = false) to false
  }

  private fun generateAesKey(useStrongBox: Boolean): SecretKey {
    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val builder = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)

    if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      builder.setIsStrongBoxBacked(true)
    }

    keyGenerator.init(builder.build())
    return keyGenerator.generateKey()
  }

  private fun toHexBytes(raw: ByteArray): ByteArray {
    val hexChars = "0123456789abcdef".toCharArray()
    val out = ByteArray(raw.size * 2)
    for (i in raw.indices) {
      val v = raw[i].toInt() and 0xFF
      out[i * 2] = hexChars[v ushr 4].code.toByte()
      out[i * 2 + 1] = hexChars[v and 0x0F].code.toByte()
    }
    return out
  }
}
