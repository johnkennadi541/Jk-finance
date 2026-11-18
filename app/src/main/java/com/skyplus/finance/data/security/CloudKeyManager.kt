package com.skyplus.finance.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WrappedRecoveryDekEnvelope(
  val version: Int = 2,
  val ownerUid: String,
  val kdfAlgorithm: String = CloudKeyManager.KDF_SHA256,
  val kdfIterations: Int = CloudKeyManager.PBKDF2_ITERATIONS,
  val saltBase64: String,
  val nonceBase64: String,
  val wrappedDekBase64: String,
  val keyCheckHash: String,
  val deviceId: String,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
)

data class EncryptedPayloadResult(
  val ciphertextBase64: String,
  val nonceBase64: String,
)

@Serializable
private data class LocalWrappedDekFilePayload(
  val version: Int = 1,
  val ivBase64: String,
  val ciphertextBase64: String,
  val isStrongBoxBacked: Boolean = false,
)

interface LocalDekProtector {
  fun saveWrappedDek(uid: String, rawDek: ByteArray)
  fun loadWrappedDek(uid: String): ByteArray?
  fun hasWrappedDek(uid: String): Boolean
  fun clearWrappedDek(uid: String)
  fun clearAll()
}

class InMemoryDekProtector : LocalDekProtector {
  private val storage = ConcurrentHashMap<String, ByteArray>()

  override fun saveWrappedDek(uid: String, rawDek: ByteArray) {
    storage[uid] = rawDek.copyOf()
  }

  override fun loadWrappedDek(uid: String): ByteArray? = storage[uid]?.copyOf()

  override fun hasWrappedDek(uid: String): Boolean = storage.containsKey(uid)

  override fun clearWrappedDek(uid: String) {
    storage.remove(uid)?.fill(0)
  }

  override fun clearAll() {
    storage.values.forEach { it.fill(0) }
    storage.clear()
  }
}

