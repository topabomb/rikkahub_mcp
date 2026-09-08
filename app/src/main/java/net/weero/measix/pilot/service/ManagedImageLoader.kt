package net.weero.measix.pilot.service

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.ByteBufferMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
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

/** Checks the original selection before cache lookup and again before publishing decoded content. */
internal class ManagedImageInterceptor(private val files: FileManagementApplicationService) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.data as? ManagedFileKey ?: return chain.proceed()
        files.requireImageAccess(key)
        val result = chain.proceed()
        files.requireImageAccess(key)
        return result
    }
}

internal object ManagedImageKeyer : Keyer<ManagedFileKey> {
    override fun key(data: ManagedFileKey, options: Options): String =
        "managed-image:" + data.toString().encodeUtf8().sha256().hex()
}

internal class ManagedImageFetcherFactory(private val files: FileManagementApplicationService) : Fetcher.Factory<ManagedFileKey> {
    @OptIn(ExperimentalCoilApi::class)
    override fun create(data: ManagedFileKey, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        val bytes = files.readImage(data)
        SourceFetchResult(
            source = ImageSource(bytes.inputStream().source().buffer(), options.fileSystem, ByteBufferMetadata(ByteBuffer.wrap(bytes))),
            mimeType = ImageMime.sniff(bytes),
            dataSource = DataSource.DISK,
        )
    }
}
