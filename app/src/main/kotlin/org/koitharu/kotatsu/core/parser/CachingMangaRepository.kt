package org.koitharu.kotatsu.core.parser

import android.util.Log
import androidx.collection.MutableLongSet
import coil3.request.CachePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.cache.SafeDeferred
import org.koitharu.kotatsu.core.model.isSameEntryAs
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable

abstract class CachingMangaRepository(
	private val cache: MemoryContentCache,
) : MangaRepository {

    internal var onFreshDetails: (suspend (Manga) -> Unit)? = null

    internal var beforeDetails: (suspend (Manga) -> Manga?)? = null

    final override suspend fun getLightweightMetadata(manga: Manga): Manga? =
        beforeDetails?.invoke(manga)?.copy(chapters = null) ?: getLightweightMetadataImpl(manga)

    protected open suspend fun getLightweightMetadataImpl(manga: Manga): Manga? = null

	private val detailsMutex = MultiMutex<Long>()
	private val relatedMangaMutex = MultiMutex<Long>()
	private val pagesMutex = MultiMutex<MemoryContentCache.Key>()

	final override suspend fun getDetails(manga: Manga): Manga = getDetails(manga, CachePolicy.ENABLED)

    final override suspend fun refreshDetails(manga: Manga): Manga =
        getDetailsCached(manga, CachePolicy.WRITE_ONLY, explicitRefresh = true)

    final override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = getPagesCached(null, chapter)

    final override suspend fun getPages(manga: Manga, chapter: MangaChapter): List<MangaPage> =
        getPagesCached(manga, chapter)

    private suspend fun getPagesCached(manga: Manga?, chapter: MangaChapter): List<MangaPage> =
        pagesMutex.withLock(MemoryContentCache.Key(source, chapter.url, manga?.url)) {
		cache.getPages(source, chapter.url, manga?.url)?.let { return it }
		val pages = asyncSafe {
			(if (manga == null) getPagesImpl(chapter) else getPagesImpl(manga, chapter)).distinctById()
		}
		cache.putPages(source, chapter.url, pages, manga?.url)
		pages
	}.await()

	final override suspend fun getRelated(seed: Manga): List<Manga> = relatedMangaMutex.withLock(seed.id) {
		cache.getRelatedManga(source, seed.url)?.let { return it }
		val related = asyncSafe {
			getRelatedMangaImpl(seed).filterNot { it.isSameEntryAs(seed) }
		}
		cache.putRelatedManga(source, seed.url, related)
		related
	}.await()

    suspend fun getDetails(manga: Manga, cachePolicy: CachePolicy): Manga =
        getDetailsCached(manga, cachePolicy, explicitRefresh = false)

    private suspend fun getDetailsCached(manga: Manga, cachePolicy: CachePolicy, explicitRefresh: Boolean): Manga =
        detailsMutex.withLock(manga.id) {
            if (!explicitRefresh) beforeDetails?.invoke(manga)?.let { return it }
            if (cachePolicy.readEnabled) {
                cache.getDetails(source, manga.url)?.let { return it }
            }
            lateinit var details: SafeDeferred<Manga>
            details = asyncSafe(start = CoroutineStart.LAZY) {
                // A queued request must honor a choice enabled after it left the calling coroutine.
                if (!explicitRefresh) beforeDetails?.invoke(manga)?.let {
                    cache.removeDetails(source, manga.url, details)
                    return@asyncSafe it
                }
                getDetailsImpl(manga).also { onFreshDetails?.invoke(it) }
            }
            if (cachePolicy.writeEnabled) {
                cache.putDetails(source, manga.url, details)
            }
            details
        }.await()

	suspend fun peekDetails(manga: Manga): Manga? {
		return cache.getDetails(source, manga.url)
	}

	fun invalidateCache() {
		cache.clear(source)
	}

	protected abstract suspend fun getDetailsImpl(manga: Manga): Manga

	protected abstract suspend fun getRelatedMangaImpl(seed: Manga): List<Manga>

	protected abstract suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage>

    protected open suspend fun getPagesImpl(manga: Manga, chapter: MangaChapter): List<MangaPage> = getPagesImpl(chapter)

    private suspend fun <T> asyncSafe(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T,
    ): SafeDeferred<T> {
		var dispatcher = currentCoroutineContext()[CoroutineDispatcher.Key]
		if (dispatcher == null || dispatcher is MainCoroutineDispatcher) {
			dispatcher = Dispatchers.Default
		}
		return SafeDeferred(
			processLifecycleScope.async(dispatcher, start = start) {
				runCatchingCancellable { block() }
			},
		)
	}

	private fun List<MangaPage>.distinctById(): List<MangaPage> {
		if (isEmpty()) {
			return emptyList()
		}
		val result = ArrayList<MangaPage>(size)
		val set = MutableLongSet(size)
		for (page in this) {
			if (set.add(page.id)) {
				result.add(page)
			} else if (BuildConfig.DEBUG) {
				Log.w(null, "Duplicate page: $page")
			}
		}
		return result
	}
}
