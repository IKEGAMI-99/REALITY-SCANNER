package com.ikegami99.realityscanner.detection

import android.graphics.Bitmap
import com.ikegami99.realityscanner.logging.AppLogger

class DetectorCascade(
    private val logger: AppLogger,
    private val candidates: List<Detector>
) : Detector {
    @Volatile private var active: Detector? = null
    private var nextCandidate = 0

    override val isReady: Boolean
        get() = active?.isReady == true

    override val backendName: String
        get() = active?.backendName ?: "PROBING"

    @Synchronized
    private fun choose(bitmap: Bitmap, rotationDegrees: Int, lowLightGain: Float): Pair<Detector?, List<Detection>> {
        active?.let { return it to it.detect(bitmap, rotationDegrees, lowLightGain) }

        while (nextCandidate < candidates.size) {
            val candidate = candidates[nextCandidate++]
            logger.info("MODEL", "probing detector ${candidate.backendName}")
            val result = candidate.detect(bitmap, rotationDegrees, lowLightGain)
            if (candidate.isReady) {
                active = candidate
                logger.info("MODEL", "selected live detector ${candidate.backendName}")
                return candidate to result
            }
            runCatching { candidate.close() }
        }

        return null to emptyList()
    }

    override fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int,
        lowLightGain: Float
    ): List<Detection> {
        val current = active
        if (current != null) return current.detect(bitmap, rotationDegrees, lowLightGain)
        return choose(bitmap, rotationDegrees, lowLightGain).second
    }

    override fun close() {
        candidates.forEach { runCatching { it.close() } }
        active = null
    }
}
