package com.skyplus.finance.presentation.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

  private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
      BiometricManager.Authenticators.BIOMETRIC_WEAK or
      BiometricManager.Authenticators.DEVICE_CREDENTIAL

  fun canAuthenticate(activity: FragmentActivity): Boolean {
    val manager = BiometricManager.from(activity)
    return manager.canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
  }

  fun authenticate(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
  ) {
    val manager = BiometricManager.from(activity)
    val status = manager.canAuthenticate(ALLOWED_AUTHENTICATORS)
    if (status != BiometricManager.BIOMETRIC_SUCCESS) {
      onError("Biometric or device credential authentication is not configured on this device.")
      return
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        super.onAuthenticationSucceeded(result)
        onSuccess()
      }

      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        super.onAuthenticationError(errorCode, errString)
        onError(errString.toString())
      }

      override fun onAuthenticationFailed() {
        super.onAuthenticationFailed()
      }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
      .build()

    prompt.authenticate(promptInfo)
  }
}
