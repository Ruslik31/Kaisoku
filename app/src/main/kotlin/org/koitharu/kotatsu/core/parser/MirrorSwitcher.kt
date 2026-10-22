package org.koitharu.kotatsu.core.parser

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.exceptions.ProxyConfigException
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/** A repository whose site can be served from one of several preset domains. */
interface MirrorableRepository {

	val source: MangaSource

	var domain: String

	val domains: Array<out String>
}

class MirrorSwitcher @Inject constructor(
	private val settings: AppSettings,
	@MangaHttpClient private val okHttpClient: OkHttpClient,
) {

	private val blacklist = HashSet<String>()
	private val mutex: Mutex = Mutex()

	val isEnabled: Boolean
		get() = settings.isMirrorSwitchingEnabled

	/** Runs [block]; when it fails or returns nothing, retries it on the repository's other mirrors. */
	suspend fun <T : Any> withMirrors(repository: MirrorableRepository, block: suspend () -> T): T {
		if (!isEnabled) {
			return block()
		}
		val initialResult = runCatchingCancellable { block() }
		if (initialResult.isValidResult()) {
			return initialResult.getOrThrow()
		}
		val newResult = trySwitchMirror(repository, block)
		return newResult ?: initialResult.getOrThrow()
	}

	suspend fun <T : Any> trySwitchMirror(repository: MirrorableRepository, loader: suspend () -> T?): T? {
		val source = repository.source.name
		if (!isEnabled || source in blacklist) {
			return null
		}
		val availableMirrors = repository.domains
		val currentHost = repository.domain
		if (availableMirrors.size <= 1 || currentHost !in availableMirrors) {
			return null
		}
		mutex.withLock {
			if (source in blacklist) {
				return null
			}
			logd { "Looking for mirrors for ${source}..." }
			findRedirect(repository)?.let { mirror ->
				repository.domain = mirror
				runCatchingCancellable {
					loader()?.takeIfValid()
				}.getOrNull()?.let {
					logd { "Found redirect for $source: $mirror" }
					return it
				}
			}
			for (mirror in availableMirrors) {
				repository.domain = mirror
				runCatchingCancellable {
					loader()?.takeIfValid()
				}.getOrNull()?.let {
					logd { "Found mirror for $source: $mirror" }
					return it
				}
			}
			repository.domain = currentHost // rollback
			blacklist.add(source)
			logd { "$source blacklisted" }
			return null
		}
	}

	suspend fun findRedirect(repository: MirrorableRepository): String? {
		if (!isEnabled) {
			return null
		}
		val currentHost = repository.domain
		val newHost = okHttpClient.newCall(
			Request.Builder()
				.url("https://$currentHost")
				.head()
				.build(),
		).await().use {
			if (it.isSuccessful) {
				it.request.url.host
			} else {
				null
			}
		}
		return if (newHost != currentHost) {
			newHost
		} else {
			null
		}
	}

	private fun Result<Any>.isValidResult() = fold(
		onSuccess = {
			when (it) {
				is Collection<*> -> it.isNotEmpty()
				else -> true
			}
		},
		onFailure = {
			when (it.cause) {
				is CloudFlareProtectedException,
				is AuthRequiredException,
				is InteractiveActionRequiredException,
				is ProxyConfigException -> true

				else -> false
			}
		},
	)

	private fun <T : Any> T.takeIfValid() = takeIf {
		when (it) {
			is Collection<*> -> it.isNotEmpty()
			else -> true
		}
	}

	private companion object {

		const val TAG = "MirrorSwitcher"

		inline fun logd(message: () -> String) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, message())
			}
		}
	}
}
