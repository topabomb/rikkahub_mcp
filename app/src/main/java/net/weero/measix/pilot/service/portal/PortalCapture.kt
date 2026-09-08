package net.weero.measix.pilot.service.portal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PortalCapturePhase { PERMISSION, PREPARING, READY, CAPTURING, FINISHED }

@MainThread
internal interface PortalCaptureOperation {
    val permission: String
    val phase: StateFlow<PortalCapturePhase>
    val preview: StateFlow<View?>
    val result: Deferred<Unit>
    fun permissionResult(granted: Boolean)
    fun start()
    fun stop()
    fun cancel()
    fun close(): Deferred<Unit>
}

internal fun interface PortalCaptureFactory {
    @MainThread
    fun create(capture: PortalMediaCapture, maxDurationSeconds: Int?, admit: suspend (() -> Unit) -> Unit): PortalCaptureOperation
}

internal class AndroidPortalCaptureFactory(
    private val context: Context,
    private val scope: CoroutineScope,
) : PortalCaptureFactory {
    override fun create(capture: PortalMediaCapture, maxDurationSeconds: Int?,
        admit: suspend (() -> Unit) -> Unit): PortalCaptureOperation {
        requireCaptureMain()
        scope.coroutineContext.ensureActive()
        val operation = if (maxDurationSeconds == null) {
            if (capture.mimeType != PortalMediaStore.JPEG) throw PortalFailure("invalid_request")
            PortalPhotoCapture(context, scope, capture, admit)
        } else {
            if (capture.mimeType != PortalMediaStore.AUDIO_MP4 || maxDurationSeconds !in 1..60) {
                throw PortalFailure("invalid_request")
            }
            PortalAudioCapture(context, scope, capture, maxDurationSeconds, admit)
        }
        operation.watchParent(parentScope = scope)
        return operation
    }
}

/** Admission can wait for Session; hardware and writer completion never depend on that job finishing. */
private abstract class AndroidPortalCapture(
    protected val context: Context,
    parentScope: CoroutineScope,
    protected val capture: PortalMediaCapture,
    private val admit: suspend (() -> Unit) -> Unit,
) : PortalCaptureOperation {
    protected val main = Handler(Looper.getMainLooper())
    protected val phases = MutableStateFlow(PortalCapturePhase.PERMISSION)
    protected val previews = MutableStateFlow<View?>(null)
    final override val phase: StateFlow<PortalCapturePhase> = phases.asStateFlow()
    final override val preview: StateFlow<View?> = previews.asStateFlow()
    private val completed = CompletableDeferred<Unit>()
    final override val result: Deferred<Unit> = completed
    private val admissions = SupervisorJob(parentScope.coroutineContext[Job])
    private val admissionScope = CoroutineScope(parentScope.coroutineContext + admissions + Dispatchers.Main.immediate)
    private val writerRoot = SupervisorJob()
    private val writerScope = CoroutineScope(writerRoot + Dispatchers.IO)
    private var writer: Job? = null
    private var writerPending = false
    protected var closing = false
        private set
    private var outcome: PortalFailure? = null
    private var cleanupFailure: PortalFailure? = null
    private var closeAttempt: CompletableDeferred<Unit>? = null
    private var cleaningHardware = false
    private val deadline = Runnable { finish(PortalFailure("timeout")) }
    private var parentWatch: Job? = null

    init { main.postDelayed(deadline, 120_000) }

    fun watchParent(parentScope: CoroutineScope) {
        val watch = parentScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try { awaitCancellation() }
            finally { if (!closing) finish(PortalFailure("user_cancelled")) }
        }
        parentWatch = watch
        watch.start()
        if (!watch.isActive && !closing) finish(PortalFailure("user_cancelled"))
    }

    final override fun permissionResult(granted: Boolean) {
        requireCaptureMain()
        if (closing || phases.value != PortalCapturePhase.PERMISSION) return
        if (!granted || context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            finish(PortalFailure("permission_denied"))
            return
        }
        try { prepare() } catch (error: Exception) { finish(captureFailure(error)) }
    }

    protected abstract fun prepare()
    /** Return true only after every owned device, session, buffer and recorder has actually closed. */
    protected abstract fun releaseHardware(): Boolean

    protected fun admitted(operation: () -> Unit) {
        if (closing) return
        admissionScope.launch {
            try {
                admit {
                    requireCaptureMain()
                    if (!closing) {
                        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                            throw PortalFailure("permission_denied")
                        }
                        operation()
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!closing) finish(PortalFailure("user_cancelled"))
                throw cancelled
            } catch (error: Exception) { if (!closing) finish(captureFailure(error)) }
        }
    }

    protected fun writePhoto(bytes: ByteArray, rotation: Int) {
        check(!writerPending)
        writerPending = true
        val pending = writerScope.async(start = CoroutineStart.LAZY) {
            capture.file.outputStream().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    ensureActive()
                    val count = minOf(65536, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
            }
            ensureActive()
            ExifInterface(capture.file.absolutePath).apply {
                val orientation = when (rotation) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            if (capture.file.length() > PortalMediaStore.MAX_ITEM_BYTES) throw PortalFailure("resource_limit")
        }
        writer = pending
        pending.invokeOnCompletion { failure ->
            main.post {
                writerPending = false
                if (!closing) finish(failure?.let(::captureFailure)) else continueClose()
            }
        }
        pending.start()
    }

    protected fun finish(failure: PortalFailure?) {
        requireCaptureMain()
        if (!closing) {
            closing = true
            outcome = failure
            phases.value = PortalCapturePhase.FINISHED
            previews.value = null
            main.removeCallbacks(deadline)
            admissions.cancel()
            parentWatch?.cancel()
            if (failure != null) writer?.cancel()
        }
        close()
    }

    protected fun recordResultFailure(failure: PortalFailure) {
        if (outcome == null) outcome = failure
    }

    final override fun cancel() {
        requireCaptureMain()
        finish(PortalFailure("user_cancelled"))
    }

    final override fun close(): Deferred<Unit> {
        requireCaptureMain()
        if (!closing) {
            finish(PortalFailure("user_cancelled"))
            return requireNotNull(closeAttempt)
        }
        closeAttempt?.takeIf { it.isActive || !it.isCancelled }?.let { return it }
        cleanupFailure = null
        return CompletableDeferred<Unit>().also {
            closeAttempt = it
            continueClose()
        }
    }

    protected fun continueClose() {
        requireCaptureMain()
        if (!closing || cleaningHardware) return
        cleaningHardware = true
        val hardwareClosed = try { releaseHardware() }
        catch (error: Exception) { cleanupFailure = captureFailure(error); false }
        finally { cleaningHardware = false }
        if (writerPending) return
        val failure = cleanupFailure
        if (failure != null) {
            val original = outcome ?: failure.also { outcome = it }
            if (original !== failure && original.suppressed.none { it === failure }) original.addSuppressed(failure)
            completed.completeExceptionally(original)
            closeAttempt?.completeExceptionally(failure)
        } else if (hardwareClosed) {
            outcome?.let { completed.completeExceptionally(it) } ?: completed.complete(Unit)
            closeAttempt?.complete(Unit)
            writerRoot.cancel()
        }
    }
}

