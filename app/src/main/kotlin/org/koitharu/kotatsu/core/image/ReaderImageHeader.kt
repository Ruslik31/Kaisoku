package org.koitharu.kotatsu.core.image

/** Sniff bytes only after native decoding fails; extensions and HTTP MIME labels are not evidence. */
internal object ReaderImageHeader {

    enum class Format { JPEG, AVIF }

    fun detect(header: ByteArray, size: Int): Format? {
        require(size in 0..header.size)
        if (size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() &&
            header[2] == 0xff.toByte()
        ) return Format.JPEG
        if (size < 16 || String(header, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
        val boxSize = ((header[0].toLong() and 255) shl 24) or ((header[1].toLong() and 255) shl 16) or
            ((header[2].toLong() and 255) shl 8) or (header[3].toLong() and 255)
        if (boxSize < 16 || boxSize > size || boxSize % 4 != 0L) return null
        // Minor version at offset 12 is not a brand. Accept complete ftyp boxes only.
        for (offset in 8 until boxSize.toInt() step 4) {
            if (offset == 12) continue
            val brand = String(header, offset, 4, Charsets.ISO_8859_1)
            if (brand == "avif" || brand == "avis") return Format.AVIF
        }
        return null
    }
}
