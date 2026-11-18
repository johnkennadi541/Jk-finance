package com.skyplus.finance.data.cloud

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DriveAuthorizationOutcome {
  data class Authorized(val accessToken: String) : DriveAuthorizationOutcome
  data class RequiresUserConsent(val intentSenderRequest: IntentSenderRequest) : DriveAuthorizationOutcome
  data class Unavailable(val reason: String) : DriveAuthorizationOutcome
}

/**
 * Requests user-consented Google Drive access strictly limited to `drive.file`
 * via Google Play Services AuthorizationClient (no broad Drive scope, no hardcoded secrets).
 */
@Singleton
class DriveAuthorizationManager @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  companion object {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
  }

  suspend fun authorizeDriveFileAccess(): DriveAuthorizationOutcome {
    val requestedScopes = listOf(Scope(DRIVE_FILE_SCOPE))
    val request = AuthorizationRequest.builder()
      .setRequestedScopes(requestedScopes)
      .build()

    return try {
      val result = Identity.getAuthorizationClient(context)
        .authorize(request)
        .awaitResult()
      if (result.hasResolution()) {
        val pendingIntent = result.pendingIntent
          ?: return DriveAuthorizationOutcome.Unavailable("Google Drive authorization intent unavailable.")
        DriveAuthorizationOutcome.RequiresUserConsent(
          IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
        )
      } else {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
          DriveAuthorizationOutcome.Unavailable("Google Drive did not return an OAuth access token.")
        } else {
          DriveAuthorizationOutcome.Authorized(token)
        }
      }
    } catch (e: Exception) {
      DriveAuthorizationOutcome.Unavailable(
        e.message ?: "Google Drive authorization requires Google Play Services and configured OAuth consent.",
      )
    }
  }

  fun extractAccessTokenFromResult(intentData: Intent?): Result<String> = runCatching {
    requireNotNull(intentData) { "Google Drive authorization cancelled." }
    val result = Identity.getAuthorizationClient(context)
      .getAuthorizationResultFromIntent(intentData)
    result.accessToken?.takeIf { it.isNotBlank() }
      ?: error("Google Drive access token was not granted.")
  }
}
