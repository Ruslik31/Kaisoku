package org.koitharu.kotatsu.sync.drive

internal object DriveTransferPolicy {

	fun acknowledgedOffset(range: String?): Long = range
		?.substringAfterLast('-')
		?.toLongOrNull()
		?.plus(1)
		?: 0L

	fun contentRangeStart(contentRange: String?): Long? = contentRange
		?.substringAfter("bytes ")
		?.substringBefore('-')
		?.toLongOrNull()

	fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 429 || code >= 500
}
