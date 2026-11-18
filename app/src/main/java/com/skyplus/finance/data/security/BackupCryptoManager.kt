package com.skyplus.finance.data.security

import android.os.Build
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EncryptedBackupHeader(
  val formatVersion: Int = 1,
  val applicationId: String = "com.skyplus.finance",
  val createdAtEpochMillis: Long,
  val kdfAlgorithm: String,
  val kdfIterations: Int,
  val saltBase64: String,
  val nonceBase64: String,
  val payloadSha256: String,
  val recordCount: Int,
)

@Serializable
data class EncryptedBackupEnvelope(
  val header: EncryptedBackupHeader,
  val ciphertextBase64: String,
)

@Serializable
data class EncryptedBackupHeaderV2(
  val formatVersion: Int = 2,
  val applicationId: String = "com.skyplus.finance",
  val createdAtEpochMillis: Long,
  val deviceId: String,
  val schemaVersion: Int,
  val dbVersion: Int,
  val kdfAlgorithm: String,
  val kdfIterations: Int,
  val kekSaltBase64: String,
  val dekWrapNonceBase64: String,
  val wrappedDekBase64: String,
  val payloadNonceBase64: String,
  val payloadSha256: String,
  val recordCount: Int,
)

@Serializable
data class EncryptedBackupEnvelopeV2(
  val header: EncryptedBackupHeaderV2,
  val ciphertextBase64: String,
)

data class BackupEnvelopeMetadata(
  val formatVersion: Int,
  val createdAtEpochMillis: Long,
  val schemaVersion: Int,
  val dbVersion: Int,
  val deviceId: String?,
  val recordCount: Int,
  val payloadSha256: String,
)

/**
 * Portable AES-256-GCM encrypted backup engine with PBKDF2 passphrase derivation and
 * authenticated header metadata (AAD). Supports both v1 (direct KDF 120k) and
 * v2 (wrapped 256-bit DEK with 210k PBKDF2-HMAC-SHA256) envelopes for full backward compatibility.
 */
@OptIn(ExperimentalEncodingApi::class)
class BackupCryptoManager {
  companion object {
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val DEK_BYTES = 32
    private const val KEY_BITS = 256
    private const val DEFAULT_V1_ITERATIONS = 120_000
    const val DEFAULT_V2_ITERATIONS = 210_000
    private const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
    private const val KDF_SHA1 = "PBKDF2WithHmacSHA1"
  }

  private val secureRandom = SecureRandom()
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun encryptBackup(
    plaintextJson: String,
    passphrase: CharArray,
    recordCount: Int,
    createdAtEpochMillis: Long = System.currentTimeMillis(),
  ): String {
    require(passphrase.size >= 8) { "Recovery passphrase must be at least 8 characters." }

    val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
    val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
    val kdfAlgo = selectKdfAlgorithm()
    val secretKey = deriveAesKey(passphrase, salt, kdfAlgo, DEFAULT_V1_ITERATIONS)

    val plaintextBytes = plaintextJson.toByteArray(StandardCharsets.UTF_8)
    val payloadHash = sha256Hex(plaintextBytes)

    val header = EncryptedBackupHeader(
      formatVersion = 1,
      applicationId = "com.skyplus.finance",
      createdAtEpochMillis = createdAtEpochMillis,
      kdfAlgorithm = kdfAlgo,
      kdfIterations = DEFAULT_V1_ITERATIONS,
      saltBase64 = encodeBase64(salt),
      nonceBase64 = encodeBase64(nonce),
      payloadSha256 = payloadHash,
      recordCount = recordCount,
    )
    val aadBytes = canonicalAadV1(header)

    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
    cipher.updateAAD(aadBytes)
    val ciphertext = cipher.doFinal(plaintextBytes)

    val envelope = EncryptedBackupEnvelope(
      header = header,
      ciphertextBase64 = encodeBase64(ciphertext),
    )
    return json.encodeToString(EncryptedBackupEnvelope.serializer(), envelope)
  }

  fun encryptBackupV2(
    plaintextJson: String,
    passphrase: CharArray,
    recordCount: Int,
    deviceId: String = "android-device",
    schemaVersion: Int = 3,
    dbVersion: Int = 4,
    createdAtEpochMillis: Long = System.currentTimeMillis(),
    iterations: Int = DEFAULT_V2_ITERATIONS,
  ): String {
    require(passphrase.size >= 8) { "Recovery passphrase must be at least 8 characters." }

    val kekSalt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
    val dekWrapNonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
    val payloadNonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
    val dek = ByteArray(DEK_BYTES).also { secureRandom.nextBytes(it) }

    return try {
      val kdfAlgo = selectKdfAlgorithm()
      val kek = deriveAesKey(passphrase, kekSalt, kdfAlgo, iterations)
      val kekSaltB64 = encodeBase64(kekSalt)
      val dekWrapNonceB64 = encodeBase64(dekWrapNonce)
      val payloadNonceB64 = encodeBase64(payloadNonce)

      val wrapCipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
      wrapCipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekWrapNonce))
      wrapCipher.updateAAD(
        dekWrapAadV2(
          applicationId = "com.skyplus.finance",
          deviceId = deviceId,
          schemaVersion = schemaVersion,
          dbVersion = dbVersion,
          kdfAlgorithm = kdfAlgo,
          kdfIterations = iterations,
          kekSaltBase64 = kekSaltB64,
          dekWrapNonceBase64 = dekWrapNonceB64,
        ),
      )
      val wrappedDekB64 = encodeBase64(wrapCipher.doFinal(dek))

