package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Context
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import com.my.kizzyrpc.entities.presence.Activity
import com.my.kizzyrpc.entities.presence.Assets
import com.my.kizzyrpc.entities.presence.Metadata
import com.my.kizzyrpc.entities.presence.Timestamps
import dagger.Lazy
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import okio.utf8Size
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.scrobbling.discord.data.DiscordRepository
import org.koitharu.kotatsu.scrobbling.discord.domain.DiscordPresenceStatus

private const val BUTTON_TEXT_LIMIT = 32
private const val DEBOUNCE_TIMEOUT = 16_000L
private const val WSRV_PREFIX = "https://wsrv.nl/?url="

@ViewModelScoped
class DiscordRpc @Inject constructor(
    @LocalizedAppContext private val context: Context,
    private val settings: AppSettings,
    private val repository: DiscordRepository,
    private val oauthRpc: Lazy<DiscordOauthRpc>,
    lifecycle: ViewModelLifecycle,
) : RetainedLifecycle.OnClearedListener {
    private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
    private val appId = context.getString(R.string.discord_app_id)
    private val appName = context.getString(R.string.app_name)
    private val appIcon = context.getString(R.string.app_icon_url)
    private val mpCache = Collections.synchronizedMap(ArrayMap<String, String>())
    private var rpc: DiscordTokenGateway? = null
    @Volatile private var oauthConstructed = false
    private val updates = DiscordPresenceDispatcher(
        scope = coroutineScope,
        debounceMillis = DEBOUNCE_TIMEOUT,
        now = SystemClock::elapsedRealtime,
        wallTime = System::currentTimeMillis,
        status = { DiscordPresenceStatus.fromPreference(settings.discordRpcStatus) },
        allowed = { nsfw -> settings.isDiscordRpcEnabled && !settings.isDiscordRpcOauth &&
            !(settings.isDiscordRpcSkipNsfw && nsfw) },
        connect = { getRpc()?.also { it.awaitReady() } },
        prepare = { activity: Activity, _: Boolean -> mapActivity(activity) },
        send = { client: DiscordTokenGateway, packet ->
            if (synchronized(this) { rpc === client }) client.send(packet) else false
        },
        onError = { it.printStackTraceDebug(); closeTokenClient() },
    )

    init {
        lifecycle.addOnClearedListener(this)
        settings.observe(AppSettings.KEY_DISCORD_RPC_STATUS, AppSettings.KEY_DISCORD_RPC,
            AppSettings.KEY_DISCORD_RPC_OAUTH, AppSettings.KEY_DISCORD_TOKEN, AppSettings.KEY_DISCORD_SCOPES,
            AppSettings.KEY_DISCORD_RPC_SKIP_NSFW)
            .drop(1)
            .onEach { key ->
                when (key) {
                    AppSettings.KEY_DISCORD_RPC_STATUS -> refreshPresence()
                    AppSettings.KEY_DISCORD_TOKEN, AppSettings.KEY_DISCORD_SCOPES -> {
                        closeTokenClient()
                        if (oauthConstructed) oauthRpc.get().reconnectPresence()
                        if (!settings.isDiscordRpcOauth) updates.refresh()
                    }
                    else -> clearRpc()
                }
            }
            .launchIn(coroutineScope)
    }

    override fun onCleared() {
        clearRpc()
        if (oauthConstructed) coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { oauthRpc.get().close() }.onFailure { it.printStackTraceDebug() }
        }
    }

    fun clearRpc() {
        updates.clear()
        closeTokenClient()
        if (oauthConstructed) oauthRpc.get().clearRpc()
    }

    fun setIdle() {
        if (settings.isDiscordRpcOauth) {
            if (oauthConstructed) oauthRpc.get().setIdle()
        } else updates.setIdle()
    }

    private fun refreshPresence() {
        if (settings.isDiscordRpcOauth) {
            if (oauthConstructed) oauthRpc.get().refreshPresence()
        } else updates.refresh()
    }

    private fun closeTokenClient() {
        val client = synchronized(this) { rpc.also { rpc = null } } ?: return
        client.invalidate()
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { client.close() }.onFailure { it.printStackTraceDebug() }
        }
    }

    @AnyThread
    @Synchronized
    fun updateRpc(manga: Manga, state: ReaderUiState) {
        if (!settings.isDiscordRpcEnabled || (settings.isDiscordRpcSkipNsfw && manga.isNsfw())) {
            clearRpc()
            return
        }
        if (settings.isDiscordRpcOauth) {
            val oauth = oauthRpc.get()
            oauthConstructed = true
            oauth.updateRpc(manga, state)
            return
        }
        val coverUrl = manga.largeCoverUrl?.takeUnless { it.isBlank() }
            ?: manga.coverUrl?.takeUnless { it.isBlank() }
        val buttons = buildDiscordRpcButtons(context.getString(R.string.url_discord),
            context.getString(R.string.telegram_group), BUTTON_TEXT_LIMIT)
        updates.submit(Activity(
            applicationId = appId,
            name = appName,
            details = manga.title,
            state = state.getChapterTitle(context.resources),
            type = 3,
            timestamps = Timestamps(start = updates.activity?.timestamps?.start ?: System.currentTimeMillis()),
            assets = Assets(
                largeImage = coverUrl,
                largeText = context.getString(R.string.reading_s, manga.title),
                smallText = context.getString(R.string.discord_rpc_description),
                smallImage = appIcon,
            ),
            buttons = buttons?.labels,
            metadata = buttons?.let { Metadata(it.urls) },
        ), manga.isNsfw())
    }

    private suspend fun mapActivity(activity: Activity): Activity {
        val hideButtons = activity.buttons?.any { it != null && it.utf8Size() > BUTTON_TEXT_LIMIT } ?: false
        return activity.copy(
            assets = activity.assets?.let {
                it.copy(largeImage = it.largeImage?.toWsrvProxy()?.toMediaProxyUrl(),
                    smallImage = it.smallImage?.toMediaProxyUrl())
            },
            buttons = activity.buttons.takeUnless { hideButtons },
            metadata = activity.metadata.takeUnless { hideButtons },
        )
    }

    suspend fun String.toMediaProxyUrl(): String? {
        if (repository.isMediaProxyUrl(this)) {
            return this
        }
        mpCache[this]?.let {
            return it
        }
        return runCatchingCancellable {
            repository.getMediaProxyUrl(this)
        }.onSuccess { url ->
            mpCache[this] = url
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrNull()
    }

    /**
     * Wrap an http(s) image URL through wsrv.nl so the eventual fetcher (Discord's media proxy)
     * receives a stable headerless URL. Pass-through for already-proxied URLs and for anything
     * that isn't a network image (e.g. existing Discord `mp:` URLs, app icons on local schemes).
     */
    private fun String.toWsrvProxy(): String {
        if (startsWith(WSRV_PREFIX, ignoreCase = true)) return this
        if (!startsWith("http://", ignoreCase = true) && !startsWith("https://", ignoreCase = true)) return this
        return WSRV_PREFIX + java.net.URLEncoder.encode(this, Charsets.UTF_8.name()) + "&we"
    }

    @Synchronized
    private fun getRpc(): DiscordTokenGateway? {
        if (!settings.isDiscordRpcEnabled || settings.isDiscordRpcOauth) return null
        return rpc ?: settings.discordToken?.let { token ->
            lateinit var client: DiscordTokenGateway
            client = DiscordTokenGateway.create(token, coroutineScope) {
                if (synchronized(this) { rpc === client }) updates.refresh()
            }
            client.also { rpc = it }
        }
    }
}
