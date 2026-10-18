package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.core.parser.mihon.repo.MihonAvailableExtension
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Another implementation of the same site. [extension] is set when it must be installed first.
 */
data class SourceSwitchCandidate(
	val source: MangaSource,
	val kind: Kind,
	val title: String,
	val origin: String?,
	val matchedByHost: Boolean,
	val extension: MihonAvailableExtension? = null,
) {

	val needsInstall: Boolean
		get() = extension != null

	enum class Kind { PLUGIN, BUILT_IN, EXTENSION }
}
