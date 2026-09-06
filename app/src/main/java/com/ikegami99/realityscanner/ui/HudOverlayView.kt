package com.ikegami99.realityscanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.ikegami99.realityscanner.tracking.TrackSnapshot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Stats(
        val cameraFps: Float = 0f,
        val aiFps: Float = 0f,
        val inferenceMs: Float = 0f,
        val lowLight: Boolean = false,
        val backend: String = "INITIALIZING"
    )

    private data class FastestCaptureMeta(
        val label: String,
        val id: Int,
        val speed: Float
    )

    private val green = Color.rgb(112, 255, 112)
    private val dimGreen = Color.rgb(42, 150, 62)
    private val blackGlass = Color.argb(185, 0, 10, 1)
    private val captureGlass = Color.argb(222, 0, 8, 1)
    private val warning = Color.rgb(205, 255, 92)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        textSize = 27f
        typeface = Typeface.MONOSPACE
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimGreen
        textSize = 21f
        typeface = Typeface.MONOSPACE
    }
    private val panelPaint = Paint().apply {
        color = blackGlass
        style = Paint.Style.FILL
    }
    private val capturePanelPaint = Paint().apply {
        color = captureGlass
        style = Paint.Style.FILL
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    @Volatile private var tracks: List<TrackSnapshot> = emptyList()
    @Volatile private var stats = Stats()
    private var fastestBitmap: Bitmap? = null
    private var fastestMeta: FastestCaptureMeta? = null
    private var scanPhase = 0f

    fun setTracks(value: List<TrackSnapshot>) {
        tracks = value
        postInvalidateOnAnimation()
    }

    fun setStats(value: Stats) {
        stats = value
        postInvalidateOnAnimation()
    }

    /**
     * Takes ownership of [bitmap]. The previous successful capture stays visible until a new
     * successful capture arrives. Callers should never pass failed/placeholder crops here.
     */
    fun setFastestCapture(bitmap: Bitmap, label: String, id: Int, speed: Float) {
        val applyCapture = {
            fastestBitmap?.let { old ->
                if (old !== bitmap && !old.isRecycled) old.recycle()
            }
            fastestBitmap = bitmap
            fastestMeta = FastestCaptureMeta(label, id, speed)
            postInvalidateOnAnimation()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyCapture()
        } else {
            post { applyCapture() }
        }
    }

    fun clearFastestCapture() {
        val clear = {
            fastestBitmap?.let { if (!it.isRecycled) it.recycle() }
            fastestBitmap = null
            fastestMeta = null
            postInvalidateOnAnimation()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) clear() else post { clear() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        drawScanLine(canvas)
        drawSystemStatus(canvas)

        val now = System.nanoTime()
        tracks.forEach { drawTrack(canvas, it, now) }
        drawFastestCapture(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawSystemStatus(canvas: Canvas) {
        val s = stats
        val lines = listOf(
            "CAM %4.1f FPS  AI %4.1f FPS".format(s.cameraFps, s.aiFps),
            "INFER %5.1f ms  OBJ %02d".format(s.inferenceMs, tracks.size),
            "BACKEND ${s.backend}",
            if (s.lowLight) "LOW LIGHT // ACTIVE" else "LOW LIGHT // STANDBY"
        )
        val panel = RectF(12f, 12f, min(width - 12f, 500f), 126f)
        canvas.drawRect(panel, panelPaint)
        lines.forEachIndexed { index, text ->
            smallPaint.color = if (s.lowLight && index == 3) warning else dimGreen
            canvas.drawText(text, 24f, 36f + index * 27f, smallPaint)
        }
    }

    private fun drawScanLine(canvas: Canvas) {
        scanPhase = (scanPhase + 0.006f) % 1f
        linePaint.color = Color.argb(70, 112, 255, 112)
        linePaint.strokeWidth = 1f
        val y = height * scanPhase
        canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
    }

    private fun drawTrack(canvas: Canvas, track: TrackSnapshot, now: Long) {
        val ageSeconds = ((now - track.lastSeenNanos) / 1_000_000_000f).coerceAtLeast(0f)
        val moving = track.relativeSpeed >= MIN_VISUAL_SPEED

        val latencyBudget = (stats.inferenceMs / 1000f * LATENCY_COMPENSATION_MULTIPLIER)
            .coerceIn(MIN_EXTRAPOLATION_SECONDS, MAX_EXTRAPOLATION_SECONDS)
        val dt = if (moving) ageSeconds.coerceAtMost(latencyBudget) else 0f
        val dx = track.velocityX * dt
        val dy = track.velocityY * dt

        val rect = RectF(
            (track.box.left + dx) * width,
            (track.box.top + dy) * height,
            (track.box.right + dx) * width,
            (track.box.bottom + dy) * height
        )

        val stale = ageSeconds > STALE_SECONDS
        linePaint.color = if (stale) dimGreen else green
        linePaint.strokeWidth = 2f
        drawCorners(canvas, rect)

        val label = "${track.label.uppercase()} #${track.id.toString().padStart(4, '0')}"
        val predictionFlag = when {
            stale -> " HOLD"
            moving && ageSeconds > 0.06f -> " PRED"
            else -> ""
        }
        val metrics = "CONF %.1f%%  REL %.3f/s%s".format(
            track.score * 100f,
            track.relativeSpeed,
            predictionFlag
        )

        val labelWidth = maxOf(textPaint.measureText(label), smallPaint.measureText(metrics)) + 20f
        val labelTop = (rect.top - 61f).coerceAtLeast(0f)
        canvas.drawRect(rect.left, labelTop, rect.left + labelWidth, labelTop + 58f, panelPaint)
        textPaint.color = if (stale) dimGreen else green
        canvas.drawText(label, rect.left + 8f, labelTop + 25f, textPaint)
        canvas.drawText(metrics, rect.left + 8f, labelTop + 49f, smallPaint)

        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.drawCircle(cx, cy, 4f, linePaint)

        if (!stale && moving) {
            val targetX = cx + track.velocityX * width * VECTOR_LOOKAHEAD_SECONDS
            val targetY = cy + track.velocityY * height * VECTOR_LOOKAHEAD_SECONDS
            canvas.drawLine(cx, cy, targetX, targetY, linePaint)
            drawArrow(canvas, cx, cy, targetX, targetY)
            canvas.drawCircle(targetX, targetY, 10f, linePaint)
        }
    }

    private fun drawFastestCapture(canvas: Canvas) {
        val bitmap = fastestBitmap ?: return
        val meta = fastestMeta ?: return
        if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) return

        val panelWidth = (width * 0.36f).coerceAtMost(330f)
        val imageHeight = panelWidth * 0.70f
        val titleHeight = 52f
        val margin = 12f
        val left = margin
        val right = left + panelWidth
        val bottom = height - margin
        val top = bottom - imageHeight - titleHeight

        val panelRect = RectF(left, top, right, bottom)
        canvas.drawRect(panelRect, capturePanelPaint)

        smallPaint.color = green
        smallPaint.textSize = 19f
        val title = "FASTEST // ${meta.label.uppercase()} #${meta.id.toString().padStart(4, '0')}"
        canvas.drawText(title, left + 8f, top + 21f, smallPaint)
        smallPaint.color = dimGreen
        canvas.drawText("CAPTURED  REL %.3f/s".format(meta.speed), left + 8f, top + 43f, smallPaint)

        val dst = RectF(left + 4f, top + titleHeight, right - 4f, bottom - 4f)
        val src = centerCropSource(bitmap, dst.width() / dst.height())
        canvas.drawBitmap(bitmap, src, dst, imagePaint)

        linePaint.color = green
        linePaint.strokeWidth = 2f
        canvas.drawRect(panelRect, linePaint)
        canvas.drawRect(dst, linePaint)
    }

    private fun centerCropSource(bitmap: Bitmap, targetAspect: Float): Rect {
        val sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        return if (sourceAspect > targetAspect) {
            val cropWidth = (bitmap.height * targetAspect).toInt().coerceAtLeast(1)
            val left = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + cropWidth).coerceAtMost(bitmap.width), bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetAspect).toInt().coerceAtLeast(1)
            val top = ((bitmap.height - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, bitmap.width, (top + cropHeight).coerceAtMost(bitmap.height))
        }
    }

    private fun drawCorners(canvas: Canvas, rect: RectF) {
        val corner = min(rect.width(), rect.height()) * 0.18f
        canvas.drawLine(rect.left, rect.top, rect.left + corner, rect.top, linePaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + corner, linePaint)

        canvas.drawLine(rect.right, rect.top, rect.right - corner, rect.top, linePaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + corner, linePaint)

        canvas.drawLine(rect.left, rect.bottom, rect.left + corner, rect.bottom, linePaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - corner, linePaint)

        canvas.drawLine(rect.right, rect.bottom, rect.right - corner, rect.bottom, linePaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - corner, linePaint)
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val size = 18.0
        val a1 = angle + Math.PI * 0.82
        val a2 = angle - Math.PI * 0.82
        canvas.drawLine(x2, y2, (x2 + cos(a1) * size).toFloat(), (y2 + sin(a1) * size).toFloat(), linePaint)
        canvas.drawLine(x2, y2, (x2 + cos(a2) * size).toFloat(), (y2 + sin(a2) * size).toFloat(), linePaint)
    }

    override fun onDetachedFromWindow() {
        fastestBitmap?.let { if (!it.isRecycled) it.recycle() }
        fastestBitmap = null
        fastestMeta = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MIN_EXTRAPOLATION_SECONDS = 0.10f
        private const val MAX_EXTRAPOLATION_SECONDS = 0.32f
        private const val LATENCY_COMPENSATION_MULTIPLIER = 1.18f
        private const val STALE_SECONDS = 0.42f
        private const val VECTOR_LOOKAHEAD_SECONDS = 0.28f
        private const val MIN_VISUAL_SPEED = 0.012f
    }
}
