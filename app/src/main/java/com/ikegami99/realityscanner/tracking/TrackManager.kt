package com.ikegami99.realityscanner.tracking

import android.graphics.RectF
import com.ikegami99.realityscanner.detection.Detection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TrackSnapshot(
    val id: Int,
    val label: String,
    val score: Float,
    val box: RectF,
    val velocityX: Float,
    val velocityY: Float,
    val lastSeenNanos: Long
) {
    val relativeSpeed: Float
        get() = sqrt(velocityX * velocityX + velocityY * velocityY)
}

class TrackManager {
    private data class MutableTrack(
        val id: Int,
        var label: String,
        var score: Float,
        var box: RectF,
        var centerX: Float,
        var centerY: Float,
        var velocityX: Float,
        var velocityY: Float,
        var lastSeenNanos: Long
    )

    private val tracks = mutableListOf<MutableTrack>()
    private var nextId = 1

    @Synchronized
    fun update(detections: List<Detection>, nowNanos: Long): List<TrackSnapshot> {
        val unmatched = detections.toMutableList()

        tracks.forEach { track ->
            val predicted = predictedBox(track, nowNanos)
            var best: Detection? = null
            var bestQuality = Float.NEGATIVE_INFINITY
            var bestIou = 0f
            var bestDistance = Float.MAX_VALUE

            unmatched.forEach { detection ->
                if (detection.label != track.label) return@forEach

                val overlap = iou(predicted, detection.box)
                val dx = predicted.centerX() - detection.box.centerX()
                val dy = predicted.centerY() - detection.box.centerY()
                val distance = sqrt(dx * dx + dy * dy)

                // IoU remains the strongest signal, but after a multi-second detector refresh a
                // moving target may no longer overlap its old box. Center distance keeps the same
                // Track ID alive when the constant-velocity prediction is close.
                val quality = overlap * 2.5f - distance
                if (quality > bestQuality) {
                    bestQuality = quality
                    bestIou = overlap
                    bestDistance = distance
                    best = detection
                }
            }

            val match = best
            if (match != null && (bestIou >= MIN_IOU || bestDistance <= MAX_CENTER_DISTANCE)) {
                val newCx = match.box.centerX()
                val newCy = match.box.centerY()
                val dt = ((nowNanos - track.lastSeenNanos) / 1_000_000_000f).coerceAtLeast(0.001f)
                val measuredVx = (newCx - track.centerX) / dt
                val measuredVy = (newCy - track.centerY) / dt

                // Slow detector refreshes are noisy. EMA avoids velocity vectors changing wildly
                // when a box edge shifts a few pixels between YOLO passes.
                track.velocityX = track.velocityX * 0.55f + measuredVx * 0.45f
                track.velocityY = track.velocityY * 0.55f + measuredVy * 0.45f
                track.centerX = newCx
                track.centerY = newCy
                track.box = RectF(match.box)
                track.score = match.score
                track.lastSeenNanos = nowNanos
                unmatched.remove(match)
            }
        }

        unmatched.forEach { detection ->
            tracks += MutableTrack(
                id = nextId++,
                label = detection.label,
                score = detection.score,
                box = RectF(detection.box),
                centerX = detection.box.centerX(),
                centerY = detection.box.centerY(),
                velocityX = 0f,
                velocityY = 0f,
                lastSeenNanos = nowNanos
            )
        }

        // One YOLO26x CPU/XNNPACK pass can still take seconds. Do not throw tracks away before the
        // next authoritative detector refresh has had a chance to arrive.
        tracks.removeAll { nowNanos - it.lastSeenNanos > TRACK_TTL_NANOS }
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<TrackSnapshot> = tracks.map {
        TrackSnapshot(
            id = it.id,
            label = it.label,
            score = it.score,
            box = RectF(it.box),
            velocityX = it.velocityX,
            velocityY = it.velocityY,
            lastSeenNanos = it.lastSeenNanos
        )
    }

    private fun predictedBox(track: MutableTrack, nowNanos: Long): RectF {
        val dt = ((nowNanos - track.lastSeenNanos) / 1_000_000_000f)
            .coerceIn(0f, MAX_PREDICTION_SECONDS)
        val dx = track.velocityX * dt
        val dy = track.velocityY * dt
        return RectF(
            track.box.left + dx,
            track.box.top + dy,
            track.box.right + dx,
            track.box.bottom + dy
        )
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        private const val MIN_IOU = 0.05f
        private const val MAX_CENTER_DISTANCE = 0.20f
        private const val MAX_PREDICTION_SECONDS = 5.0f
        private const val TRACK_TTL_NANOS = 6_000_000_000L
    }
}
