package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page

/** Shared ABI used by optional novel wrappers. Their runtimes remain in the extension APK. */
interface NovelSource {
    val isNovelSource: Boolean get() = true

    suspend fun fetchPageText(page: Page): String
}
