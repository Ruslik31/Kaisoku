package org.koitharu.kotatsu.scrobbling.discord.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.AnyThread
import com.discord.oauth2rpc.API
import com.discord.oauth2rpc.DiscordAssetRegistrar
import com.discord.oauth2rpc.GatewayClient
import com.discord.oauth2rpc.GatewayConnectOptions
import com.discord.oauth2rpc.structures.RichPresence
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import java.io.File
import java.io.IOException
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
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
private const val DEBOUNCE_TIMEOUT = 3_000L
private const val PRESENCE_SCOPE = "sdk.social_layer_presence"
private const val TAG = "DiscordOauth"

@ViewModelScoped
class DiscordOauthRpc @Inject constructor(
    @LocalizedAppContext private val context: Context,
    private val settings: AppSettings,
    private val repository: DiscordRepository,
    private val imageLoader: ImageLoader,
    lifecycle: ViewModelLifecycle,
) {
    private class Client(val gateway: GatewayClient, val ready: CompletableDeferred<Unit>) {
        var connectJob: Job? = null
    }

    private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
    private val appId = context.getString(R.string.discord_app_id)
    private val appName = context.getString(R.string.app_name)
    private val appIcon = context.getString(R.string.app_icon_url)
    private val mpCache = Collections.synchronizedMap(HashMap<String, String>())
    private var rpc: Client? = null
    private var assetRegistrar: DiscordAssetRegistrar? = null
    private var registrarToken: String? = null
    private val apiInstance: Lazy<API> = lazy { API() }
    private val updates = DiscordPresenceDispatcher(
        scope = coroutineScope,
        debounceMillis = DEBOUNCE_TIMEOUT,
        now = SystemClock::elapsedRealtime,
        wallTime = System::currentTimeMillis,
        status = { DiscordPresenceStatus.fromPreference(settings.discordRpcStatus) },
        allowed = { nsfw -> settings.isDiscordRpcEnabled && settings.isDiscordRpcOauth &&
            !(settings.isDiscordRpcSkipNsfw && nsfw) },
        connect = {
            getRpc()?.also { client ->
                val connected = withTimeoutOrNull(30_000L) { client.ready.await(); true } ?: false
                if (!connected) throw IOException("Discord gateway connection timed out")
            }
        },
        prepare = { presence: RichPresence, nsfw: Boolean -> mapPresence(presence, nsfw) },
        send = { client: Client, packet ->
            synchronized(this) {
                rpc === client && client.gateway.send(3, mapOf(
                    "activities" to packet.activities, "status" to packet.status,
                    "since" to packet.since, "afk" to packet.afk,
                ))
            }
        },
        onError = { it.printStackTraceDebug(); closeClient() },
    )

    fun close() {
        clearRpc()
        if (apiInstance.isInitialized()) apiInstance.value.close()
    }

    fun clearRpc() {
        updates.clear()
        closeClient()
    }

    private fun closeClient() {
        val client = synchronized(this) { rpc.also { rpc = null } } ?: return
        client.connectJob?.cancel()
        client.gateway.disconnect()
    }

    fun setIdle() = updates.setIdle()
    fun refreshPresence() = updates.refresh()
    fun reconnectPresence() {
        closeClient()
        updates.refresh()
    }

    @AnyThread
    @Synchronized
    fun updateRpc(manga: Manga, state: ReaderUiState) {
        if (!settings.isDiscordRpcEnabled || (settings.isDiscordRpcSkipNsfw && manga.isNsfw())) {
            clearRpc()
            return
        }
        val coverUrl = manga.largeCoverUrl?.takeUnless { it.isBlank() }
            ?: manga.coverUrl?.takeUnless { it.isBlank() }
        val presence = RichPresence()
            .setApplicationId(appId).setName(appName).setDetails(manga.title)
            .setState(state.getChapterTitle(context.resources)).setType(3)
            .setStartTimestamp(updates.activity?.timestamps?.get("start") ?: System.currentTimeMillis())
            .setAssetsLargeImage(coverUrl).setAssetsLargeText(context.getString(R.string.reading_s, manga.title))
            .setAssetsSmallImage(appIcon).setAssetsSmallText(context.getString(R.string.discord_rpc_description))
        val buttons = buildDiscordRpcButtons(context.getString(R.string.url_discord),
            context.getString(R.string.telegram_group), BUTTON_TEXT_LIMIT)
        if (buttons != null) presence.setButtons(mapOf("name" to buttons.labels[0], "url" to buttons.urls[0]))
        updates.submit(presence, manga.isNsfw())
    }

    private suspend fun mapPresence(presence: RichPresence, isNsfw: Boolean): Map<String, Any?> =
        mapDiscordOauthActivity(presence, isNsfw) { url, nsfw -> url.toMediaProxyUrl(nsfw) }

    private suspend fun String.toMediaProxyUrl(isNsfw: Boolean): String? {
        if (repository.isMediaProxyUrl(this)) return this
        return mpCache[this] ?: runCatchingCancellable {
            val file = getCacheFile(this)
            val upload = file?.let { repository.getMediaProxyUrl(it) }
            val contentRating = if (isNsfw) 1 else 0
            if (upload != null) {
                getRegistrar()?.resolve(upload, contentRating)
            } else {
                getRegistrar()?.resolve(this, contentRating)
            }
        }.onSuccess { url -> url?.let { mpCache[this] = it } }
            .onFailure { it.printStackTraceDebug() }
            .getOrNull()
    }

    private suspend fun getCacheFile(url: String): File? {
        var snapshot = imageLoader.diskCache?.openSnapshot(url)
        if (snapshot == null) {
            val request = ImageRequest.Builder(context).data(url).build()
            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                snapshot = imageLoader.diskCache?.openSnapshot(url)
            }
        }
        return snapshot?.use { File(it.data.toString()) }
    }

    @Synchronized
    private fun getRpc(): Client? {
        if (!settings.isDiscordRpcEnabled || !settings.isDiscordRpcOauth) return null
        rpc?.let { return it }
        val token = settings.discordToken ?: return null
        if (!settings.discordScopes.orEmpty().contains(PRESENCE_SCOPE)) {
            Log.w(TAG, "token lacks presence scope; not connecting")
            return null
        }
        val client = Client(GatewayClient(), CompletableDeferred())
        rpc = client
        fun ready() {
            if (synchronized(this) { rpc !== client }) return
            client.ready.complete(Unit)
            updates.refresh()
        }
        client.gateway.onReady = { ready() }
        client.gateway.onResumed = { ready() }
        client.connectJob = coroutineScope.launch {
            try {
                var currentToken = token
                runCatchingCancellable { repository.checkToken(currentToken) }.onFailure {
                    repository.refreshToken()
                    currentToken = settings.discordToken ?: token
                }
                if (synchronized(this@DiscordOauthRpc) { rpc !== client }) return@launch
                client.gateway.connect(GatewayConnectOptions(token = currentToken))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                client.ready.completeExceptionally(e)
                e.printStackTraceDebug()
            }
        }
        return client
    }

    private fun getRegistrar(): DiscordAssetRegistrar? {
        val currentToken = settings.discordToken ?: return null
        if (assetRegistrar == null || registrarToken != currentToken) {
            registrarToken = currentToken
            assetRegistrar = DiscordAssetRegistrar(apiInstance.value, appId, currentToken)
        }
        return assetRegistrar
    }
}
