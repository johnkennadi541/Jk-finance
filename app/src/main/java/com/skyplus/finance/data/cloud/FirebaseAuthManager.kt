package com.skyplus.finance.data.cloud

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.skyplus.finance.data.backup.GoogleAuthConfigStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class AuthenticatedCloudUser(
  val uid: String,
  val email: String?,
  val displayName: String?,
)

/**
 * Manages optional Google Sign-In via Jetpack Credential Manager + Firebase Authentication.
 * - Guest Mode remains default and requires zero Firebase or network calls.
 * - Anonymous authentication (signInAnonymously) is strictly disabled.
 * - Resolves Web Client ID from AI Studio Secrets (`GOOGLE_SERVER_CLIENT_ID`) or
 *   Firebase `google-services.json` (`R.string.default_web_client_id`).
 */
@Singleton
class FirebaseAuthManager private constructor(
  private val context: Context,
  private val firebaseAuth: FirebaseAuth?,
  private val testUserOverride: (() -> AuthenticatedCloudUser?)?,
) {
  @Inject
  constructor(
    @ApplicationContext context: Context,
    firebaseAuth: FirebaseAuth?,
  ) : this(context, firebaseAuth, null)

  companion object {
    const val DEBUG_SHA1 = "F3:51:5B:C5:FF:98:07:6D:14:D3:47:57:2C:05:B0:BD:2D:EF:9D:57"
    const val DEBUG_SHA256 = "73:04:26:A5:E2:1E:35:15:97:6D:D3:56:71:7E:F2:1A:B0:AA:E5:AD:82:40:5D:98:10:E1:97:D0:1A:65:16:27"

    fun createForTests(
      context: Context,
      userProvider: () -> AuthenticatedCloudUser?,
    ): FirebaseAuthManager = FirebaseAuthManager(context, null, userProvider)
  }

  fun resolveWebClientId(): String? {
    val buildConfigId = BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()
    if (isValidClientId(buildConfigId)) return buildConfigId

    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId != 0) {
      val resValue = context.getString(resId).trim()
      if (isValidClientId(resValue)) return resValue
    }
    return null
  }

  private fun isValidClientId(clientId: String): Boolean {
    return clientId.isNotEmpty() &&
      !clientId.startsWith("YOUR_", ignoreCase = true) &&
      !clientId.contains("PLACEHOLDER", ignoreCase = true) &&
      clientId.endsWith(".apps.googleusercontent.com", ignoreCase = true)
  }

  fun checkConfiguration(): GoogleAuthConfigStatus {
    val webClientId = resolveWebClientId()
    val hasDefaultWebClientRes = context.resources.getIdentifier(
      "default_web_client_id",
      "string",
      context.packageName,
    ) != 0

    if (webClientId == null || firebaseAuth == null || !hasDefaultWebClientRes) {
      val missingParts = buildList {
        if (webClientId == null) add("Web OAuth Client ID (GOOGLE_SERVER_CLIENT_ID or google-services.json default_web_client_id)")
        if (!hasDefaultWebClientRes) add("Android OAuth client registration in app/google-services.json (currently oauth_client is empty)")
        if (firebaseAuth == null) add("FirebaseApp / FirebaseAuth initialization")
      }
      return GoogleAuthConfigStatus.Blocker(
        reason = "Firebase Google Sign-In requires console OAuth setup before live authentication: ${missingParts.joinToString("; ")}.",
        requiredSteps = listOf(
          "In Firebase Console project 'skyplus-finance', add Debug SHA-1 ($DEBUG_SHA1) and SHA-256 ($DEBUG_SHA256) under Project Settings -> Android app (${context.packageName}).",
          "Enable Google Sign-In provider under Authentication -> Sign-in method (and publish OAuth consent screen).",
          "Download the updated google-services.json into app/google-services.json (so oauth_client & default_web_client_id are populated) and optionally set GOOGLE_SERVER_CLIENT_ID in AI Studio Secrets.",
          "Offline Guest Mode remains 100% functional without signing in.",
        ),
      )
    }
    return GoogleAuthConfigStatus.Ready
  }

  fun currentUser(): AuthenticatedCloudUser? {
    testUserOverride?.let { return it() }
    val user = firebaseAuth?.currentUser ?: return null
    if (user.isAnonymous) return null
    return user.toDomainUser()
  }

  fun authStateFlow(): Flow<AuthenticatedCloudUser?> = callbackFlow {
    val auth = firebaseAuth
    if (auth == null) {
      trySend(null)
      close()
      return@callbackFlow
    }
    val listener = FirebaseAuth.AuthStateListener { fa ->
      val user = fa.currentUser?.takeUnless { it.isAnonymous }
      trySend(user?.toDomainUser())
    }
    auth.addAuthStateListener(listener)
    trySend(auth.currentUser?.takeUnless { it.isAnonymous }?.toDomainUser())
    awaitClose {
      auth.removeAuthStateListener(listener)
    }
  }

  suspend fun signInWithGoogle(activityContext: Context): Result<AuthenticatedCloudUser> {
    val status = checkConfiguration()
    if (status is GoogleAuthConfigStatus.Blocker) {
      return Result.failure(IllegalStateException(status.reason))
    }
    val webClientId = resolveWebClientId()
      ?: return Result.failure(IllegalStateException("Google Web Client ID is missing."))
    val auth = firebaseAuth
      ?: return Result.failure(IllegalStateException("FirebaseAuth is unavailable in this environment."))

    return runCatching {
      val credentialManager = CredentialManager.create(activityContext)
      val nonce = UUID.randomUUID().toString()
      val signInOption = GetSignInWithGoogleOption.Builder(webClientId)
        .setNonce(nonce)
        .build()
      val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()

      val response = try {
        credentialManager.getCredential(
          context = activityContext,
          request = request,
        )
      } catch (_: NoCredentialException) {
        val fallbackOption = GetGoogleIdOption.Builder()
          .setFilterByAuthorizedAccounts(false)
          .setServerClientId(webClientId)
          .setNonce(nonce)
          .build()
        val fallbackRequest = GetCredentialRequest.Builder()
          .addCredentialOption(fallbackOption)
          .build()
        try {
          credentialManager.getCredential(
            context = activityContext,
            request = fallbackRequest,
          )
        } catch (e: NoCredentialException) {
          error("No Google account credentials available on this device. Offline Guest Mode remains active.")
        } catch (e: GetCredentialCancellationException) {
          error("Google Sign-In cancelled. Your local financial data remains untouched.")
        } catch (e: GetCredentialException) {
          error("Google Sign-In failed: ${e.errorMessage ?: e.type}. Your local financial data remains untouched.")
        }
      } catch (e: GetCredentialCancellationException) {
        error("Google Sign-In cancelled. Your local financial data remains untouched.")
      } catch (e: GetCredentialException) {
        error("Google Sign-In failed: ${e.errorMessage ?: e.type}. Your local financial data remains untouched.")
      }

      val cred = response.credential
      if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
        val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
        val authResult = auth.signInWithCredential(firebaseCred).awaitResult()
        val user = authResult.user
          ?: error("Firebase Authentication did not return a valid user profile.")
        user.toDomainUser().copy(
          email = user.email ?: googleCred.id,
          displayName = user.displayName ?: googleCred.displayName,
        )
      } else {
        error("Unsupported credential type returned by Credential Manager.")
      }
    }
  }

  suspend fun signOutPreservingLocalData(): Result<Unit> = runCatching {
    firebaseAuth?.signOut()
    runCatching {
      CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
  }

  private fun FirebaseUser.toDomainUser(): AuthenticatedCloudUser =
    AuthenticatedCloudUser(
      uid = uid,
      email = email,
      displayName = displayName,
    )
}
