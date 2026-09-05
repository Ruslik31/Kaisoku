package org.koitharu.kotatsu.sync.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface DriveAuthorization {
	data class Token(val value: String) : DriveAuthorization
	data class Resolution(val pendingIntent: PendingIntent) : DriveAuthorization
}

internal object DriveAuthorizationErrorPolicy {

	fun isApiConsoleSetupError(statusCode: Int, message: String?): Boolean =
		statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
			message?.contains(API_CONSOLE_UNREGISTERED, ignoreCase = true) == true

	private const val API_CONSOLE_UNREGISTERED = "UNREGISTERED_ON_API_CONSOLE"
}

class DriveAuthorizationRequiredException : IOException("Google Drive authorization is required")

data class DriveClientIdentity(
	val packageName: String,
	val sha1Fingerprints: List<String>,
) {
	fun asPlainText(): String = buildString {
		append("Package: ")
		append(packageName)
		append("\nSHA-1: ")
		append(sha1Fingerprints.joinToString().ifEmpty { "Unknown" })
	}
}

@Singleton
class GoogleDriveAuth @Inject constructor(@ApplicationContext private val context: Context) {

	private val client = Identity.getAuthorizationClient(context)
	private val request = AuthorizationRequest.builder()
		.setRequestedScopes(listOf(Scope(SCOPE_APPDATA)))
		.build()

	suspend fun authorize(activity: Activity? = null): DriveAuthorization {
		val authorizationClient = activity?.let(Identity::getAuthorizationClient) ?: client
		val result = authorizationClient.authorize(request).await()
		return result.toAuthorization()
	}

	fun authorizationFromIntent(data: Intent?): DriveAuthorization.Token {
		val result = client.getAuthorizationResultFromIntent(data)
		return result.toAuthorization() as? DriveAuthorization.Token
			?: throw DriveAuthorizationRequiredException()
	}

	suspend fun clearRejectedToken(token: String) {
		withContext(Dispatchers.IO) {
			GoogleAuthUtil.clearToken(context, token)
		}
	}

	fun getClientIdentity(): DriveClientIdentity = DriveClientIdentity(
		packageName = context.packageName,
		sha1Fingerprints = getSigningCertificates().map { signature ->
			MessageDigest.getInstance("SHA-1")
				.digest(signature.toByteArray())
				.joinToString(":") { byte -> "%02X".format(Locale.US, byte) }
		},
	)

	private fun getSigningCertificates(): Array<out Signature> = runCatching {
		@Suppress("DEPRECATION")
		val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			PackageManager.GET_SIGNING_CERTIFICATES
		} else {
			PackageManager.GET_SIGNATURES
		}
		@Suppress("DEPRECATION")
		val packageInfo = context.packageManager.getPackageInfo(context.packageName, flags)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			packageInfo.signingInfo?.apkContentsSigners.orEmpty()
		} else {
			@Suppress("DEPRECATION")
			packageInfo.signatures.orEmpty()
		}
	}.getOrDefault(emptyArray())

	private fun AuthorizationResult.toAuthorization(): DriveAuthorization {
		if (hasResolution()) return DriveAuthorization.Resolution(checkNotNull(pendingIntent))
		return accessToken?.let(DriveAuthorization::Token) ?: throw DriveAuthorizationRequiredException()
	}

	private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
		addOnSuccessListener { continuation.resume(it) }
		addOnFailureListener { continuation.resumeWithException(it) }
		addOnCanceledListener { continuation.cancel() }
	}

	companion object {
		const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
	}
}

class DriveApiException(val code: Int, message: String) : IOException(message)

class DriveSchemaException(val remoteVersion: Int) :
	IOException("Google Drive sync data uses newer schema $remoteVersion")
