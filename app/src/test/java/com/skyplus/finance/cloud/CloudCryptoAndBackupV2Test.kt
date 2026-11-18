package com.skyplus.finance.cloud

import com.skyplus.finance.data.security.BackupCryptoManager
import com.skyplus.finance.data.security.CloudKeyManager
import com.skyplus.finance.data.security.InMemoryDekProtector
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCryptoAndBackupV2Test {

  @Test
  fun `cloud key manager wraps 256 bit dek with pbkdf2 210k and rejects wrong passphrase or tampered aad`() {
    val keyManager = CloudKeyManager.createForTests(
      localDekProtector = InMemoryDekProtector(),
      pbkdf2Iterations = CloudKeyManager.PBKDF2_ITERATIONS,
    )
    val uid = "user-alpha-001"
    val passphrase = "StrongRecoveryPass!2026".toCharArray()

    val envelope = keyManager.setupRecoveryPassphrase(
      uid = uid,
      passphrase = passphrase,
      deviceId = "pixel-8",
    ).getOrThrow()

    assertEquals(2, envelope.version)
    assertEquals(210_000, envelope.kdfIterations)
    assertEquals(CloudKeyManager.KDF_SHA256, envelope.kdfAlgorithm)
    assertTrue(keyManager.hasLocalUnlockedDek(uid))

    val plaintext = """{"description":"Monthly Salary","amount":"75000.00"}"""
    val aad = "$uid|TRANSACTION|rec-100|1"
    val encrypted = keyManager.encryptPayload(uid, plaintext, aad).getOrThrow()
    assertFalse(encrypted.ciphertextBase64.contains("Monthly Salary"))

    // Simulate a second device unlocking the same cloud envelope
    val device2Protector = InMemoryDekProtector()
    val device2KeyManager = CloudKeyManager.createForTests(
      localDekProtector = device2Protector,
      pbkdf2Iterations = CloudKeyManager.PBKDF2_ITERATIONS,
    )
    assertFalse(device2KeyManager.hasLocalUnlockedDek(uid))
    device2KeyManager.unlockDekWithPassphrase(uid, envelope, "StrongRecoveryPass!2026".toCharArray()).getOrThrow()
    assertTrue(device2KeyManager.hasLocalUnlockedDek(uid))

    val decryptedOnDevice2 = device2KeyManager.decryptPayload(
      uid = uid,
      ciphertextBase64 = encrypted.ciphertextBase64,
      nonceBase64 = encrypted.nonceBase64,
      aad = aad,
    ).getOrThrow()
    assertEquals(plaintext, decryptedOnDevice2)

    // Wrong passphrase rejected
    val wrongPassResult = device2KeyManager.unlockDekWithPassphrase(
      uid = uid,
      envelope = envelope,
      passphrase = "WrongPassphrase123".toCharArray(),
    )
    assertTrue(wrongPassResult.isFailure)

    // Wrong account UID rejected
    val wrongUidResult = device2KeyManager.unlockDekWithPassphrase(
      uid = "different-user-999",
      envelope = envelope,
      passphrase = "StrongRecoveryPass!2026".toCharArray(),
    )
    assertTrue(wrongUidResult.isFailure)

    // Tampered AAD rejected by GCM authentication tag
    val tamperedAadResult = device2KeyManager.decryptPayload(
      uid = uid,
      ciphertextBase64 = encrypted.ciphertextBase64,
      nonceBase64 = encrypted.nonceBase64,
      aad = "$uid|TRANSACTION|rec-999|1",
    )
    assertTrue(tamperedAadResult.isFailure)

    // Tampered ciphertext rejected
    val rawCipherBytes = Base64.getDecoder().decode(encrypted.ciphertextBase64)
    rawCipherBytes[0] = (rawCipherBytes[0].toInt() xor 0xFF).toByte()
    val tamperedCipherB64 = Base64.getEncoder().encodeToString(rawCipherBytes)
    val tamperedCipherResult = device2KeyManager.decryptPayload(
      uid = uid,
      ciphertextBase64 = tamperedCipherB64,
      nonceBase64 = encrypted.nonceBase64,
      aad = aad,
    )
    assertTrue(tamperedCipherResult.isFailure)

    // Passphrase rotation re-wraps DEK without invalidating existing encrypted records
    val rotated = device2KeyManager.rotateRecoveryPassphrase(
      uid = uid,
      currentEnvelope = envelope,
      oldPassphrase = "StrongRecoveryPass!2026".toCharArray(),
      newPassphrase = "RotatedNewPassphrase!99".toCharArray(),
      deviceId = "pixel-9",
    ).getOrThrow()
    assertNotEquals(envelope.wrappedDekBase64, rotated.wrappedDekBase64)

    val device3KeyManager = CloudKeyManager.createForTests(
      localDekProtector = InMemoryDekProtector(),
      pbkdf2Iterations = CloudKeyManager.PBKDF2_ITERATIONS,
    )
    // Old passphrase no longer opens rotated envelope
    assertTrue(
      device3KeyManager.unlockDekWithPassphrase(uid, rotated, "StrongRecoveryPass!2026".toCharArray()).isFailure,
    )
    // New passphrase opens rotated envelope AND decrypts previously encrypted payload
    device3KeyManager.unlockDekWithPassphrase(uid, rotated, "RotatedNewPassphrase!99".toCharArray()).getOrThrow()
    val decryptedAfterRotation = device3KeyManager.decryptPayload(
      uid = uid,
      ciphertextBase64 = encrypted.ciphertextBase64,
      nonceBase64 = encrypted.nonceBase64,
      aad = aad,
    ).getOrThrow()
    assertEquals(plaintext, decryptedAfterRotation)
  }

  @Test
  fun `backup crypto manager v1 and v2 envelopes round trip and reject tampering`() {
    val crypto = BackupCryptoManager()
    val passphrase = "BackupVault!2026".toCharArray()
    val payloadJson = """{"backupSchemaVersion":3,"transactions":[{"description":"Apartment Rent","amount":"25000.00"}]}"""

    // v1 backward compatibility
    val v1Envelope = crypto.encryptBackup(payloadJson, passphrase, recordCount = 1)
    assertFalse(v1Envelope.contains("Apartment Rent"))
    val v1Meta = crypto.inspectBackupHeader(v1Envelope).getOrThrow()
    assertEquals(1, v1Meta.formatVersion)
    assertEquals(1, v1Meta.recordCount)
    assertEquals(payloadJson, crypto.decryptBackup(v1Envelope, "BackupVault!2026".toCharArray()))

    // v2 DEK-wrapped envelope
    val v2Envelope = crypto.encryptBackupV2(
      plaintextJson = payloadJson,
      passphrase = "BackupVault!2026".toCharArray(),
      recordCount = 1,
      deviceId = "test-device-1",
      schemaVersion = 3,
      dbVersion = 4,
    )
    assertFalse(v2Envelope.contains("Apartment Rent"))
    val v2Meta = crypto.inspectBackupHeader(v2Envelope).getOrThrow()
    assertEquals(2, v2Meta.formatVersion)
    assertEquals(4, v2Meta.dbVersion)
    assertEquals("test-device-1", v2Meta.deviceId)
    assertEquals(payloadJson, crypto.decryptBackup(v2Envelope, "BackupVault!2026".toCharArray()))

    // Tampered v2 header metadata breaks AAD validation
    val tamperedHeaderEnvelope = v2Envelope.replace("\"recordCount\":1", "\"recordCount\":99")
    var tamperedFailed = false
    try {
      crypto.decryptBackup(tamperedHeaderEnvelope, "BackupVault!2026".toCharArray())
    } catch (_: Exception) {
      tamperedFailed = true
    }
    assertTrue(tamperedFailed)
  }
}
