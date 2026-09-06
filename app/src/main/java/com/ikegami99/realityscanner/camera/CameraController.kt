package com.ikegami99.realityscanner.camera

import android.graphics.Bitmap
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
import com.ikegami99.realityscanner.ui.HudOverlayView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

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

    fun start() {
        sourceMode = SourceMode.CAMERA
        sourceGeneration.incrementAndGet()
        trackManager.clear()
        hud.setTracks(emptyList())
        frameCounter = 0
        inferenceCount = 0
        cameraFps = 0f
        aiFps = 0f
        lastInferenceNanos = 0L
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

    /**
     * Switches inference ownership from CameraX to the demo-frame pump while keeping the detector
     * and inference executor alive. Any camera inference that was already in flight is invalidated
     * by sourceGeneration and is not allowed to repopulate the HUD after the switch.
     */
    fun pause() {
        sourceMode = SourceMode.DEMO
        sourceGeneration.incrementAndGet()
        cameraProvider?.unbindAll()
        camera = null
        cameraFps = 0f
        aiFps = 0f
        lastInferenceNanos = 0L
        lastLowLight = false
        trackManager.clear()
        hud.setTracks(emptyList())
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

    /**
     * Takes ownership of [bitmap]. The bitmap is always recycled by this controller, including
     * when the detector is busy and the frame is dropped. This lets the PixelCopy demo pump submit
     * frames at display cadence without building an unbounded queue behind a slow detector.
     */
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

                // A result from the previous source must never re-enter the HUD after a
                // CAMERA <-> DEMO transition.
                if (sourceMode != expectedMode || sourceGeneration.get() != generation) {
                    logger.info(logTag, "stale inference discarded // source switched")
                    return@execute
                }

                val tracks = trackManager.update(detections, sourceFrameNanos)
                lastInferenceMs = (completed - started) / 1_000_000f
                updateAiFps(completed)

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
        analysisExecutor.shutdownNow()
        inferenceExecutor.shutdownNow()
        detector.close()
    }

    companion object {
        private const val INFERENCE_INTERVAL_NANOS = 40_000_000L
        private const val DEMO_INFERENCE_INTERVAL_NANOS = 80_000_000L
        private const val LOW_LIGHT_ENTER_LUMA = 45f
        private const val LOW_LIGHT_EXIT_LUMA = 55f
    }
}
