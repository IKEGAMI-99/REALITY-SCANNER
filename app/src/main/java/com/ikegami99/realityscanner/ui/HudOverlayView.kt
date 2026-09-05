package com.ikegami99.realityscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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

    private val green = Color.rgb(112, 255, 112)
    private val dimGreen = Color.rgb(42, 150, 62)
    private val blackGlass = Color.argb(185, 0, 10, 1)
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

    @Volatile private var tracks: List<TrackSnapshot> = emptyList()
    @Volatile private var stats = Stats()
    private var scanPhase = 0f

    fun setTracks(value: List<TrackSnapshot>) {
        tracks = value
        postInvalidateOnAnimation()
    }

    fun setStats(value: Stats) {
        stats = value
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        drawScanLine(canvas)
        drawSystemStatus(canvas)

        val now = System.nanoTime()
        tracks.forEach { drawTrack(canvas, it, now) }
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
        val dt = if (moving) ageSeconds.coerceAtMost(MAX_EXTRAPOLATION_SECONDS) else 0f
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
            moving && ageSeconds > 0.08f -> " PRED"
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

    companion object {
        private const val MAX_EXTRAPOLATION_SECONDS = 0.18f
        private const val STALE_SECONDS = 0.24f
        private const val VECTOR_LOOKAHEAD_SECONDS = 0.35f
        private const val MIN_VISUAL_SPEED = 0.025f
    }
}
