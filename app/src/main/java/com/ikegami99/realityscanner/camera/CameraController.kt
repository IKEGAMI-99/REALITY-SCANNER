package com.ikegami99.realityscanner.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ikegami99.realityscanner.detection.Detector
import com.ikegami99.realityscanner.logging.AppLogger
import com.ikegami99.realityscanner.tracking.TrackManager
import com.ikegami99.realityscanner.tracking.TrackSnapshot
import com.ikegami99.realityscanner.ui.HudOverlayView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CameraController(
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val detector: Detector,
    private val trackManager: TrackManager,
    private val hud: HudOverlayView,
    private val logger: AppLogger
) {
    private enum class SourceMode { CAMERA, DEMO, STOPPED }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)
    private val sourceGeneration = AtomicLong(0L)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    @Volatile private var sourceMode = SourceMode.STOPPED
    private var frameCounter = 0
    private var fpsWindowStart = System.nanoTime()
    @Volatile private var cameraFps = 0f

    @Volatile private var lastInferenceNanos = 0L
    @Volatile private var lastInferenceMs = 0f
    @Volatile private var aiFps = 0f
    @Volatile private var lastLowLight = false
    private var inferenceCount = 0
    private var aiWindowStart = System.nanoTime()

    private var lastWipeCaptureAttemptNanos = 0L
    private var lastWipeTrackId = -1

    fun start() {
        sourceMode = SourceMode.CAMERA
        sourceGeneration.incrementAndGet()
        trackManager.clear()
        hud.setTracks(emptyList())
        hud.clearFastestCapture()
        frameCounter = 0
        inferenceCount = 0
        cameraFps = 0f
        aiFps = 0f
        lastInferenceNanos = 0L
        lastWipeCaptureAttemptNanos = 0L
        lastWipeTrackId = -1
        fpsWindowStart = System.nanoTime()
        aiWindowStart = System.nanoTime()

        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            try {
                if (sourceMode != SourceMode.CAMERA) return@addListener
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                logger.info(
                    "CAMERA",
                    "preview started // analyzer=1280x720 // async inference enabled // display=square crop"
                )
            } catch (t: Throwable) {
                logger.error("CAMERA", "start failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    fun pause() {
        sourceMode = SourceMode.DEMO
        sourceGeneration.incrementAndGet()
        cameraProvider?.unbindAll()
        camera = null
        cameraFps = 0f
        aiFps = 0f
        lastInferenceNanos = 0L
        lastLowLight = false
        inferenceCount = 0
        aiWindowStart = System.nanoTime()
        lastWipeCaptureAttemptNanos = 0L
        lastWipeTrackId = -1
        trackManager.clear()
        hud.setTracks(emptyList())
        hud.clearFastestCapture()
        publishHud()
        logger.info("CAMERA", "preview paused // detector retained // demo inference armed")
    }

    private fun analyze(image: ImageProxy) {
        if (sourceMode != SourceMode.CAMERA) {
            image.close()
            return
        }

        val generation = sourceGeneration.get()
        val now = System.nanoTime()
        updateCameraFps(now)
        val luma = calculateLuma(image)

        val lowLight = if (lastLowLight) luma < LOW_LIGHT_EXIT_LUMA else luma < LOW_LIGHT_ENTER_LUMA
        if (lowLight != lastLowLight) {
            lastLowLight = lowLight
            logger.info(
                "LOWLIGHT",
                if (lowLight) "AUTO -> ACTIVE // luma=${"%.1f".format(luma)}"
                else "AUTO -> STANDBY // luma=${"%.1f".format(luma)}"
            )
            applyExposureAssist(lowLight)
        }

        publishHud()

        val inferDue = now - lastInferenceNanos >= INFERENCE_INTERVAL_NANOS
        if (!inferDue || !inferenceRunning.compareAndSet(false, true)) {
            image.close()
            return
        }

        lastInferenceNanos = now
        val sourceFrameNanos = now
        val rotationDegrees = image.imageInfo.rotationDegrees
        val lowLightGain = if (lowLight) 1.45f else 1f

        val bitmap = try {
            image.toBitmap()
        } catch (t: Throwable) {
            inferenceRunning.set(false)
            logger.error("ANALYSIS", "frame copy failed: ${t.javaClass.simpleName}: ${t.message}")
            image.close()
            return
        }
        image.close()

        runInference(
            bitmap = bitmap,
            rotationDegrees = rotationDegrees,
            lowLightGain = lowLightGain,
            sourceFrameNanos = sourceFrameNanos,
            expectedMode = SourceMode.CAMERA,
            generation = generation,
            logTag = "YOLO"
        )
    }

    fun submitDemoFrame(bitmap: Bitmap): Boolean {
        val now = System.nanoTime()
        if (sourceMode != SourceMode.DEMO) {
            bitmap.recycle()
            return false
        }

        val inferDue = now - lastInferenceNanos >= DEMO_INFERENCE_INTERVAL_NANOS
        if (!inferDue || !inferenceRunning.compareAndSet(false, true)) {
            bitmap.recycle()
            return false
        }

        lastInferenceNanos = now
        val generation = sourceGeneration.get()
        runInference(
            bitmap = bitmap,
            rotationDegrees = 0,
            lowLightGain = 1f,
            sourceFrameNanos = now,
            expectedMode = SourceMode.DEMO,
            generation = generation,
            logTag = "DEMO-YOLO"
        )
        return true
    }

    private fun runInference(
        bitmap: Bitmap,
        rotationDegrees: Int,
        lowLightGain: Float,
        sourceFrameNanos: Long,
        expectedMode: SourceMode,
        generation: Long,
        logTag: String
    ) {
        inferenceExecutor.execute {
            val started = System.nanoTime()
            try {
                val detections = detector.detect(
                    bitmap = bitmap,
                    rotationDegrees = rotationDegrees,
                    lowLightGain = lowLightGain
                )
                val completed = System.nanoTime()

                if (sourceMode != expectedMode || sourceGeneration.get() != generation) {
                    logger.info(logTag, "stale inference discarded // source switched")
                    return@execute
                }

                val tracks = trackManager.update(detections, sourceFrameNanos)
                lastInferenceMs = (completed - started) / 1_000_000f
                updateAiFps(completed)

                maybeUpdateFastestCapture(
                    bitmap = bitmap,
                    rotationDegrees = rotationDegrees,
                    tracks = tracks,
                    sourceFrameNanos = sourceFrameNanos,
                    completedNanos = completed,
                    expectedMode = expectedMode,
                    generation = generation
                )

                logger.info(
                    logTag,
                    "objects=${detections.size} tracks=${tracks.size} " +
                        "infer=${"%.1f".format(lastInferenceMs)}ms backend=${detector.backendName}"
                )
                tracks.take(4).forEach {
                    logger.info(
                        "VECTOR",
                        "#${it.id} ${it.label} rel=${"%.3f".format(it.relativeSpeed)}/s " +
                            "vx=${"%.3f".format(it.velocityX)} vy=${"%.3f".format(it.velocityY)}"
                    )
                }

                previewView.post {
                    if (sourceMode == expectedMode && sourceGeneration.get() == generation) {
                        hud.setTracks(tracks)
                        publishHud()
                    }
                }
            } catch (t: Throwable) {
                logger.error(logTag, "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                bitmap.recycle()
                inferenceRunning.set(false)
            }
        }
    }

    private fun maybeUpdateFastestCapture(
        bitmap: Bitmap,
        rotationDegrees: Int,
        tracks: List<TrackSnapshot>,
        sourceFrameNanos: Long,
        completedNanos: Long,
        expectedMode: SourceMode,
        generation: Long
    ) {
        if (completedNanos - lastWipeCaptureAttemptNanos < WIPE_CAPTURE_INTERVAL_NANOS) return
        lastWipeCaptureAttemptNanos = completedNanos

        val target = tracks.asSequence()
            .filter { sourceFrameNanos - it.lastSeenNanos <= MAX_CAPTURE_TRACK_AGE_NANOS }
            .filter { it.relativeSpeed >= MIN_CAPTURE_SPEED }
            .filter { it.score >= MIN_CAPTURE_SCORE }
            .filter { normalizedVisibleRatio(it.box) >= MIN_CAPTURE_VISIBLE_RATIO }
            .filter { visibleArea(it.box) >= MIN_CAPTURE_NORMALIZED_AREA }
            .maxByOrNull { it.relativeSpeed }
            ?: return

        val capture = createCapture(bitmap, rotationDegrees, target) ?: return

        val targetChanged = target.id != lastWipeTrackId
        lastWipeTrackId = target.id
        previewView.post {
            if (sourceMode == expectedMode && sourceGeneration.get() == generation) {
                hud.setFastestCapture(capture, target.label, target.id, target.relativeSpeed)
            } else {
                if (!capture.isRecycled) capture.recycle()
            }
        }

        if (targetChanged) {
            logger.info(
                "CAPTURE",
                "fastest locked // #${target.id} ${target.label} rel=${"%.3f".format(target.relativeSpeed)}/s"
            )
        }
    }

    private fun createCapture(
        source: Bitmap,
        rotationDegrees: Int,
        track: TrackSnapshot
    ): Bitmap? {
        val clipped = clipNormalized(track.box)
        if (clipped.width() <= 0f || clipped.height() <= 0f) return null

        val padX = clipped.width() * CAPTURE_PADDING_RATIO
        val padY = clipped.height() * CAPTURE_PADDING_RATIO
        val captureBox = RectF(
            (clipped.left - padX).coerceAtLeast(0f),
            (clipped.top - padY).coerceAtLeast(0f),
            (clipped.right + padX).coerceAtMost(1f),
            (clipped.bottom + padY).coerceAtMost(1f)
        )

        val oriented = rotateForCapture(source, rotationDegrees)
        try {
            val squareSize = min(oriented.width, oriented.height)
            if (squareSize <= 2) return null
            val offsetX = (oriented.width - squareSize) / 2
            val offsetY = (oriented.height - squareSize) / 2

            val left = (offsetX + captureBox.left * squareSize).roundToInt()
                .coerceIn(offsetX, offsetX + squareSize - 1)
            val top = (offsetY + captureBox.top * squareSize).roundToInt()
                .coerceIn(offsetY, offsetY + squareSize - 1)
            val right = (offsetX + captureBox.right * squareSize).roundToInt()
                .coerceIn(left + 1, offsetX + squareSize)
            val bottom = (offsetY + captureBox.bottom * squareSize).roundToInt()
                .coerceIn(top + 1, offsetY + squareSize)

            val cropWidth = right - left
            val cropHeight = bottom - top
            if (cropWidth < MIN_CAPTURE_PIXELS || cropHeight < MIN_CAPTURE_PIXELS) return null

            val rawCrop = try {
                Bitmap.createBitmap(oriented, left, top, cropWidth, cropHeight)
            } catch (_: Throwable) {
                return null
            }

            val scaled = scaleCaptureDown(rawCrop)
            if (scaled !== rawCrop && !rawCrop.isRecycled) rawCrop.recycle()

            if (!captureLooksUsable(scaled)) {
                if (!scaled.isRecycled) scaled.recycle()
                return null
            }
            return scaled
        } finally {
            if (oriented !== source && !oriented.isRecycled) oriented.recycle()
        }
    }

    private fun rotateForCapture(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun scaleCaptureDown(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= MAX_CAPTURE_OUTPUT_PIXELS) return bitmap
        val scale = MAX_CAPTURE_OUTPUT_PIXELS.toFloat() / maxSide.toFloat()
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun captureLooksUsable(bitmap: Bitmap): Boolean {
        if (bitmap.width < MIN_CAPTURE_PIXELS || bitmap.height < MIN_CAPTURE_PIXELS) return false

        val stepX = max(1, bitmap.width / 14)
        val stepY = max(1, bitmap.height / 14)
        var count = 0
        var sum = 0.0
        var sumSq = 0.0

        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val luma = r * 0.2126 + g * 0.7152 + b * 0.0722
                sum += luma
                sumSq += luma * luma
                count++
                x += stepX
            }
            y += stepY
        }

        if (count < 16) return false
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return mean in MIN_CAPTURE_MEAN_LUMA..MAX_CAPTURE_MEAN_LUMA &&
            variance >= MIN_CAPTURE_LUMA_VARIANCE
    }

    private fun normalizedVisibleRatio(box: RectF): Float {
        val fullWidth = max(0f, box.width())
        val fullHeight = max(0f, box.height())
        val fullArea = fullWidth * fullHeight
        if (fullArea <= 0f) return 0f
        return visibleArea(box) / fullArea
    }

    private fun visibleArea(box: RectF): Float {
        val clipped = clipNormalized(box)
        return max(0f, clipped.width()) * max(0f, clipped.height())
    }

    private fun clipNormalized(box: RectF): RectF = RectF(
        box.left.coerceIn(0f, 1f),
        box.top.coerceIn(0f, 1f),
        box.right.coerceIn(0f, 1f),
        box.bottom.coerceIn(0f, 1f)
    )

    private fun updateCameraFps(now: Long) {
        frameCounter++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            cameraFps = frameCounter * 1_000_000_000f / elapsed
            frameCounter = 0
            fpsWindowStart = now
        }
    }

    @Synchronized
    private fun updateAiFps(now: Long) {
        inferenceCount++
        val elapsed = now - aiWindowStart
        if (elapsed >= 1_000_000_000L) {
            aiFps = inferenceCount * 1_000_000_000f / elapsed
            inferenceCount = 0
            aiWindowStart = now
        }
    }

    private fun publishHud() {
        val mode = sourceMode
        previewView.post {
            val backend = when (mode) {
                SourceMode.DEMO -> "DEMO/${detector.backendName}"
                SourceMode.CAMERA -> detector.backendName
                SourceMode.STOPPED -> "STOPPED"
            }
            hud.setStats(
                HudOverlayView.Stats(
                    cameraFps = if (mode == SourceMode.CAMERA) cameraFps else 0f,
                    aiFps = aiFps,
                    inferenceMs = lastInferenceMs,
                    lowLight = mode == SourceMode.CAMERA && lastLowLight,
                    backend = backend
                )
            )
        }
    }

    private fun calculateLuma(image: ImageProxy): Float {
        val plane = image.planes.firstOrNull() ?: return 255f
        val buffer = plane.buffer
        val remaining = buffer.remaining()
        if (remaining <= 0) return 255f

        val step = maxOf(1, remaining / 4096)
        var sum = 0L
        var count = 0
        var index = buffer.position()
        val limit = buffer.limit()

        while (index < limit) {
            sum += buffer.get(index).toInt() and 0xFF
            count++
            index += step
        }
        return if (count == 0) 255f else sum.toFloat() / count
    }

    private fun applyExposureAssist(lowLight: Boolean) {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return

        val target = if (lowLight) min(2, state.exposureCompensationRange.upper) else 0
        runCatching {
            activeCamera.cameraControl.setExposureCompensationIndex(target)
        }
    }

    fun stop() {
        sourceMode = SourceMode.STOPPED
        sourceGeneration.incrementAndGet()
        cameraProvider?.unbindAll()
        camera = null
        trackManager.clear()
        hud.clearFastestCapture()
        analysisExecutor.shutdownNow()
        inferenceExecutor.shutdownNow()
        detector.close()
    }

    companion object {
        private const val INFERENCE_INTERVAL_NANOS = 40_000_000L
        private const val DEMO_INFERENCE_INTERVAL_NANOS = 40_000_000L
        private const val LOW_LIGHT_ENTER_LUMA = 45f
        private const val LOW_LIGHT_EXIT_LUMA = 55f

        // Wipe capture is intentionally much slower than AI inference so FAST20 performance is not
        // consumed by bitmap rotation/cropping. Failed captures never replace the last good image.
        private const val WIPE_CAPTURE_INTERVAL_NANOS = 250_000_000L
        private const val MAX_CAPTURE_TRACK_AGE_NANOS = 180_000_000L
        private const val MIN_CAPTURE_SPEED = 0.025f
        private const val MIN_CAPTURE_SCORE = 0.45f
        private const val MIN_CAPTURE_VISIBLE_RATIO = 0.82f
        private const val MIN_CAPTURE_NORMALIZED_AREA = 0.0025f
        private const val CAPTURE_PADDING_RATIO = 0.18f
        private const val MIN_CAPTURE_PIXELS = 48
        private const val MAX_CAPTURE_OUTPUT_PIXELS = 360
        private const val MIN_CAPTURE_MEAN_LUMA = 10.0
        private const val MAX_CAPTURE_MEAN_LUMA = 247.0
        private const val MIN_CAPTURE_LUMA_VARIANCE = 14.0
    }
}
