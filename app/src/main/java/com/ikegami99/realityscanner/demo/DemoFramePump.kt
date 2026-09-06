package com.ikegami99.realityscanner.demo

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import com.ikegami99.realityscanner.camera.CameraController
import com.ikegami99.realityscanner.logging.AppLogger

/**
 * Captures the currently composited YouTube demo area and feeds fresh frames to the same detector
 * used by CameraX. PixelCopy is used instead of View.draw() because hardware-composited WebView
 * video can otherwise produce an empty/black bitmap.
 */
class DemoFramePump(
    private val window: Window,
    private val sourceView: View,
    private val cameraController: CameraController,
    private val logger: AppLogger
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var copyInFlight = false
    private var consecutiveFailures = 0
    private var submittedFrames = 0L

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            captureLatestFrame()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        copyInFlight = false
        consecutiveFailures = 0
        submittedFrames = 0L
        handler.post(loop)
        logger.info(
            "DEMO",
            "frame inference started // PixelCopy latest-frame pump // interval=${CAPTURE_INTERVAL_MS}ms"
        )
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(loop)
        logger.info("DEMO", "frame inference stopped // submitted=$submittedFrames")
    }

    private fun captureLatestFrame() {
        if (!running || copyInFlight) return
        val width = sourceView.width
        val height = sourceView.height
        if (width <= 1 || height <= 1 || !sourceView.isShown) return

        val location = IntArray(2)
        sourceView.getLocationInWindow(location)
        val left = location[0]
        val top = location[1]
        val rect = Rect(left, top, left + width, top + height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        copyInFlight = true
        try {
            PixelCopy.request(
                window,
                rect,
                bitmap,
                { result ->
                    copyInFlight = false
                    if (!running) {
                        bitmap.recycle()
                        return@request
                    }

                    if (result == PixelCopy.SUCCESS) {
                        consecutiveFailures = 0
                        if (cameraController.submitDemoFrame(bitmap)) {
                            submittedFrames++
                        }
                    } else {
                        bitmap.recycle()
                        consecutiveFailures++
                        if (consecutiveFailures == 1 || consecutiveFailures % 30 == 0) {
                            logger.warn(
                                "DEMO",
                                "PixelCopy frame skipped // code=$result failures=$consecutiveFailures"
                            )
                        }
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            copyInFlight = false
            bitmap.recycle()
            consecutiveFailures++
            if (consecutiveFailures == 1 || consecutiveFailures % 30 == 0) {
                logger.warn(
                    "DEMO",
                    "PixelCopy failed: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    companion object {
        // Faster than the current ~175 ms CPU fallback, while submitDemoFrame drops captures when
        // inference is still busy. A future HTP path can consume more of these without code changes.
        private const val CAPTURE_INTERVAL_MS = 100L
    }
}
