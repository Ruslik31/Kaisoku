package org.koitharu.kotatsu.reader.domain

import androidx.annotation.CheckResult
import androidx.collection.LongSparseArray
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject

private const val PAGES_TRIM_THRESHOLD = 120

@ViewModelScoped
class ChaptersLoader internal constructor(
	private val repositoryForSource: (MangaSource) -> MangaRepository,
) {
	@Inject
	constructor(mangaRepositoryFactory: MangaRepository.Factory) : this(mangaRepositoryFactory::create)

	private val chapters = LongSparseArray<MangaChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()
    private lateinit var manga: Manga

	val size: Int
		get() = chapters.size()

	suspend fun init(manga: MangaDetails) = mutex.withLock {
        this.manga = manga.toManga()
		chapters.clear()
		manga.allChapters.forEach {
			chapters.put(it.id, it)
		}
	}

	suspend fun loadPrevNextChapter(
		manga: MangaDetails,
		currentId: Long,
		isNext: Boolean,
		reversed: Boolean = false,
	): Boolean {
		val chapters = itemsInReadingOrder(manga.allChapters, reversed)
		val predicate: (MangaChapter) -> Boolean = { it.id == currentId }
		val index = if (isNext) chapters.indexOfFirst(predicate) else chapters.indexOfLast(predicate)
		if (index == -1) return false
		val newChapter = chapters.getOrNull(if (isNext) index + 1 else index - 1) ?: return false
		if (mutex.withLock { newChapter.id in chapterPages }) return false
		val newPages = loadChapter(newChapter.id)
		if (newPages.isEmpty()) return false
		return mutex.withLock {
			currentCoroutineContext().ensureActive()
			if (newChapter.id in chapterPages) return@withLock false
			if (chapterPages.chaptersSize > 1) {
				// trim pages
				if (chapterPages.size > PAGES_TRIM_THRESHOLD) {
					if (isNext) {
						chapterPages.removeFirst()
					} else {
						chapterPages.removeLast()
					}
				}
			}
			if (isNext) {
				chapterPages.addLast(newChapter.id, newPages)
			} else {
				chapterPages.addFirst(newChapter.id, newPages)
			}
		}
	}

	@CheckResult
	suspend fun loadSingleChapter(chapterId: Long): Boolean {
		val pages = loadChapter(chapterId)
		return mutex.withLock {
			chapterPages.clear()
			chapterPages.addLast(chapterId, pages)
			pages.isNotEmpty()
		}
	}

	fun peekChapter(chapterId: Long): MangaChapter? = chapters[chapterId]

	/** Keep the visible chapter without fetching it again; old-direction preloads are discarded. */
	suspend fun retainVisibleChapter(chapterId: Long, pages: List<ReaderPage>) = mutex.withLock {
		chapterPages.retainVisibleChapter(chapterId, pages)
	}

	fun hasPages(chapterId: Long): Boolean {
		return chapterId in chapterPages
	}

	fun getPages(chapterId: Long): List<MangaPage> = synchronized(chapterPages) {
		return chapterPages.subList(chapterId).map { it.toMangaPage() }
	}

	fun getPagesCount(chapterId: Long): Int {
		return chapterPages.size(chapterId)
	}

	fun last() = chapterPages.last()

	fun first() = chapterPages.first()

	fun snapshot() = chapterPages.toList()

	private suspend fun loadChapter(chapterId: Long): List<ReaderPage> {
		val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
		val repo = repositoryForSource(chapter.source)
		return repo.getPages(manga, chapter).mapIndexed { index, page ->
			ReaderPage(page, index, chapterId)
		}
	}
}
