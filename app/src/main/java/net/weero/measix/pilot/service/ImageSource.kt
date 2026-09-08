package net.weero.measix.pilot.service

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.ByteBufferMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource as CoilImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.key.Keyer
import coil3.request.ImageResult
import coil3.request.Options
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.source
import java.nio.ByteBuffer

enum class ImageOrigin { GENERATED, UPLOAD, NETWORK, INLINE, LOCAL }

/**
 * An owner's borrowed image read capability. Identity supports UI/cache reuse, never authorization.
 * The read callback must itself authorize and protect a bounded read; neither callback transfers ownership.
 */
class ImageSource internal constructor(
    cacheIdentity: String,
    val origin: ImageOrigin,
    val displayName: String? = null,
    val modifiedAtMillis: Long? = null,
    private val verifyAccess: suspend () -> Unit,
    private val readPayload: suspend () -> ByteArray,
) {
    internal val cacheKey = "image-source:" + cacheIdentity.encodeUtf8().sha256().hex()

    internal suspend fun requireAccess() {
        currentCoroutineContext().ensureActive()
        verifyAccess()
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun readBytes(): ByteArray {
        currentCoroutineContext().ensureActive()
        val bytes = readPayload()
        currentCoroutineContext().ensureActive()
        return bytes
    }

    override fun equals(other: Any?): Boolean = other is ImageSource && cacheKey == other.cacheKey
    override fun hashCode(): Int = cacheKey.hashCode()
    override fun toString(): String = "ImageSource($cacheKey)"
}

/** Authorization surrounds cache lookup and decoded-result delivery without holding an owner lock. */
internal object ImageSourceInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val source = chain.request.data as? ImageSource ?: return chain.proceed()
        source.requireAccess()
        val result = chain.proceed()
        source.requireAccess()
        return result
    }
}

internal object ImageSourceKeyer : Keyer<ImageSource> {
    override fun key(data: ImageSource, options: Options): String = data.cacheKey
}

internal object ImageSourceFetcherFactory : Fetcher.Factory<ImageSource> {
    @OptIn(ExperimentalCoilApi::class)
    override fun create(data: ImageSource, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        val bytes = data.readBytes()
        SourceFetchResult(
            source = CoilImageSource(bytes.inputStream().source().buffer(), options.fileSystem, ByteBufferMetadata(ByteBuffer.wrap(bytes))),
            mimeType = ImageMime.sniff(bytes),
            dataSource = if (data.origin == ImageOrigin.NETWORK) DataSource.NETWORK else DataSource.DISK,
        )
    }
}
