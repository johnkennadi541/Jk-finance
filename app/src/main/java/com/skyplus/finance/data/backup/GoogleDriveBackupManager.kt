package com.skyplus.finance.data.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import com.example.BuildConfig
import com.skyplus.finance.data.cloud.DriveAuthorizationManager
import com.skyplus.finance.data.cloud.FirebaseAuthManager
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.export.FinancialExportManager
import com.skyplus.finance.data.security.AppPreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface GoogleAuthConfigStatus {
  data object Ready : GoogleAuthConfigStatus
  data class Blocker(val reason: String, val requiredSteps: List<String>) : GoogleAuthConfigStatus
}

data class DriveHttpResponse(
  val statusCode: Int,
  val body: String,
)

interface DriveHttpTransport {
  fun execute(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String? = null,
  ): DriveHttpResponse
}

class DefaultDriveHttpTransport : DriveHttpTransport {
  override fun execute(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?,
  ): DriveHttpResponse {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = 15_000
      readTimeout = 25_000
      headers.forEach { (k, v) -> setRequestProperty(k, v) }
      if (body != null) {
        doOutput = true
      }
    }
    if (body != null) {
      OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(body)
        writer.flush()
      }
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val responseBody = stream?.let { s ->
      BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
    }.orEmpty()
    return DriveHttpResponse(code, responseBody)
  }
}

@Serializable
data class DriveBackupMetadataItem(
  val id: String,
  val name: String,
  val createdTime: String? = null,
  val modifiedTime: String? = null,
  val size: String? = null,
)

@Serializable
private data class DriveFilesListResponse(
  val files: List<DriveBackupMetadataItem> = emptyList(),
)

@Serializable
private data class DriveAboutResponse(
  val user: DriveAboutUser? = null,
)

@Serializable
private data class DriveAboutUser(
  val emailAddress: String? = null,
  val displayName: String? = null,
)

@Serializable
private data class DriveCreatedFileResponse(
  val id: String = "",
  val name: String = "",
  val size: String? = null,
)

/**
 * Manages user-controlled AES-256-GCM encrypted backups stored inside each user's own
 * Google Drive folder (`SKYPLUS FINANCE Backups`) under the least-privilege `drive.file` scope.
 * - Independent of Firestore Cloud Sync (Option C).
 * - Verifies authorized Drive user email matches the active signed-in account before R/W.
 * - Uses Last-Known-Good upload verification before pruning older backups (keeps baseline + latest 5).
 */
