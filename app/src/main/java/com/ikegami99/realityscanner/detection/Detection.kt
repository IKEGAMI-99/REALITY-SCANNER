package com.ikegami99.realityscanner.detection

import android.graphics.RectF

data class Detection(
    val label: String,
    val score: Float,
    val box: RectF
)
