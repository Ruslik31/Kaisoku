package org.koitharu.kotatsu.core.exceptions

import okio.IOException
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * A plugin needs a value from the user (a password, a token) before it can continue.
 * The value is stored in the source settings under [key] and read back on the next attempt.
 */
class InputRequiredException(
	val source: MangaSource,
	val prompt: String,
	val key: String,
	cause: Throwable? = null,
) : IOException(prompt, cause)
