package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Native decoders retain their usual tiling/pooling behavior for supported files. */
object ReaderImageDecoders {

    fun install(view: SubsamplingScaleImageView, config: Bitmap.Config): Boolean {
        val pooled = !view.context.isLowRamDevice()
        val regions = view.regionDecoderFactory
        val bitmaps = view.bitmapDecoderFactory
        if (regions is ReaderRegionDecoderFactory && regions.bitmapConfig == config && regions.pooled == pooled &&
            bitmaps is ReaderBitmapDecoderFactory && bitmaps.bitmapConfig == config
        ) return false
        view.regionDecoderFactory = ReaderRegionDecoderFactory(config, pooled)
        view.bitmapDecoderFactory = ReaderBitmapDecoderFactory(config)
        return true
    }
}

internal class ReaderRegionDecoderFactory(
    override val bitmapConfig: Bitmap.Config,
    val pooled: Boolean,
) : DecoderFactory<ImageRegionDecoder> {

    override fun make(): ImageRegionDecoder = ReaderRegionDecoder(
        bitmapConfig,
        if (pooled) SkiaPooledImageRegionDecoder.Factory(bitmapConfig).make()
        else SkiaImageRegionDecoder.Factory(bitmapConfig).make(),
    )
}

internal class ReaderBitmapDecoderFactory(
    override val bitmapConfig: Bitmap.Config,
) : DecoderFactory<ImageDecoder> {

    override fun make(): ImageDecoder = ReaderBitmapDecoder(bitmapConfig, SkiaImageDecoder.Factory(bitmapConfig).make())
}

internal class ReaderRegionDecoder(
    private val config: Bitmap.Config,
    private var nativeDecoder: ImageRegionDecoder?,
) : ImageRegionDecoder {

    // Read locks preserve pooled native parallelism. Switching/recycling waits for all native tile reads.
    private val lock = ReentrantReadWriteLock(true)
    private var context: Context? = null
    private var uri: Uri? = null
    private var dimensions: Point? = null
    private var compatibility: ReaderCompatibilityBitmap? = null
    private var failure: Exception? = null
    private var recycled = false

    override fun init(context: Context, uri: Uri): Point = lock.write {
        check(!recycled && this.context == null) { "Reader decoder is already initialized or recycled" }
        this.context = context.applicationContext
        this.uri = uri
        val decoder = checkNotNull(nativeDecoder)
        val size = try {
            decoder.init(context, uri)
        } catch (error: ImageDecodeException) {
            return@write recover(error)
        } catch (error: IOException) {
            if (error.message != "Image format not supported") throw error
            return@write recover(error)
        } catch (error: RuntimeException) {
            if (error.message != "Cannot instantiate BitmapRegionDecoder") throw error
            return@write recover(error)
        }
        dimensions = Point(size)
        size
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        var rejection: Exception? = null
        lock.read {
            check(!recycled) { "Reader decoder is recycled" }
            failure?.let { throw it }
            compatibility?.let { return ReaderImageCompatibility.tile(it.bitmap, sRect, sampleSize, config) }
            val decoder = checkNotNull(nativeDecoder) { "Reader image decoder is unavailable" }
            try {
                return decoder.decodeRegion(sRect, sampleSize)
            } catch (error: ImageDecodeException) {
                // The pooled decoder reports its null-tile rejection as ImageDecodeException.
                rejection = error
            } catch (error: IllegalStateException) {
                if (error.message != NULL_TILE_REJECTION) throw error
                rejection = error
            }
        }
        return lock.write {
            check(!recycled) { "Reader decoder is recycled" }
            failure?.let { throw it }
            if (compatibility == null) recover(checkNotNull(rejection))
            ReaderImageCompatibility.tile(checkNotNull(compatibility).bitmap, sRect, sampleSize, config)
        }
    }

    override val isReady: Boolean
        get() = lock.read { !recycled && (compatibility != null || nativeDecoder?.isReady == true) }

    override fun recycle() = lock.write {
        recycled = true
        nativeDecoder?.recycle()
        nativeDecoder = null
        compatibility?.close()
        compatibility = null
        context = null
        uri = null
        failure = null
    }

    private fun recover(original: Exception): Point {
        // Native workspace is released before reserving any full-image memory.
        nativeDecoder?.recycle()
        nativeDecoder = null
        ReaderDecodeLimits.checkInterrupted()
        val decoded = try {
            ReaderImageCompatibility.decode(checkNotNull(context), checkNotNull(uri), config, tryBitmapFactory = true)
        } catch (error: IOException) {
            original.addSuppressed(error)
            failure = original
            throw original
        }
        var retained = false
        try {
            ReaderDecodeLimits.checkInterrupted()
            val bitmap = decoded.bitmap
            val originalSize = dimensions
            if (originalSize != null && (bitmap.width != originalSize.x || bitmap.height != originalSize.y)) {
                original.addSuppressed(IOException("Alternate reader decoder changed source dimensions"))
                failure = original
                throw original
            }
            compatibility = decoded
            retained = true
            return Point(bitmap.width, bitmap.height).also { dimensions = it }
        } finally {
            if (!retained) decoded.close()
        }
    }

    companion object {
        const val NULL_TILE_REJECTION =
            "Skia image decoder returned null bitmap - image format may not be supported"
    }
}

internal class ReaderBitmapDecoder(
    private val config: Bitmap.Config,
    private val nativeDecoder: ImageDecoder,
) : ImageDecoder {

    override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap {
        require(sampleSize > 0)
        return try {
            nativeDecoder.decode(context, uri, sampleSize)
        } catch (error: ImageDecodeException) {
            val decoded = try {
                // BitmapFactory is the failed native implementation, so only try its actual alternate.
                ReaderImageCompatibility.decode(context, uri, config, tryBitmapFactory = false)
            } catch (alternate: IOException) {
                error.addSuppressed(alternate)
                throw error
            }
            decoded.use {
                ReaderDecodeLimits.checkInterrupted()
                val bitmap = decoded.bitmap
                if (sampleSize == 1 && bitmap.config == config) decoded.takeBitmap()
                else ReaderImageCompatibility.tile(bitmap, Rect(0, 0, bitmap.width, bitmap.height), sampleSize, config)
            }
        }
    }
}
