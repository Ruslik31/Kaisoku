package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.scrobbling.discord.data.DiscordRepository
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

private const val TAG = "DiscordOauth"

/**
 * Discord OAuth2 (PKCE) login: launches the authorize page and catches the
 * `kaisoku|kotatsu://discord-auth?code=…` redirect to exchange the code for an access token.
 * Used only for the OAuth presence path; the user-token flow stays in [DiscordAuthActivity].
 */
@AndroidEntryPoint
class DiscordOauthActivity : ComponentActivity() {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var repository: DiscordRepository

	private var authStarted = false
	private var exchangeStarted = false
	private var basicScopesOnly = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		handleIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent) {
		val data = intent.data
		Log.i(TAG, "callback intent: $data")
		if (data != null && (data.scheme == "kaisoku" || data.scheme == "kotatsu") && data.host == "discord-auth") {
			when (val error = data.getQueryParameter("error")) {
				"invalid_scope" -> showScopeErrorScreen()

				"access_denied" -> finish()

				null -> {
					val code = data.getQueryParameter("code")
					if (code != null) {
						if (code == settings.discordLastExchangedCode) {
							Log.i(TAG, "already processed code; finishing")
							finish()
						} else if (exchangeStarted) {
							Log.i(TAG, "duplicate callback ignored")
						} else {
							exchangeStarted = true
							beginExchange(code)
						}
					} else {
						finish()
					}
				}

				else -> showErrorScreen(IllegalStateException(error))
			}
		} else {
			showProgressView()
			startAuthOnce()
		}
	}

	private fun startAuthOnce() {
		if (authStarted) {
			return
		}
		authStarted = true
		startAuth()
	}

	private fun beginExchange(code: String) {
		showProgressView()
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.Default) {
					repository.authorize(code)
				}
				Log.i(TAG, "code exchange ok")
				setResult(RESULT_OK)
				showSuccessScreen()
			} catch (e: Exception) {
				e.printStackTraceDebug()
				Log.w(TAG, "code exchange failed", e)
				showErrorScreen(e)
			}
		}
	}

	private suspend fun showSuccessScreen() {
		val name = withContext(Dispatchers.Default) {
			settings.discordToken?.let { token ->
				runCatchingCancellable { repository.checkToken(token) }.getOrNull()
			}
		}
		if (!name.isNullOrEmpty()) {
			settings.discordAccountName = name
			showMessageScreen(getString(R.string.discord_signed_in_as, name)) {
				addButton(R.string.done) { finish() }
			}
		} else {
			showMessageScreen(getString(R.string.discord_oauth_connected)) {
				addButton(R.string.done) { finish() }
			}
		}
	}

	private fun showScopeErrorScreen() {
		exchangeStarted = false
		showMessageScreen(getString(R.string.discord_oauth_invalid_scope)) {
			addButton(R.string.discord_oauth_basic_scope) {
				basicScopesOnly = true
				startAuth()
			}
			addButton(R.string.cancel) { finish() }
		}
	}

	private fun showErrorScreen(e: Exception) {
		exchangeStarted = false
		val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
		showMessageScreen(getString(R.string.discord_oauth_failed, ": $reason")) {
			addButton(R.string.retry) { startAuth() }
			addButton(R.string.cancel) { finish() }
		}
	}

	private fun startAuth() {
		val authUrl = if (settings.isDiscordOauthBrowser) {
			repository.oauthFallbackUrl(basicScopesOnly)
		} else {
			repository.oauthUrl(basicScopesOnly)
		}
		val intent = Intent(Intent.ACTION_VIEW, authUrl.toUri()).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		try {
			startActivity(intent)
		} catch (_: Exception) {
			intent.data = repository.oauthFallbackUrl(basicScopesOnly).toUri()
			try {
				startActivity(intent)
			} catch (e: Exception) {
				e.printStackTraceDebug()
				finish()
			}
		}
	}

	private fun showProgressView() {
		setContentView(
			FrameLayout(this).apply {
				addView(ProgressBar(this@DiscordOauthActivity))
			},
		)
	}

	private fun showMessageScreen(
		text: String,
		buttons: LinearLayout.() -> Unit,
	) {
		val density = resources.displayMetrics.density
		setContentView(
			ScrollView(this).apply {
				addView(
					LinearLayout(context).apply {
						orientation = LinearLayout.VERTICAL
						gravity = Gravity.CENTER
						setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
						addView(
							TextView(this@DiscordOauthActivity).apply {
								this.text = text
								textSize = 16f
								gravity = Gravity.CENTER
								setPadding(0, (64 * density).toInt(), 0, (48 * density).toInt())
							},
						)
						buttons()
					},
				)
			},
		)
	}

	private fun LinearLayout.addButton(textRes: Int, onClick: () -> Unit) {
		addView(
			Button(this@DiscordOauthActivity).apply {
				setText(textRes)
				setOnClickListener { onClick() }
			},
		)
	}
}
