@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

/**
 * Mihon-compatible SChapter interface.
 * Ported from Mihon source-api for extension compatibility.
 */
interface SChapter : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    /**
     * Source-private metadata container added in tachiyomix 1.6.
     */
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}

/**
 * Default implementation of SChapter.
 */
class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    override var memo: JsonObject = JsonObject(emptyMap())
}
