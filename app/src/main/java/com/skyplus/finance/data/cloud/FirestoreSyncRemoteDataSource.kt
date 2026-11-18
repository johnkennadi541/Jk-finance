package com.skyplus.finance.data.cloud

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.skyplus.finance.data.cloud.dto.FirestoreEncryptedEnvelope
import com.skyplus.finance.data.cloud.dto.FirestorePrivateProfileDto
import com.skyplus.finance.data.security.WrappedRecoveryDekEnvelope
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteEncryptedDocument(
  val recordId: String,
  val ownerUid: String,
  val entityType: String,
  val encryptedPayloadBase64: String,
  val payloadNonceBase64: String,
  val schemaVersion: Int,
  val syncVersion: Long,
  val updatedByDeviceId: String,
  val isDeleted: Boolean,
  val clientUpdatedAtMillis: Long,
  val serverUpdatedAtMillis: Long,
)

interface CloudSyncRemoteDataSource {
  suspend fun upsertPrivateProfile(profile: FirestorePrivateProfileDto): Result<Unit>
  suspend fun getPrivateProfile(uid: String): Result<FirestorePrivateProfileDto?>
  suspend fun getRecoveryMetadata(uid: String): Result<WrappedRecoveryDekEnvelope?>
  suspend fun upsertRecoveryMetadata(envelope: WrappedRecoveryDekEnvelope): Result<Unit>
  suspend fun getEncryptedRecord(uid: String, collectionName: String, recordId: String): Result<RemoteEncryptedDocument?>
  suspend fun upsertEncryptedRecord(collectionName: String, envelope: FirestoreEncryptedEnvelope): Result<Unit>
  suspend fun fetchEncryptedRecordsDelta(
    uid: String,
    collectionName: String,
    sinceClientUpdatedAtMillis: Long,
    limit: Long = 500L,
  ): Result<List<RemoteEncryptedDocument>>
}

