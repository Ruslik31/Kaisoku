package org.koitharu.kotatsu.core.parser.tsuki

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.parser.BitmapWrapper
import org.koitharu.kotatsu.core.parser.DynamicParserManager
import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import org.koitharu.kotatsu.core.prefs.SourceSettings
import tsuki.MangaLoaderContext
import tsuki.MangaParser
import tsuki.bitmap.Bitmap
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaSource
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.koitharu.kotatsu.parsers.model.MangaSource as HostMangaSource

/**
 * Host services for tsuki.* parser plugins, backed by the app's own loader context.
 */
@Singleton
class TsukiLoaderContext @Inject constructor(
	@ApplicationContext private val androidContext: Context,
	@MangaHttpClient hostHttpClient: OkHttpClient,
	override val cookieJar: MutableCookieJar,
	val hostContext: MangaLoaderContextImpl,
) : MangaLoaderContext() {

	// Plugins tag requests with their own source type; re-tag them so the shared
	// header, captcha and interceptor chain can find the owning plugin repository.
	override val httpClient: OkHttpClient = hostHttpClient.newBuilder().apply {
		interceptors().add(0, SourceTagInterceptor)
	}.build()

	override fun newParserInstance(source: MangaSource): MangaParser =
		DynamicParserManager.createTsukiParser(hostSource(source), this, androidContext).parser

	override fun getParserSources(): List<MangaSource> = DynamicParserManager.getTsukiSources()

	override fun encodeBase64(data: ByteArray): String = hostContext.encodeBase64(data)

	override fun decodeBase64(data: String): ByteArray = hostContext.decodeBase64(data)

	override fun getPreferredLocales(): List<Locale> = hostContext.getPreferredLocales()

	@Deprecated("Provide a base url")
	override suspend fun evaluateJs(script: String): String? = hostContext.evaluateJs("", script)

	override suspend fun evaluateJs(baseUrl: String, script: String): String? = hostContext.evaluateJs(baseUrl, script)

	override fun requestBrowserAction(parser: MangaParser, url: String): Nothing =
		throw InteractiveActionRequiredException(hostSource(parser.source), url)

	override fun getConfig(source: MangaSource): MangaSourceConfig =
		TsukiSourceConfig(SourceSettings(androidContext, hostSource(source)))

	override fun getDefaultUserAgent(): String = hostContext.getDefaultUserAgent()

	override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response =
		hostContext.redrawImageResponse(response) { image ->
			(redraw(TsukiBitmap(image as BitmapWrapper)) as TsukiBitmap).wrapper
		}

	override fun createBitmap(width: Int, height: Int): Bitmap = TsukiBitmap(BitmapWrapper.create(width, height))

	private fun hostSource(source: MangaSource): HostMangaSource =
		DynamicParserManager.findPluginSource(source) ?: TsukiOrphanSource(source.name)

	private object SourceTagInterceptor : Interceptor {

		override fun intercept(chain: Interceptor.Chain): Response {
			val request = chain.request()
			val pluginSource = request.tag(MangaSource::class.java)
				?.takeIf { request.tag(HostMangaSource::class.java) == null }
				?.let { DynamicParserManager.findPluginSource(it) }
				?: return chain.proceed(request)
			return chain.proceed(
				request.newBuilder().tag(HostMangaSource::class.java, pluginSource).build(),
			)
		}
	}

	/** A plugin source that is no longer loaded; keeps settings and errors attributable. */
	private data class TsukiOrphanSource(override val name: String) : HostMangaSource
}