@Singleton
class GoogleDriveBackupManager(
  private val context: Context,
  private val database: SkyPlusDatabase,
  private val exportManager: FinancialExportManager,
  private val preferencesDataStore: AppPreferencesDataStore,
  private val firebaseAuthManager: FirebaseAuthManager? = null,
  private val httpTransport: DriveHttpTransport = DefaultDriveHttpTransport(),
) {
  @Inject
  constructor(
    @ApplicationContext context: Context,
    database: SkyPlusDatabase,
    exportManager: FinancialExportManager,
    preferencesDataStore: AppPreferencesDataStore,
    firebaseAuthManager: FirebaseAuthManager,
  ) : this(
    context = context,
    database = database,
    exportManager = exportManager,
    preferencesDataStore = preferencesDataStore,
    firebaseAuthManager = firebaseAuthManager,
    httpTransport = DefaultDriveHttpTransport(),
  )

  companion object {
    const val DRIVE_FILE_SCOPE = DriveAuthorizationManager.DRIVE_FILE_SCOPE
    const val DRIVE_BACKUP_FOLDER_NAME = "SKYPLUS FINANCE Backups"
    const val BACKUP_FILE_PREFIX = "skyplus_finance_backup_"
    const val BACKUP_FILE_EXTENSION = ".skyplusenc"
    const val MAX_LATEST_BACKUPS = 5
  }

  private val json = Json { ignoreUnknownKeys = true }

  fun checkGoogleOAuthConfiguration(): GoogleAuthConfigStatus {
    firebaseAuthManager?.let { return it.checkConfiguration() }

    val buildConfigId = BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()
    val hasValidBuildId = isValidClientId(buildConfigId)
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    val resClientId = if (resId != 0) context.getString(resId).trim() else ""
    val hasValidResId = isValidClientId(resClientId)

    if (!hasValidBuildId && !hasValidResId) {
      return GoogleAuthConfigStatus.Blocker(
        reason = "Google OAuth Client ID is not configured for this build.",
        requiredSteps = listOf(
          "Register an Android OAuth 2.0 Client ID in Firebase Console ('skyplus-finance') for package ${context.packageName} with your Debug SHA-1 & SHA-256 fingerprints.",
          "Enable Google Sign-In in Firebase Authentication and publish Google Drive API OAuth consent ($DRIVE_FILE_SCOPE).",
          "Download updated app/google-services.json and/or configure GOOGLE_SERVER_CLIENT_ID in AI Studio Secrets.",
        ),
      )
    }
    return GoogleAuthConfigStatus.Ready
  }

  private fun isValidClientId(clientId: String): Boolean {
    return clientId.isNotEmpty() &&
      !clientId.startsWith("YOUR_", ignoreCase = true) &&
      !clientId.contains("PLACEHOLDER", ignoreCase = true) &&
      clientId.endsWith(".apps.googleusercontent.com", ignoreCase = true)
  }

  fun isNetworkAvailable(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  fun isUnmeteredNetwork(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
  }

  suspend fun signInWithGoogle(activityContext: Context): Result<String> {
    val authMgr = firebaseAuthManager
      ?: return Result.failure(IllegalStateException("FirebaseAuthManager is unavailable."))
    return authMgr.signInWithGoogle(activityContext).map { user ->
      val email = user.email ?: user.uid
      linkAccountEmailWithoutOverwritingData(email)
      email
    }
  }

  suspend fun signOutPreservingLocalData(): Result<Unit> = runCatching {
    val previousUid = firebaseAuthManager?.currentUser()?.uid
    if (!previousUid.isNullOrBlank()) {
      preferencesDataStore.clearDriveCacheForUid(previousUid)
    }
    firebaseAuthManager?.signOutPreservingLocalData()?.getOrThrow()
    database.withTransaction {
      val primary = database.accountDao().getPrimaryAccountOnce()
      if (primary != null) {
        database.accountDao().updateAccount(primary.copy(googleAccountEmail = null))
      }
    }
  }

  suspend fun linkAccountEmailWithoutOverwritingData(email: String) {
    database.withTransaction {
      val primary = database.accountDao().getPrimaryAccountOnce()
      if (primary != null) {
        database.accountDao().updateAccount(primary.copy(googleAccountEmail = email))
      }
    }
  }

  suspend fun verifyDriveAccountOwnership(
    oauthAccessToken: String,
    expectedEmail: String?,
  ): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val response = httpTransport.execute(
        method = "GET",
        url = "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)",
        headers = mapOf("Authorization" to "Bearer $oauthAccessToken"),
      )
      if (response.statusCode !in 200..299) {
        error("Unable to verify Google Drive account ownership (HTTP ${response.statusCode}).")
      }
      val about = json.decodeFromString(DriveAboutResponse.serializer(), response.body)
      val driveEmail = about.user?.emailAddress?.trim()
        ?: error("Google Drive did not return an account email address.")
      if (!expectedEmail.isNullOrBlank() && !driveEmail.equals(expectedEmail.trim(), ignoreCase = true)) {
        error(
          "Google Drive account ($driveEmail) does not match your signed-in account ($expectedEmail). Re-authorize with the matching Google account.",
        )
      }
      driveEmail
    }
  }

  suspend fun uploadEncryptedBackupToDrive(
    oauthAccessToken: String?,
    recoveryPassphrase: CharArray,
  ): Result<String> = uploadEncryptedBackupToDrive(
    oauthAccessToken = oauthAccessToken,
    recoveryPassphrase = recoveryPassphrase,
    uid = "GUEST",
    expectedAccountEmail = null,
    skipConnectivityCheck = false,
  )

  suspend fun uploadEncryptedBackupToDrive(
    oauthAccessToken: String?,
    recoveryPassphrase: CharArray,
    uid: String = "GUEST",
    expectedAccountEmail: String? = null,
    skipConnectivityCheck: Boolean = false,
  ): Result<String> = withContext(Dispatchers.IO) {
    if (!skipConnectivityCheck && !isNetworkAvailable()) {
      return@withContext Result.failure(
        IllegalStateException("Internet connection unavailable. Google Drive backup requires an active network."),
      )
    }
    if (oauthAccessToken.isNullOrBlank()) {
      return@withContext Result.failure(
        IllegalStateException("Google Drive authorization ($DRIVE_FILE_SCOPE) is required before uploading."),
      )
    }

    runCatching {
      val verifiedEmail = verifyDriveAccountOwnership(oauthAccessToken, expectedAccountEmail).getOrThrow()
      preferencesDataStore.setDriveAuthorizedEmail(uid, verifiedEmail)

      val encryptedEnvelope = exportManager.buildEncryptedBackupString(recoveryPassphrase).getOrThrow()
      val folderId = findOrCreateBackupFolder(oauthAccessToken, uid)
      val timestampTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val fileName = "${BACKUP_FILE_PREFIX}${timestampTag}$BACKUP_FILE_EXTENSION"

      val boundary = "skyplus_boundary_${UUID.randomUUID()}"
      val metadataJson = """{"name":"$fileName","parents":["$folderId"],"mimeType":"application/octet-stream"}"""
      val payload = buildString {
        append("--$boundary\r\n")
        append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        append(metadataJson)
        append("\r\n--$boundary\r\n")
        append("Content-Type: application/octet-stream\r\n\r\n")
        append(encryptedEnvelope)
        append("\r\n--$boundary--\r\n")
      }

      val uploadResp = httpTransport.execute(
        method = "POST",
        url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size",
        headers = mapOf(
          "Authorization" to "Bearer $oauthAccessToken",
          "Content-Type" to "multipart/related; boundary=$boundary",
        ),
        body = payload,
      )
      if (uploadResp.statusCode !in 200..299) {
        preferencesDataStore.recordDriveBackupStateForUid(
          uid,
          System.currentTimeMillis(),
          "Failed: HTTP ${uploadResp.statusCode}",
        )
        error("Google Drive upload failed (HTTP ${uploadResp.statusCode}): ${uploadResp.body}")
      }

      val createdFile = runCatching {
        json.decodeFromString(DriveCreatedFileResponse.serializer(), uploadResp.body)
      }.getOrNull()
      check(createdFile == null || createdFile.id.isNotBlank()) {
        "Google Drive upload did not return a valid file ID."
      }

      // Last-Known-Good retention: prune ONLY AFTER verified upload succeeds.
      pruneRetentionKeepingBaselineAndLatest(oauthAccessToken, folderId)

      val now = System.currentTimeMillis()
      preferencesDataStore.recordDriveBackupStateForUid(
        uid,
        now,
        "Encrypted backup saved to '$DRIVE_BACKUP_FOLDER_NAME' ($verifiedEmail)",
      )
      "Encrypted backup uploaded ($fileName) to '$DRIVE_BACKUP_FOLDER_NAME'."
    }
  }

  suspend fun listBackupsInDrive(
    oauthAccessToken: String?,
    uid: String = "GUEST",
    expectedAccountEmail: String? = null,
    skipConnectivityCheck: Boolean = false,
  ): Result<List<DriveBackupMetadataItem>> = withContext(Dispatchers.IO) {
    if (!skipConnectivityCheck && !isNetworkAvailable()) {
      return@withContext Result.failure(
        IllegalStateException("Internet connection unavailable."),
      )
    }
    if (oauthAccessToken.isNullOrBlank()) {
      return@withContext Result.failure(
        IllegalStateException("Google Drive authorization ($DRIVE_FILE_SCOPE) is required."),
      )
    }
    runCatching {
      verifyDriveAccountOwnership(oauthAccessToken, expectedAccountEmail).getOrThrow()
      val folderId = findExistingBackupFolder(oauthAccessToken, uid)
        ?: return@runCatching emptyList()
      listBackupsInFolder(oauthAccessToken, folderId, orderAsc = false)
    }
  }

  suspend fun restoreBackupFromDrive(
    oauthAccessToken: String?,
    recoveryPassphrase: CharArray,
    overwriteExisting: Boolean,
    targetFileId: String? = null,
    uid: String = "GUEST",
    expectedAccountEmail: String? = null,
    skipConnectivityCheck: Boolean = false,
  ): Result<Int> = withContext(Dispatchers.IO) {
    if (!skipConnectivityCheck && !isNetworkAvailable()) {
      return@withContext Result.failure(
        IllegalStateException("Internet connection unavailable. Google Drive restore requires an active network."),
      )
    }
    if (oauthAccessToken.isNullOrBlank()) {
      return@withContext Result.failure(
        IllegalStateException("Google Drive authorization ($DRIVE_FILE_SCOPE) is required before restoring."),
      )
    }

    runCatching {
      verifyDriveAccountOwnership(oauthAccessToken, expectedAccountEmail).getOrThrow()

      val fileIdToDownload = if (!targetFileId.isNullOrBlank()) {
        targetFileId
      } else {
        val folderId = findExistingBackupFolder(oauthAccessToken, uid)
          ?: error("No '$DRIVE_BACKUP_FOLDER_NAME' folder found on Google Drive.")
        val backups = listBackupsInFolder(oauthAccessToken, folderId, orderAsc = false)
        backups.firstOrNull()?.id
          ?: error("No encrypted SkyPlus Finance backup files found in '$DRIVE_BACKUP_FOLDER_NAME'.")
      }

      val dlResp = httpTransport.execute(
        method = "GET",
        url = "https://www.googleapis.com/drive/v3/files/$fileIdToDownload?alt=media",
        headers = mapOf("Authorization" to "Bearer $oauthAccessToken"),
      )
      if (dlResp.statusCode !in 200..299) {
        error("Failed to download backup from Google Drive (HTTP ${dlResp.statusCode}).")
      }

      val count = exportManager.importEncryptedPayloadFromJson(
        encryptedEnvelopeJson = dlResp.body,
        passphrase = recoveryPassphrase,
        overwriteExisting = overwriteExisting,
      ).getOrThrow()

      val modeLabel = if (overwriteExisting) "Replaced with" else "Merged"
      preferencesDataStore.recordDriveBackupStateForUid(
        uid,
        System.currentTimeMillis(),
        "$modeLabel $count records from Google Drive",
      )
      count
    }
  }

  /**
   * Backward-compatible entry point preserved for existing Phase 2/3 tests.
   */
  suspend fun uploadEncryptedBackupToAppDataFolder(
    oauthAccessToken: String?,
    recoveryPassphrase: CharArray,
  ): Result<String> {
    val authStatus = checkGoogleOAuthConfiguration()
    if (authStatus is GoogleAuthConfigStatus.Blocker && oauthAccessToken.isNullOrBlank()) {
      return Result.failure(IllegalStateException(authStatus.reason))
    }
    return uploadEncryptedBackupToDrive(
      oauthAccessToken = oauthAccessToken,
      recoveryPassphrase = recoveryPassphrase,
      skipConnectivityCheck = false,
    )
  }

  /**
   * Backward-compatible entry point preserved for existing Phase 2/3 tests.
   */
  suspend fun restoreLatestFromAppDataFolder(
    oauthAccessToken: String?,
    recoveryPassphrase: CharArray,
    overwriteExisting: Boolean,
  ): Result<Int> {
    return restoreBackupFromDrive(
      oauthAccessToken = oauthAccessToken,
      recoveryPassphrase = recoveryPassphrase,
      overwriteExisting = overwriteExisting,
      skipConnectivityCheck = false,
    )
  }

  private suspend fun findExistingBackupFolder(oauthAccessToken: String, uid: String): String? {
    val cached = preferencesDataStore.getDriveFolderId(uid)
    if (!cached.isNullOrBlank()) {
      val checkResp = httpTransport.execute(
        method = "GET",
        url = "https://www.googleapis.com/drive/v3/files/$cached?fields=id,name,trashed",
        headers = mapOf("Authorization" to "Bearer $oauthAccessToken"),
      )
      if (checkResp.statusCode in 200..299 && !checkResp.body.contains("\"trashed\":true")) {
        return cached
      }
      preferencesDataStore.setDriveFolderId(uid, null)
    }

    val query = URLEncoder.encode(
      "name='$DRIVE_BACKUP_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false",
      "UTF-8",
    )
    val resp = httpTransport.execute(
      method = "GET",
      url = "https://www.googleapis.com/drive/v3/files?spaces=drive&q=$query&fields=files(id,name)",
      headers = mapOf("Authorization" to "Bearer $oauthAccessToken"),
    )
    if (resp.statusCode !in 200..299) {
      error("Unable to query Google Drive backup folder (HTTP ${resp.statusCode}).")
    }
    val parsed = json.decodeFromString(DriveFilesListResponse.serializer(), resp.body)
    val foundId = parsed.files.firstOrNull()?.id
    if (!foundId.isNullOrBlank()) {
      preferencesDataStore.setDriveFolderId(uid, foundId)
    }
    return foundId
  }

  private suspend fun findOrCreateBackupFolder(oauthAccessToken: String, uid: String): String {
    findExistingBackupFolder(oauthAccessToken, uid)?.let { return it }

    val createBody = """{"name":"$DRIVE_BACKUP_FOLDER_NAME","mimeType":"application/vnd.google-apps.folder"}"""
    val resp = httpTransport.execute(
      method = "POST",
      url = "https://www.googleapis.com/drive/v3/files?fields=id,name",
      headers = mapOf(
        "Authorization" to "Bearer $oauthAccessToken",
        "Content-Type" to "application/json; charset=UTF-8",
      ),
      body = createBody,
    )
    if (resp.statusCode !in 200..299) {
      error("Unable to create '$DRIVE_BACKUP_FOLDER_NAME' folder in Google Drive (HTTP ${resp.statusCode}).")
    }
    val created = json.decodeFromString(DriveCreatedFileResponse.serializer(), resp.body)
    require(created.id.isNotBlank()) { "Google Drive folder creation returned an empty folder ID." }
    preferencesDataStore.setDriveFolderId(uid, created.id)
    return created.id
  }

  private fun listBackupsInFolder(
    oauthAccessToken: String,
    folderId: String,
    orderAsc: Boolean,
  ): List<DriveBackupMetadataItem> {
    val query = URLEncoder.encode(
      "'$folderId' in parents and trashed=false and name contains '$BACKUP_FILE_EXTENSION'",
      "UTF-8",
    )
    val order = if (orderAsc) "createdTime asc" else "createdTime desc"
    val encodedOrder = URLEncoder.encode(order, "UTF-8")
    val resp = httpTransport.execute(
      method = "GET",
      url = "https://www.googleapis.com/drive/v3/files?spaces=drive&q=$query&orderBy=$encodedOrder&fields=files(id,name,createdTime,modifiedTime,size)",
      headers = mapOf("Authorization" to "Bearer $oauthAccessToken"),
    )
    if (resp.statusCode !in 200..299) {
      error("Unable to list backups inside '$DRIVE_BACKUP_FOLDER_NAME' (HTTP ${resp.statusCode}).")
    }
    return json.decodeFromString(DriveFilesListResponse.serializer(), resp.body).files
  }

  private fun pruneRetentionKeepingBaselineAndLatest(
    oauthAccessToken: String,
    folderId: String,
  ) {
    val allAsc = runCatching { listBackupsInFolder(oauthAccessToken, folderId, orderAsc = true) }
      .getOrNull() ?: return
    if (allAsc.size <= 1 + MAX_LATEST_BACKUPS) return

    val baselineId = allAsc.first().id
    val latestKeepIds = allAsc.takeLast(MAX_LATEST_BACKUPS).map { it.id }.toSet()
    val toDelete = allAsc.filter { it.id != baselineId && it.id !in latestKeepIds }
    for (file in toDelete) {
      runCatching {
        httpTransport.execute(
          method = "DELETE",
          url = "https://www.googleapis.com/drive/v3/files/${file.id}",
          headers = mapOf("Authorization" to "Bearer $oauthAccessToken"),
        )
      }
    }
  }
}