@Singleton
class FirestoreSyncRemoteDataSource @Inject constructor(
  private val firestore: FirebaseFirestore?,
) : CloudSyncRemoteDataSource {

  private fun requireDb(): FirebaseFirestore =
    firestore ?: error("Firebase Firestore is unavailable in this environment.")

  override suspend fun upsertPrivateProfile(profile: FirestorePrivateProfileDto): Result<Unit> = runCatching {
    val db = requireDb()
    val ref = db.collection("users")
      .document(profile.uid)
      .collection("private")
      .document("profile")

    val existing = ref.get().awaitResult()
    val payload = linkedMapOf<String, Any>(
      "uid" to profile.uid,
      "cloudSyncEnabled" to profile.cloudSyncEnabled,
      "driveBackupEnabled" to profile.driveBackupEnabled,
      "driveWifiOnly" to profile.driveWifiOnly,
      "schemaVersion" to profile.schemaVersion,
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (!profile.email.isNullOrBlank()) payload["email"] = profile.email
    if (!profile.displayName.isNullOrBlank()) payload["displayName"] = profile.displayName
    if (profile.deviceId.isNotBlank()) payload["deviceId"] = profile.deviceId
    if (!existing.exists()) {
      payload["createdAt"] = FieldValue.serverTimestamp()
    } else {
      existing.getTimestamp("createdAt")?.let { payload["createdAt"] = it }
    }
    ref.set(payload, SetOptions.merge()).awaitResult()
    Unit
  }

  override suspend fun getPrivateProfile(uid: String): Result<FirestorePrivateProfileDto?> = runCatching {
    val db = requireDb()
    val snap = db.collection("users")
      .document(uid)
      .collection("private")
      .document("profile")
      .get()
      .awaitResult()
    if (!snap.exists()) return@runCatching null
    FirestorePrivateProfileDto(
      uid = snap.getString("uid") ?: uid,
      email = snap.getString("email"),
      displayName = snap.getString("displayName"),
      cloudSyncEnabled = snap.getBoolean("cloudSyncEnabled") ?: false,
      driveBackupEnabled = snap.getBoolean("driveBackupEnabled") ?: false,
      driveWifiOnly = snap.getBoolean("driveWifiOnly") ?: true,
      deviceId = snap.getString("deviceId") ?: "",
      schemaVersion = (snap.getLong("schemaVersion") ?: 1L).toInt(),
    )
  }

  override suspend fun getRecoveryMetadata(uid: String): Result<WrappedRecoveryDekEnvelope?> = runCatching {
    val db = requireDb()
    val snap = db.collection("users")
      .document(uid)
      .collection("crypto_metadata")
      .document("recovery")
      .get()
      .awaitResult()
    if (!snap.exists()) return@runCatching null
    WrappedRecoveryDekEnvelope(
      version = (snap.getLong("version") ?: 2L).toInt(),
      ownerUid = snap.getString("ownerUid") ?: uid,
      kdfAlgorithm = snap.getString("kdfAlgorithm") ?: return@runCatching null,
      kdfIterations = (snap.getLong("kdfIterations") ?: 0L).toInt(),
      saltBase64 = snap.getString("saltBase64") ?: return@runCatching null,
      nonceBase64 = snap.getString("nonceBase64") ?: return@runCatching null,
      wrappedDekBase64 = snap.getString("wrappedDekBase64") ?: return@runCatching null,
      keyCheckHash = snap.getString("keyCheckHash") ?: return@runCatching null,
      deviceId = snap.getString("deviceId") ?: "",
      createdAtEpochMillis = snap.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
      updatedAtEpochMillis = snap.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
    )
  }

  override suspend fun upsertRecoveryMetadata(envelope: WrappedRecoveryDekEnvelope): Result<Unit> = runCatching {
    val db = requireDb()
    val ref = db.collection("users")
      .document(envelope.ownerUid)
      .collection("crypto_metadata")
      .document("recovery")
    val existing = ref.get().awaitResult()
    val payload = linkedMapOf<String, Any>(
      "ownerUid" to envelope.ownerUid,
      "version" to envelope.version,
      "kdfAlgorithm" to envelope.kdfAlgorithm,
      "kdfIterations" to envelope.kdfIterations,
      "saltBase64" to envelope.saltBase64,
      "wrappedDekBase64" to envelope.wrappedDekBase64,
      "nonceBase64" to envelope.nonceBase64,
      "keyCheckHash" to envelope.keyCheckHash,
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (envelope.deviceId.isNotBlank()) {
      payload["deviceId"] = envelope.deviceId
    }
    if (!existing.exists()) {
      payload["createdAt"] = FieldValue.serverTimestamp()
    } else {
      existing.getTimestamp("createdAt")?.let { payload["createdAt"] = it }
    }
    ref.set(payload).awaitResult()
    Unit
  }

  override suspend fun getEncryptedRecord(
    uid: String,
    collectionName: String,
    recordId: String,
  ): Result<RemoteEncryptedDocument?> = runCatching {
    val db = requireDb()
    val snap = db.collection("users")
      .document(uid)
      .collection(collectionName)
      .document(recordId)
      .get()
      .awaitResult()
    snap.toRemoteEncryptedDocumentOrNull()
  }

  override suspend fun upsertEncryptedRecord(
    collectionName: String,
    envelope: FirestoreEncryptedEnvelope,
  ): Result<Unit> = runCatching {
    val db = requireDb()
    val ref = db.collection("users")
      .document(envelope.ownerUid)
      .collection(collectionName)
      .document(envelope.recordId)
    val existing = ref.get().awaitResult()

    val payload = linkedMapOf<String, Any>(
      "recordId" to envelope.recordId,
      "ownerUid" to envelope.ownerUid,
      "entityType" to envelope.entityType,
      "encryptedPayloadBase64" to envelope.encryptedPayloadBase64,
      "payloadNonceBase64" to envelope.payloadNonceBase64,
      "schemaVersion" to envelope.schemaVersion,
      "syncVersion" to envelope.syncVersion,
      "updatedByDeviceId" to envelope.updatedByDeviceId,
      "isDeleted" to envelope.isDeleted,
      "clientUpdatedAtMillis" to envelope.clientUpdatedAtMillis,
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (!existing.exists()) {
      payload["createdAt"] = FieldValue.serverTimestamp()
    } else {
      existing.getTimestamp("createdAt")?.let { payload["createdAt"] = it }
    }
    ref.set(payload).awaitResult()
    Unit
  }

  override suspend fun fetchEncryptedRecordsDelta(
    uid: String,
    collectionName: String,
    sinceClientUpdatedAtMillis: Long,
    limit: Long,
  ): Result<List<RemoteEncryptedDocument>> = runCatching {
    val db = requireDb()
    val baseCollection = db.collection("users")
      .document(uid)
      .collection(collectionName)
    val query = if (sinceClientUpdatedAtMillis > 0L) {
      baseCollection
        .whereGreaterThan("clientUpdatedAtMillis", sinceClientUpdatedAtMillis)
        .limit(limit)
    } else {
      baseCollection.limit(limit)
    }
    val snap = query.get().awaitResult()
    snap.documents.mapNotNull { it.toRemoteEncryptedDocumentOrNull() }
  }

  private fun DocumentSnapshot.toRemoteEncryptedDocumentOrNull(): RemoteEncryptedDocument? {
    if (!exists()) return null
    val recordId = getString("recordId") ?: id
    val ownerUid = getString("ownerUid") ?: return null
    val entityType = getString("entityType") ?: return null
    val encryptedPayload = getString("encryptedPayloadBase64") ?: return null
    val nonce = getString("payloadNonceBase64") ?: return null
    return RemoteEncryptedDocument(
      recordId = recordId,
      ownerUid = ownerUid,
      entityType = entityType,
      encryptedPayloadBase64 = encryptedPayload,
      payloadNonceBase64 = nonce,
      schemaVersion = (getLong("schemaVersion") ?: 3L).toInt(),
      syncVersion = getLong("syncVersion") ?: 1L,
      updatedByDeviceId = getString("updatedByDeviceId") ?: "",
      isDeleted = getBoolean("isDeleted") ?: false,
      clientUpdatedAtMillis = getLong("clientUpdatedAtMillis") ?: 0L,
      serverUpdatedAtMillis = getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
    )
  }
}