      val plaintextBytes = plaintextJson.toByteArray(StandardCharsets.UTF_8)
      val payloadHash = sha256Hex(plaintextBytes)

      val header = EncryptedBackupHeaderV2(
        formatVersion = 2,
        applicationId = "com.skyplus.finance",
        createdAtEpochMillis = createdAtEpochMillis,
        deviceId = deviceId,
        schemaVersion = schemaVersion,
        dbVersion = dbVersion,
        kdfAlgorithm = kdfAlgo,
        kdfIterations = iterations,
        kekSaltBase64 = kekSaltB64,
        dekWrapNonceBase64 = dekWrapNonceB64,
        wrappedDekBase64 = wrappedDekB64,
        payloadNonceBase64 = payloadNonceB64,
        payloadSha256 = payloadHash,
        recordCount = recordCount,
      )

      val payloadCipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
      payloadCipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(dek, "AES"),
        GCMParameterSpec(GCM_TAG_LENGTH_BITS, payloadNonce),
      )
      payloadCipher.updateAAD(canonicalAadV2(header))
      val ciphertext = payloadCipher.doFinal(plaintextBytes)

      val envelope = EncryptedBackupEnvelopeV2(
        header = header,
        ciphertextBase64 = encodeBase64(ciphertext),
      )
      json.encodeToString(EncryptedBackupEnvelopeV2.serializer(), envelope)
    } finally {
      dek.fill(0)
    }
  }

  fun inspectBackupHeader(encryptedEnvelopeJson: String): Result<BackupEnvelopeMetadata> = runCatching {
    if (isV2Envelope(encryptedEnvelopeJson)) {
      val envelope = json.decodeFromString(EncryptedBackupEnvelopeV2.serializer(), encryptedEnvelopeJson)
      val h = envelope.header
      BackupEnvelopeMetadata(
        formatVersion = h.formatVersion,
        createdAtEpochMillis = h.createdAtEpochMillis,
        schemaVersion = h.schemaVersion,
        dbVersion = h.dbVersion,
        deviceId = h.deviceId,
        recordCount = h.recordCount,
        payloadSha256 = h.payloadSha256,
      )
    } else {
      val envelope = json.decodeFromString(EncryptedBackupEnvelope.serializer(), encryptedEnvelopeJson)
      val h = envelope.header
      BackupEnvelopeMetadata(
        formatVersion = h.formatVersion,
        createdAtEpochMillis = h.createdAtEpochMillis,
        schemaVersion = 2,
        dbVersion = 3,
        deviceId = null,
        recordCount = h.recordCount,
        payloadSha256 = h.payloadSha256,
      )
    }
  }

  fun decryptBackup(
    encryptedEnvelopeJson: String,
    passphrase: CharArray,
  ): String {
    return if (isV2Envelope(encryptedEnvelopeJson)) {
      decryptBackupV2(encryptedEnvelopeJson, passphrase)
    } else {
      decryptBackupV1(encryptedEnvelopeJson, passphrase)
    }
  }

  private fun isV2Envelope(envelopeJson: String): Boolean {
    return runCatching {
      val parsed = json.decodeFromString(EncryptedBackupEnvelopeV2.serializer(), envelopeJson)
      parsed.header.formatVersion == 2 && parsed.header.wrappedDekBase64.isNotBlank()
    }.getOrDefault(false)
  }

  private fun decryptBackupV1(
    encryptedEnvelopeJson: String,
    passphrase: CharArray,
  ): String {
    val envelope = json.decodeFromString(EncryptedBackupEnvelope.serializer(), encryptedEnvelopeJson)
    val header = envelope.header
    val salt = decodeBase64(header.saltBase64)
    val nonce = decodeBase64(header.nonceBase64)
    val ciphertext = decodeBase64(envelope.ciphertextBase64)

    val secretKey = deriveAesKey(passphrase, salt, header.kdfAlgorithm, header.kdfIterations)
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
    cipher.updateAAD(canonicalAadV1(header))

    val plaintextBytes = cipher.doFinal(ciphertext)
    val computedHash = sha256Hex(plaintextBytes)
    check(computedHash == header.payloadSha256) {
      "Backup integrity verification failed: SHA-256 checksum mismatch."
    }
    return String(plaintextBytes, StandardCharsets.UTF_8)
  }

  private fun decryptBackupV2(
    encryptedEnvelopeJson: String,
    passphrase: CharArray,
  ): String {
    val envelope = json.decodeFromString(EncryptedBackupEnvelopeV2.serializer(), encryptedEnvelopeJson)
    val header = envelope.header
    require(header.applicationId == "com.skyplus.finance") {
      "Backup applicationId mismatch: ${header.applicationId}"
    }
    val kekSalt = decodeBase64(header.kekSaltBase64)
    val dekWrapNonce = decodeBase64(header.dekWrapNonceBase64)
    val wrappedDek = decodeBase64(header.wrappedDekBase64)
    val payloadNonce = decodeBase64(header.payloadNonceBase64)
    val ciphertext = decodeBase64(envelope.ciphertextBase64)

    val kek = deriveAesKey(passphrase, kekSalt, header.kdfAlgorithm, header.kdfIterations)
    val unwrapCipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    unwrapCipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekWrapNonce))
    unwrapCipher.updateAAD(
      dekWrapAadV2(
        applicationId = header.applicationId,
        deviceId = header.deviceId,
        schemaVersion = header.schemaVersion,
        dbVersion = header.dbVersion,
        kdfAlgorithm = header.kdfAlgorithm,
        kdfIterations = header.kdfIterations,
        kekSaltBase64 = header.kekSaltBase64,
        dekWrapNonceBase64 = header.dekWrapNonceBase64,
      ),
    )

    val dek = unwrapCipher.doFinal(wrappedDek)
    return try {
      val payloadCipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
      payloadCipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(dek, "AES"),
        GCMParameterSpec(GCM_TAG_LENGTH_BITS, payloadNonce),
      )
      payloadCipher.updateAAD(canonicalAadV2(header))
      val plaintextBytes = payloadCipher.doFinal(ciphertext)
      val computedHash = sha256Hex(plaintextBytes)
      check(computedHash == header.payloadSha256) {
        "Backup integrity verification failed: SHA-256 checksum mismatch."
      }
      String(plaintextBytes, StandardCharsets.UTF_8)
    } finally {
      dek.fill(0)
    }
  }

  private fun selectKdfAlgorithm(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      KDF_SHA256
    } else {
      try {
        SecretKeyFactory.getInstance(KDF_SHA256)
        KDF_SHA256
      } catch (_: Exception) {
        KDF_SHA1
      }
    }
  }

  private fun deriveAesKey(
    passphrase: CharArray,
    salt: ByteArray,
    algorithm: String,
    iterations: Int,
  ): SecretKeySpec {
    val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
    return try {
      val factory = SecretKeyFactory.getInstance(algorithm)
      val keyBytes = factory.generateSecret(spec).encoded
      SecretKeySpec(keyBytes, "AES")
    } finally {
      spec.clearPassword()
    }
  }

  private fun canonicalAadV1(header: EncryptedBackupHeader): ByteArray {
    val aadString = listOf(
      header.formatVersion.toString(),
      header.applicationId,
      header.createdAtEpochMillis.toString(),
      header.kdfAlgorithm,
      header.kdfIterations.toString(),
      header.saltBase64,
      header.nonceBase64,
      header.payloadSha256,
      header.recordCount.toString(),
    ).joinToString("|")
    return aadString.toByteArray(StandardCharsets.UTF_8)
  }

  private fun dekWrapAadV2(
    applicationId: String,
    deviceId: String,
    schemaVersion: Int,
    dbVersion: Int,
    kdfAlgorithm: String,
    kdfIterations: Int,
    kekSaltBase64: String,
    dekWrapNonceBase64: String,
  ): ByteArray {
    return listOf(
      "2",
      applicationId,
      deviceId,
      schemaVersion.toString(),
      dbVersion.toString(),
      kdfAlgorithm,
      kdfIterations.toString(),
      kekSaltBase64,
      dekWrapNonceBase64,
    ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
  }

  private fun canonicalAadV2(header: EncryptedBackupHeaderV2): ByteArray {
    val aadString = listOf(
      header.formatVersion.toString(),
      header.applicationId,
      header.createdAtEpochMillis.toString(),
      header.deviceId,
      header.schemaVersion.toString(),
      header.dbVersion.toString(),
      header.kdfAlgorithm,
      header.kdfIterations.toString(),
      header.kekSaltBase64,
      header.dekWrapNonceBase64,
      header.wrappedDekBase64,
      header.payloadNonceBase64,
      header.payloadSha256,
      header.recordCount.toString(),
    ).joinToString("|")
    return aadString.toByteArray(StandardCharsets.UTF_8)
  }

  private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

  private fun decodeBase64(value: String): ByteArray = Base64.decode(value)
}
