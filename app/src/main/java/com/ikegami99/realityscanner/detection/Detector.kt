package com.ikegami99.realityscanner.detection

import android.graphics.Bitmap

interface Detector : AutoCloseable {
    val isReady: Boolean
    val backendName: String

    fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int,
        lowLightGain: Float = 1f
    ): List<Detection>
}