class AndroidKeystoreDekProtector(
  private val context: Context,
) : LocalDekProtector {
  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "skyplus_finance_cloud_dek_wrap_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun dekFileForUid(uid: String): File {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(uid.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(24)
    return File(context.noBackupFilesDir, "skyplus_wrapped_cloud_dek_$digest.json")
  }

  @Synchronized
  override fun saveWrappedDek(uid: String, rawDek: ByteArray) {
    val (secretKey, usedStrongBox) = getOrCreateKeystoreSecretKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val ciphertext = cipher.doFinal(rawDek)
    val iv = cipher.iv

    val filePayload = LocalWrappedDekFilePayload(
      version = 1,
      ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
      ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
      isStrongBoxBacked = usedStrongBox,
    )
    val targetFile = dekFileForUid(uid)
    targetFile.parentFile?.mkdirs()
    targetFile.writeText(json.encodeToString(LocalWrappedDekFilePayload.serializer(), filePayload))
  }

  @Synchronized
  override fun loadWrappedDek(uid: String): ByteArray? {
    val targetFile = dekFileForUid(uid)
    if (!targetFile.exists()) return null
    val secretKey = loadExistingSecretKey() ?: return null
    val filePayload = runCatching {
      json.decodeFromString<LocalWrappedDekFilePayload>(targetFile.readText())
    }.getOrNull() ?: return null

    return runCatching {
      val iv = Base64.decode(filePayload.ivBase64, Base64.NO_WRAP)
      val ciphertext = Base64.decode(filePayload.ciphertextBase64, Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
      cipher.doFinal(ciphertext)
    }.getOrNull()
  }

  override fun hasWrappedDek(uid: String): Boolean = dekFileForUid(uid).exists()

  @Synchronized
  override fun clearWrappedDek(uid: String) {
    val target = dekFileForUid(uid)
    if (target.exists()) {
      target.delete()
    }
  }

  @Synchronized
  override fun clearAll() {
    context.noBackupFilesDir
      ?.listFiles { _, name -> name.startsWith("skyplus_wrapped_cloud_dek_") && name.endsWith(".json") }
      ?.forEach { it.delete() }
  }

  private fun loadExistingSecretKey(): SecretKey? {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    return entry?.secretKey
  }

  private fun getOrCreateKeystoreSecretKey(): Pair<SecretKey, Boolean> {
    loadExistingSecretKey()?.let { return it to false }
    val supportsStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    if (supportsStrongBox) {
      try {
        return generateAesKey(useStrongBox = true) to true
      } catch (_: StrongBoxUnavailableException) {
      } catch (_: Exception) {
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
}

/**
 * Cross-device zero-knowledge DEK (Data Encryption Key) manager for Option C Firestore sync:
 * - Random 256-bit DEK generated per authenticated user account.
 * - DEK is wrapped via PBKDF2-HMAC-SHA256 (210,000 iterations) + AES-256-GCM + AAD using the
 *   user-controlled Recovery Passphrase for cross-device recovery (envelope stored in Firestore).
 * - DEK is wrapped locally via Android Keystore for unattended WorkManager sync while unlocked.
 * - Recovery Passphrase and raw Keystore keys never leave the device.
 */
@Singleton
class CloudKeyManager internal constructor(
  private val localDekProtector: LocalDekProtector,
  private val pbkdf2Iterations: Int = PBKDF2_ITERATIONS,
) {
  @Inject
  constructor(@ApplicationContext context: Context) : this(
    localDekProtector = AndroidKeystoreDekProtector(context),
    pbkdf2Iterations = PBKDF2_ITERATIONS,
  )

  companion object {
    const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 210_000
    const val MIN_PASSPHRASE_LENGTH = 8
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val DEK_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256

    fun createForTests(
      localDekProtector: LocalDekProtector = InMemoryDekProtector(),
      pbkdf2Iterations: Int = PBKDF2_ITERATIONS,
    ): CloudKeyManager = CloudKeyManager(
      localDekProtector = localDekProtector,
      pbkdf2Iterations = pbkdf2Iterations,
    )
  }

  private val secureRandom = SecureRandom()
  private val unlockedDekCache = ConcurrentHashMap<String, ByteArray>()

  fun hasLocalUnlockedDek(uid: String): Boolean {
    if (uid.isBlank()) return false
    if (unlockedDekCache.containsKey(uid)) return true
    val loaded = runCatching { localDekProtector.loadWrappedDek(uid) }.getOrNull() ?: return false
    if (loaded.size != DEK_BYTES) {
      loaded.fill(0)
      return false
    }
    unlockedDekCache[uid] = loaded
    return true
  }

  fun setupRecoveryPassphrase(
    uid: String,
    passphrase: CharArray,
    deviceId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
  ): Result<WrappedRecoveryDekEnvelope> = runCatching {
    require(uid.isNotBlank()) { "Authenticated UID is required to initialize recovery encryption." }
    require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
      "Recovery passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
    }

    val dek = ByteArray(DEK_BYTES).also { secureRandom.nextBytes(it) }
    try {
      val envelope = wrapDekForRecovery(
        uid = uid,
        dek = dek,
        passphrase = passphrase,
        deviceId = deviceId,
        createdAtEpochMillis = nowEpochMillis,
        updatedAtEpochMillis = nowEpochMillis,
      )
      localDekProtector.saveWrappedDek(uid, dek)
      unlockedDekCache[uid] = dek.copyOf()
      envelope
    } finally {
      dek.fill(0)
    }
  }

  fun unlockDekWithPassphrase(
    uid: String,
    envelope: WrappedRecoveryDekEnvelope,
    passphrase: CharArray,
  ): Result<Unit> = runCatching {
    require(uid.isNotBlank()) { "Authenticated UID is required." }
    require(envelope.ownerUid == uid) { "Recovery envelope does not belong to the active account." }
    require(passphrase.isNotEmpty()) { "Enter your Recovery Passphrase." }

    val salt = java.util.Base64.getDecoder().decode(envelope.saltBase64)
    val nonce = java.util.Base64.getDecoder().decode(envelope.nonceBase64)
    val wrappedDek = java.util.Base64.getDecoder().decode(envelope.wrappedDekBase64)

    val kek = deriveKek(passphrase, salt, envelope.kdfAlgorithm, envelope.kdfIterations)
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
    cipher.updateAAD(
      recoveryWrapAad(
        uid = envelope.ownerUid,
        version = envelope.version,
        kdfAlgorithm = envelope.kdfAlgorithm,
        iterations = envelope.kdfIterations,
        saltBase64 = envelope.saltBase64,
        nonceBase64 = envelope.nonceBase64,
      ),
    )

    val dek = try {
      cipher.doFinal(wrappedDek)
    } catch (e: Exception) {
      throw IllegalArgumentException("Incorrect Recovery Passphrase or corrupted recovery metadata.", e)
    }

    try {
      require(dek.size == DEK_BYTES) { "Invalid DEK length." }
      val expectedCheck = computeKeyCheckHash(uid, dek)
      check(MessageDigest.isEqual(
        expectedCheck.toByteArray(StandardCharsets.UTF_8),
        envelope.keyCheckHash.toByteArray(StandardCharsets.UTF_8),
      )) {
        "Recovery key check hash mismatch."
      }
      localDekProtector.saveWrappedDek(uid, dek)
      unlockedDekCache[uid] = dek.copyOf()
    } finally {
      dek.fill(0)
    }
  }

  fun rotateRecoveryPassphrase(
    uid: String,
    currentEnvelope: WrappedRecoveryDekEnvelope,
    oldPassphrase: CharArray,
    newPassphrase: CharArray,
    deviceId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
  ): Result<WrappedRecoveryDekEnvelope> = runCatching {
    require(newPassphrase.size >= MIN_PASSPHRASE_LENGTH) {
      "New recovery passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
    }
    unlockDekWithPassphrase(uid, currentEnvelope, oldPassphrase).getOrThrow()
    val dek = getOrLoadDek(uid)
    val rotated = wrapDekForRecovery(
      uid = uid,
      dek = dek,
      passphrase = newPassphrase,
      deviceId = deviceId,
      createdAtEpochMillis = currentEnvelope.createdAtEpochMillis,
      updatedAtEpochMillis = nowEpochMillis,
    )
    rotated
  }

  fun encryptPayload(
    uid: String,
    plaintextJson: String,
    aad: String,
  ): Result<EncryptedPayloadResult> = runCatching {
    val dek = getOrLoadDek(uid)
    val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
    val keySpec = SecretKeySpec(dek, "AES")
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
    cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
    val ciphertext = cipher.doFinal(plaintextJson.toByteArray(StandardCharsets.UTF_8))
    EncryptedPayloadResult(
      ciphertextBase64 = java.util.Base64.getEncoder().encodeToString(ciphertext),
      nonceBase64 = java.util.Base64.getEncoder().encodeToString(nonce),
    )
  }

  fun decryptPayload(
    uid: String,
    ciphertextBase64: String,
    nonceBase64: String,
    aad: String,
  ): Result<String> = runCatching {
    val dek = getOrLoadDek(uid)
    val nonce = java.util.Base64.getDecoder().decode(nonceBase64)
    val ciphertext = java.util.Base64.getDecoder().decode(ciphertextBase64)
    val keySpec = SecretKeySpec(dek, "AES")
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
    cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
    val plaintextBytes = cipher.doFinal(ciphertext)
    String(plaintextBytes, StandardCharsets.UTF_8)
  }

  fun clearLocalDek(uid: String) {
    unlockedDekCache.remove(uid)?.fill(0)
    localDekProtector.clearWrappedDek(uid)
  }

  fun clearAllLocalDeks() {
    unlockedDekCache.values.forEach { it.fill(0) }
    unlockedDekCache.clear()
    localDekProtector.clearAll()
  }

  private fun getOrLoadDek(uid: String): ByteArray {
    unlockedDekCache[uid]?.let { return it }
    val loaded = localDekProtector.loadWrappedDek(uid)
      ?: error("Cloud encryption key is locked for this account. Enter your Recovery Passphrase to unlock sync.")
    require(loaded.size == DEK_BYTES) { "Stored cloud DEK is invalid." }
    unlockedDekCache[uid] = loaded
    return loaded
  }

  private fun wrapDekForRecovery(
    uid: String,
    dek: ByteArray,
    passphrase: CharArray,
    deviceId: String,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
  ): WrappedRecoveryDekEnvelope {
    val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
    val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
    val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
    val nonceB64 = java.util.Base64.getEncoder().encodeToString(nonce)
    val kek = deriveKek(passphrase, salt, KDF_SHA256, pbkdf2Iterations)

    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
    cipher.updateAAD(
      recoveryWrapAad(
        uid = uid,
        version = 2,
        kdfAlgorithm = KDF_SHA256,
        iterations = pbkdf2Iterations,
        saltBase64 = saltB64,
        nonceBase64 = nonceB64,
      ),
    )
    val wrappedDek = cipher.doFinal(dek)
    return WrappedRecoveryDekEnvelope(
      version = 2,
      ownerUid = uid,
      kdfAlgorithm = KDF_SHA256,
      kdfIterations = pbkdf2Iterations,
      saltBase64 = saltB64,
      nonceBase64 = nonceB64,
      wrappedDekBase64 = java.util.Base64.getEncoder().encodeToString(wrappedDek),
      keyCheckHash = computeKeyCheckHash(uid, dek),
      deviceId = deviceId,
      createdAtEpochMillis = createdAtEpochMillis,
      updatedAtEpochMillis = updatedAtEpochMillis,
    )
  }

  private fun deriveKek(
    passphrase: CharArray,
    salt: ByteArray,
    algorithm: String,
    iterations: Int,
  ): SecretKeySpec {
    val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
    return try {
      val factory = SecretKeyFactory.getInstance(algorithm)
      val bytes = factory.generateSecret(spec).encoded
      SecretKeySpec(bytes, "AES")
    } finally {
      spec.clearPassword()
    }
  }

  private fun recoveryWrapAad(
    uid: String,
    version: Int,
    kdfAlgorithm: String,
    iterations: Int,
    saltBase64: String,
    nonceBase64: String,
  ): ByteArray {
    return listOf(
      "SKYPLUS_RECOVERY_WRAP",
      version.toString(),
      uid,
      kdfAlgorithm,
      iterations.toString(),
      saltBase64,
      nonceBase64,
    ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
  }

  private fun computeKeyCheckHash(uid: String, dek: ByteArray): String {
    val dekB64 = java.util.Base64.getEncoder().encodeToString(dek)
    val input = "SKYPLUS_DEK_CHECK:$uid:$dekB64".toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
  }
}