private class PortalPhotoCapture(
    context: Context,
    scope: CoroutineScope,
    capture: PortalMediaCapture,
    admit: suspend (() -> Unit) -> Unit,
) : AndroidPortalCapture(context, scope, capture, admit) {
    override val permission = Manifest.permission.CAMERA
    private val manager = context.getSystemService(CameraManager::class.java)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private var configuring = false
    private var configurationQueued = false
    private var deviceCloseRequested = false
    private var deviceClosed = false
    private var sessionCloseRequested = false
    private var reader: ImageReader? = null
    private var texture: SurfaceTexture? = null
    private var textureDetached = false
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var previewSize: Size? = null
    private var sensorRotation = 0
    private var front = false
    private var imageReceived = false
    private var rotation = 0
    private var focusMode = CaptureRequest.CONTROL_AF_MODE_OFF

    @SuppressLint("MissingPermission")
    override fun prepare() {
        phases.value = PortalCapturePhase.PREPARING
        val cameras = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
        val selected = cameras.firstOrNull { it.second[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
            ?: cameras.firstOrNull() ?: throw PortalFailure("media_unavailable")
        val characteristics = selected.second
        sensorRotation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        front = characteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        if (characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]
                ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
            focusMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        val formats = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: throw PortalFailure("media_unavailable")
        val jpegSizes = formats.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val photoSize = jpegSizes.filter { it.width.toLong() * it.height <= 12_000_000 }.maxByOrNull { it.width.toLong() * it.height }
            ?: jpegSizes.minByOrNull { it.width.toLong() * it.height } ?: throw PortalFailure("media_unavailable")
        val previews = formats.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        previewSize = previews.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height }
            ?: previews.minByOrNull { it.width.toLong() * it.height } ?: throw PortalFailure("media_unavailable")
        reader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2).also {
            it.setOnImageAvailableListener(::imageAvailable, main)
        }
        val textureView = TextureView(context).also { view = it }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                texture = value
                if (closing) { continueClose(); return }
                transformPreview(width, height)
                configure()
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { transformPreview(width, height) }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                texture = value
                textureDetached = true
                if (!closing) cancel() else continueClose()
                return false
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
        }
        this.previews.value = textureView
        admitted {
            opening = true
            try { manager.openCamera(selected.first, deviceCallback, main) }
            catch (error: Exception) { opening = false; throw error }
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (deviceClosed) return
            opening = false
            device = camera
            if (closing) continueClose() else configure()
        }
        override fun onDisconnected(camera: CameraDevice) {
            if (deviceClosed) return
            opening = false
            device = camera
            if (!closing) finish(PortalFailure("media_unavailable")) else continueClose()
        }
        override fun onError(camera: CameraDevice, error: Int) {
            if (deviceClosed) return
            opening = false
            device = camera
            if (!closing) finish(PortalFailure("media_unavailable")) else continueClose()
        }
        override fun onClosed(camera: CameraDevice) {
            opening = false
            if (device === camera) {
                deviceClosed = true
                device = null
                // Device closure also closes its sessions; it may suppress their remaining capture callbacks.
                session = null
                configuring = false
            }
            if (!closing) finish(PortalFailure("media_unavailable")) else continueClose()
        }
    }

    @Suppress("DEPRECATION")
    private fun configure() {
        if (closing || device == null || texture == null || configurationQueued || configuring || session != null) return
        // Claim the attempt before admission can suspend, so two readiness callbacks cannot create two sessions.
        configurationQueued = true
        admitted {
            configurationQueued = false
            val size = requireNotNull(previewSize)
            requireNotNull(texture).setDefaultBufferSize(size.width, size.height)
            val output = Surface(requireNotNull(texture)).also { surface = it }
            configuring = true
            try {
                requireNotNull(device).createCaptureSession(listOf(output, requireNotNull(reader).surface), sessionCallback, main)
            } catch (error: Exception) { configuring = false; throw error }
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(configured: CameraCaptureSession) {
            if (deviceClosed) return
            configuring = false
            session = configured
            if (closing) { continueClose(); return }
            admitted {
                configured.setRepeatingRequest(requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(requireNotNull(surface))
                    set(CaptureRequest.CONTROL_AF_MODE, focusMode)
                }.build(), null, main)
                phases.value = PortalCapturePhase.READY
            }
        }
        override fun onConfigureFailed(configured: CameraCaptureSession) {
            // Android declares a failed configuration already closed; calling close on it is invalid.
            configuring = false
            session = null
            if (!closing) finish(PortalFailure("media_unavailable")) else continueClose()
        }
        override fun onClosed(closed: CameraCaptureSession) {
            configuring = false
            if (session === closed) session = null
            if (!closing) finish(PortalFailure("media_unavailable")) else continueClose()
        }
    }

    override fun start() {
        requireCaptureMain()
        if (closing || phases.value != PortalCapturePhase.READY) return
        phases.value = PortalCapturePhase.CAPTURING
        admitted {
            rotation = imageRotation()
            val request = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(requireNotNull(reader).surface)
                set(CaptureRequest.CONTROL_AF_MODE, focusMode)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                // Preserve sensor pixels and apply display orientation once in the adapter's Exif write.
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }.build()
            requireNotNull(session).capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (!closing) finish(PortalFailure("media_unavailable"))
                }
                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    if (!closing) finish(PortalFailure("user_cancelled"))
                }
            }, main)
        }
    }

    override fun stop() { requireCaptureMain(); cancel() }

    private fun imageAvailable(source: ImageReader) {
        try {
            source.acquireLatestImage()?.use { image ->
                if (closing || imageReceived || phases.value != PortalCapturePhase.CAPTURING) return
                if (image.format != ImageFormat.JPEG || image.planes.size != 1) throw PortalFailure("media_unavailable")
                val buffer = image.planes.single().buffer
                if (buffer.remaining() !in 1..PortalMediaStore.MAX_ITEM_BYTES) throw PortalFailure("resource_limit")
                val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                imageReceived = true
                writePhoto(bytes, rotation)
            }
        } catch (error: Exception) { if (!closing) finish(captureFailure(error)) }
    }

    override fun releaseHardware(): Boolean {
        var failure: Exception? = null
        fun attempt(operation: () -> Unit) {
            try { operation() } catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        if (!sessionCloseRequested) session?.let { owned -> attempt { owned.close(); sessionCloseRequested = true } }
        if (!deviceCloseRequested) device?.let { owned -> attempt { owned.close(); deviceCloseRequested = true } }
        if (!textureDetached) view?.let { owned -> attempt { (owned.parent as? ViewGroup)?.removeView(owned) } }
        failure?.let { throw it }
        if (opening || configuring || session != null || device != null) return false
        reader?.let { owned -> attempt { owned.close(); reader = null } }
        surface?.let { owned -> attempt { owned.release(); surface = null } }
        // A detached TextureView explicitly hands SurfaceTexture release back to this owner.
        if (textureDetached) texture?.let { owned -> attempt { owned.release(); texture = null } }
        failure?.let { throw it }
        return texture == null
    }

    private fun imageRotation(): Int {
        val displayRotation = when (view?.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sign = if (front) -1 else 1
        return (sensorRotation - displayRotation * sign + 360) % 360
    }

    private fun transformPreview(width: Int, height: Int) {
        val target = view ?: return
        val size = previewSize ?: return
        val rotation = target.display?.rotation ?: Surface.ROTATION_0
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val buffer = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        val matrix = Matrix()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            buffer.offset(viewRect.centerX() - buffer.centerX(), viewRect.centerY() - buffer.centerY())
            matrix.setRectToRect(viewRect, buffer, Matrix.ScaleToFit.FILL)
            val scale = maxOf(height.toFloat() / size.height, width.toFloat() / size.width)
            matrix.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
            matrix.postRotate(90f * (rotation - 2), viewRect.centerX(), viewRect.centerY())
        } else if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, viewRect.centerX(), viewRect.centerY())
        target.setTransform(matrix)
    }
}

