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
        var lastSeenNanos: Long,
        var hits: Int,
        val labelScores: MutableMap<String, Float>
    )

    private data class MatchCandidate(
        val trackIndex: Int,
        val detectionIndex: Int,
        val quality: Float,
        val overlap: Float,
        val distance: Float,
        val labelRelation: Int
    )

    private val tracks = mutableListOf<MutableTrack>()
    private var nextId = 1

    @Synchronized
    fun update(detections: List<Detection>, nowNanos: Long): List<TrackSnapshot> {
        val candidates = mutableListOf<MatchCandidate>()

        tracks.forEachIndexed { trackIndex, track ->
            val predicted = predictedBox(track, nowNanos)
            detections.forEachIndexed { detectionIndex, detection ->
                val overlap = iou(predicted, detection.box)
                val dx = predicted.centerX() - detection.box.centerX()
                val dy = predicted.centerY() - detection.box.centerY()
                val distance = sqrt(dx * dx + dy * dy)
                val relation = labelRelation(track.label, detection.label)

                val eligible = when (relation) {
                    2 -> overlap >= 0.015f || distance <= 0.24f
                    1 -> overlap >= 0.045f || distance <= 0.17f
                    else -> overlap >= 0.38f && distance <= 0.10f
                }
                if (!eligible) return@forEachIndexed

                val labelPenalty = when (relation) {
                    2 -> 0f
                    1 -> 0.12f
                    else -> 0.38f
                }
                val quality = overlap * 3.0f - distance * 1.35f - labelPenalty
                candidates += MatchCandidate(
                    trackIndex,
                    detectionIndex,
                    quality,
                    overlap,
                    distance,
                    relation
                )
            }
        }

        val usedTracks = BooleanArray(tracks.size)
        val usedDetections = BooleanArray(detections.size)
        candidates.sortedByDescending { it.quality }.forEach { candidate ->
            if (usedTracks[candidate.trackIndex] || usedDetections[candidate.detectionIndex]) return@forEach
            val track = tracks[candidate.trackIndex]
            val detection = detections[candidate.detectionIndex]
            updateTrack(track, detection, nowNanos)
            usedTracks[candidate.trackIndex] = true
            usedDetections[candidate.detectionIndex] = true
        }

        detections.forEachIndexed { index, detection ->
            if (usedDetections[index]) return@forEachIndexed
            tracks += MutableTrack(
                id = nextId++,
                label = detection.label,
                score = detection.score,
                box = RectF(detection.box),
                centerX = detection.box.centerX(),
                centerY = detection.box.centerY(),
                velocityX = 0f,
                velocityY = 0f,
                lastSeenNanos = nowNanos,
                hits = 1,
                labelScores = mutableMapOf(detection.label to detection.score)
            )
        }

        tracks.removeAll { nowNanos - it.lastSeenNanos > TRACK_TTL_NANOS }
        return snapshot()
    }

    private fun updateTrack(track: MutableTrack, detection: Detection, nowNanos: Long) {
        val dt = ((nowNanos - track.lastSeenNanos) / 1_000_000_000f).coerceIn(0.015f, 0.75f)
        val predictedCx = track.centerX + track.velocityX * dt
        val predictedCy = track.centerY + track.velocityY * dt
        val measuredCx = detection.box.centerX()
        val measuredCy = detection.box.centerY()
        val innovationX = measuredCx - predictedCx
        val innovationY = measuredCy - predictedCy

        // Alpha-beta filter: use the measurement strongly enough to avoid visible lag, while the
        // velocity correction absorbs motion between detector frames and keeps IDs stable.
        val alpha = if (track.hits < 3) 0.86f else 0.72f
        val beta = if (track.hits < 3) 0.34f else 0.24f
        val correctedCx = predictedCx + alpha * innovationX
        val correctedCy = predictedCy + alpha * innovationY
        var correctedVx = track.velocityX + beta * innovationX / dt
        var correctedVy = track.velocityY + beta * innovationY / dt

        val speed = sqrt(correctedVx * correctedVx + correctedVy * correctedVy)
        if (speed < VELOCITY_DEAD_ZONE) {
            correctedVx = 0f
            correctedVy = 0f
        } else if (speed > MAX_NORMALIZED_SPEED) {
            val scale = MAX_NORMALIZED_SPEED / speed
            correctedVx *= scale
            correctedVy *= scale
        }

        val oldW = track.box.width()
        val oldH = track.box.height()
        val measuredW = detection.box.width()
        val measuredH = detection.box.height()
        val boxWeight = if (track.hits < 3) 0.82f else 0.68f
        val width = oldW * (1f - boxWeight) + measuredW * boxWeight
        val height = oldH * (1f - boxWeight) + measuredH * boxWeight

        track.centerX = correctedCx
        track.centerY = correctedCy
        track.velocityX = correctedVx
        track.velocityY = correctedVy
        track.box = RectF(
            correctedCx - width / 2f,
            correctedCy - height / 2f,
            correctedCx + width / 2f,
            correctedCy + height / 2f
        )
        track.score = track.score * 0.30f + detection.score * 0.70f
        track.lastSeenNanos = nowNanos
        track.hits++

        val iterator = track.labelScores.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.setValue(entry.value * LABEL_SCORE_DECAY)
            if (entry.value < 0.02f) iterator.remove()
        }
        track.labelScores[detection.label] =
            (track.labelScores[detection.label] ?: 0f) + detection.score
        track.label = track.labelScores.maxByOrNull { it.value }?.key ?: detection.label
    }

    @Synchronized
    fun snapshot(): List<TrackSnapshot> = tracks
        .filter { it.hits >= MIN_HITS_TO_DISPLAY || it.score >= INSTANT_DISPLAY_SCORE }
        .map {
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

    @Synchronized
    fun clear() {
        tracks.clear()
    }

    private fun predictedBox(track: MutableTrack, nowNanos: Long): RectF {
        val speed = sqrt(track.velocityX * track.velocityX + track.velocityY * track.velocityY)
        if (speed < VELOCITY_DEAD_ZONE) return RectF(track.box)

        val dt = ((nowNanos - track.lastSeenNanos) / 1_000_000_000f)
            .coerceIn(0f, MAX_MATCH_PREDICTION_SECONDS)
        val dx = track.velocityX * dt
        val dy = track.velocityY * dt
        return RectF(
            track.box.left + dx,
            track.box.top + dy,
            track.box.right + dx,
            track.box.bottom + dy
        )
    }

    private fun labelRelation(a: String, b: String): Int {
        if (a == b) return 2
        val ga = semanticGroup(a)
        val gb = semanticGroup(b)
        return if (ga != null && ga == gb) 1 else 0
    }

    private fun semanticGroup(label: String): String? = when (label) {
        "bicycle", "car", "motorcycle", "bus", "train", "truck" -> "vehicle"
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe" -> "animal"
        "chair", "couch", "bed", "dining table", "bench" -> "furniture"
        "bottle", "wine glass", "cup", "bowl" -> "container"
        "tv", "laptop", "cell phone", "remote", "keyboard", "mouse" -> "electronics"
        else -> null
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
        private const val MAX_MATCH_PREDICTION_SECONDS = 0.55f
        private const val TRACK_TTL_NANOS = 900_000_000L
        private const val VELOCITY_DEAD_ZONE = 0.012f
        private const val MAX_NORMALIZED_SPEED = 2.2f
        private const val LABEL_SCORE_DECAY = 0.82f
        private const val MIN_HITS_TO_DISPLAY = 2
        private const val INSTANT_DISPLAY_SCORE = 0.78f
    }
}
