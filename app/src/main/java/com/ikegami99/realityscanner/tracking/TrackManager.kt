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
            var best: Detection? = null
            var bestIou = 0f
            unmatched.forEach { detection ->
                if (detection.label == track.label) {
                    val overlap = iou(track.box, detection.box)
                    if (overlap > bestIou) {
                        bestIou = overlap
                        best = detection
                    }
                }
            }

            val match = best
            if (match != null && bestIou >= 0.15f) {
                val newCx = match.box.centerX()
                val newCy = match.box.centerY()
                val dt = ((nowNanos - track.lastSeenNanos) / 1_000_000_000f).coerceAtLeast(0.001f)
                val measuredVx = (newCx - track.centerX) / dt
                val measuredVy = (newCy - track.centerY) / dt

                track.velocityX = track.velocityX * 0.65f + measuredVx * 0.35f
                track.velocityY = track.velocityY * 0.65f + measuredVy * 0.35f
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

        tracks.removeAll { nowNanos - it.lastSeenNanos > 900_000_000L }
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

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
