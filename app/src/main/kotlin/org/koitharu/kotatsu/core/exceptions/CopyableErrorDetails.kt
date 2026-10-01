package org.koitharu.kotatsu.core.exceptions

/** Supplies useful server or provider diagnostics that are not part of a short UI message. */
interface CopyableErrorDetails {
	val copyableErrorDetails: String
}