private class PortalAudioCapture(
    context: Context,
    scope: CoroutineScope,
    capture: PortalMediaCapture,
    private val maxDurationSeconds: Int,
    admit: suspend (() -> Unit) -> Unit,
) : AndroidPortalCapture(context, scope, capture, admit) {
    override val permission = Manifest.permission.RECORD_AUDIO
    private var recorder: MediaRecorder? = null
    private var recording = false
    private val durationLimit = Runnable { stop() }

    override fun prepare() { phases.value = PortalCapturePhase.READY }

    @Suppress("DEPRECATION")
    override fun start() {
        requireCaptureMain()
        if (closing || phases.value != PortalCapturePhase.READY) return
        phases.value = PortalCapturePhase.PREPARING
        admitted {
            val owned = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).also { recorder = it }
            owned.setOnErrorListener { _, _, _ -> if (!closing) finish(PortalFailure("media_unavailable")) }
            owned.setOnInfoListener { _, what, _ ->
                if (!closing) when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> stop()
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> finish(PortalFailure("resource_limit"))
                }
            }
            owned.setAudioSource(MediaRecorder.AudioSource.MIC)
            owned.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            owned.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            owned.setAudioEncodingBitRate(128_000)
            owned.setAudioSamplingRate(44_100)
            owned.setMaxDuration(maxDurationSeconds * 1000)
            owned.setMaxFileSize(PortalMediaStore.MAX_ITEM_BYTES.toLong())
            owned.setOutputFile(capture.file.absolutePath)
            owned.prepare()
            admitted {
                owned.start()
                recording = true
                phases.value = PortalCapturePhase.CAPTURING
                main.postDelayed(durationLimit, maxDurationSeconds * 1000L)
            }
        }
    }

    override fun stop() {
        requireCaptureMain()
        if (!closing && phases.value == PortalCapturePhase.CAPTURING) finish(null)
    }

    override fun releaseHardware(): Boolean {
        main.removeCallbacks(durationLimit)
        val owned = recorder ?: return true
        var stopFailure: Exception? = null
        if (recording) {
            // The OS limit callback is not a release acknowledgment; always stop and release ourselves.
            try { owned.stop() }
            catch (error: RuntimeException) { stopFailure = error }
            finally { recording = false }
        }
        try { owned.release(); recorder = null }
        catch (error: Exception) {
            stopFailure?.let { if (it !== error) error.addSuppressed(it) }
            throw error
        }
        stopFailure?.let { recordResultFailure(captureFailure(it)) }
        if (capture.file.length() > PortalMediaStore.MAX_ITEM_BYTES) recordResultFailure(PortalFailure("resource_limit"))
        if (capture.file.length() == 0L) recordResultFailure(PortalFailure("media_unavailable"))
        return true
    }
}

private fun requireCaptureMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

private fun captureFailure(error: Throwable): PortalFailure = when (error) {
    is PortalFailure -> error
    is SecurityException -> PortalFailure("permission_denied").apply { initCause(error) }
    is CancellationException -> PortalFailure("user_cancelled").apply { initCause(error) }
    else -> PortalFailure("media_unavailable").apply { initCause(error) }
}
