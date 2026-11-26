package com.nightcinder.helix.auth

import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.nightcinder.helix.core.auth.ActivityProvider
import com.nightcinder.helix.core.auth.PasskeyAssertionRequester

class PasskeyCancelledException(message: String = "Passkey sign-in was cancelled") : Exception(message)

/** Invokes Android CredentialManager with the foreground Activity; returns the WebAuthn assertion JSON. */
class CredentialManagerPasskeyRequester(
    private val activityProvider: ActivityProvider,
) : PasskeyAssertionRequester {
    override suspend fun requestAssertion(requestJson: String): Result<String> {
        val activity = activityProvider.currentActivity()
            ?: return Result.failure(IllegalStateException("No foreground Activity for CredentialManager"))
        return try {
            val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val cred = result.credential
            if (cred is PublicKeyCredential) Result.success(cred.authenticationResponseJson)
            else Result.failure(IllegalStateException("Unexpected credential type ${cred.type}"))
        } catch (e: GetCredentialCancellationException) {
            Result.failure(PasskeyCancelledException())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
