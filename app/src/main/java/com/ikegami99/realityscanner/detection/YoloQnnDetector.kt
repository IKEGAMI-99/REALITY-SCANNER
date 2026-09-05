package com.ikegami99.realityscanner.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.system.Os
import com.ikegami99.realityscanner.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloQnnDetector(
    private val context: Context,
    private val logger: AppLogger,
    private val assetName: String,
    private val displayName: String
) : Detector {
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var inputName = ""
    private var inputShape = longArrayOf(1, 640, 640, 3)
    private var inputWidth = 640
    private var inputHeight = 640
    private var inputNhwc = true
    private var attemptedLoad = false

    override val isReady: Boolean
        get() = session != null

    override val backendName: String
        get() = if (isReady) "$displayName/HTP" else "$displayName/PROBE"

    override fun detect(bitmap: Bitmap, rotationDegrees: Int, lowLightGain: Float): List<Detection> {
        ensureLoaded()
        val active = session ?: return emptyList()

        val rotated = rotate(bitmap, rotationDegrees)
        val square = centerCropSquare(rotated)
        val resized = Bitmap.createScaledBitmap(square, inputWidth, inputHeight, true)

        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val planeSize = inputWidth * inputHeight
        val input = FloatArray(planeSize * 3)

        if (inputNhwc) {
            var dst = 0
            for (color in pixels) {
                input[dst++] = ((((color shr 16) and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
                input[dst++] = ((((color shr 8) and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
                input[dst++] = (((color and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
            }
        } else {
            for (i in pixels.indices) {
                val color = pixels[i]
                input[i] = ((((color shr 16) and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
                input[planeSize + i] = ((((color shr 8) and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
                input[planeSize * 2 + i] = (((color and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
            }
        }

        if (rotated !== bitmap) rotated.recycle()
        if (square !== rotated && square !== bitmap) square.recycle()
        if (resized !== square) resized.recycle()

        return try {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), inputShape).use { tensor ->
                active.run(mapOf(inputName to tensor)).use { result ->
                    parseOutput(result[0].value)
                }
            }
        } catch (t: Throwable) {
            logger.error("QNN", "$displayName inference failed: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    private fun ensureLoaded() {
        if (attemptedLoad) return
        attemptedLoad = true
        val model = resolveModel() ?: run {
            logger.warn("QNN", "$assetName missing")
            return
        }

        try {
            Os.setenv(
                "ADSP_LIBRARY_PATH",
                context.applicationInfo.nativeLibraryDir + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp",
                true
            )
        } catch (t: Throwable) {
            logger.warn("QNN", "ADSP_LIBRARY_PATH setup failed: ${t.message}")
        }

        try {
            val options = OrtSession.SessionOptions()
            options.addQnn(
                mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst"
                )
            )
            val created = environment.createSession(model.absolutePath, options)
            options.close()

            inputName = created.inputNames.first()
            val info = created.inputInfo[inputName]?.info as? TensorInfo
            inputShape = info?.shape ?: inputShape
            require(inputShape.size == 4) { "unexpected input shape ${inputShape.toList()}" }

            inputNhwc = inputShape[3] == 3L && inputShape[1] != 3L
            if (inputNhwc) {
                inputHeight = inputShape[1].toInt()
                inputWidth = inputShape[2].toInt()
            } else {
                require(inputShape[1] == 3L) { "unsupported input layout ${inputShape.toList()}" }
                inputHeight = inputShape[2].toInt()
                inputWidth = inputShape[3].toInt()
            }

            session = created
            logger.info(
                "QNN",
                "$displayName loaded on HTP input=${inputWidth}x$inputHeight layout=${if (inputNhwc) "NHWC" else "NCHW"}"
            )
        } catch (t: Throwable) {
            session = null
            logger.warn("QNN", "$displayName HTP load failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun resolveModel(): File? {
        val privateModel = File(context.filesDir, "models/$assetName")
        if (privateModel.exists() && privateModel.length() > 0L) return privateModel
        return try {
            context.assets.open("models/$assetName").use { input ->
                privateModel.parentFile?.mkdirs()
                FileOutputStream(privateModel).use { output -> input.copyTo(output) }
            }
            privateModel
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseOutput(value: Any?): List<Detection> {
        val matrix = toMatrix(value) ?: return emptyList()
        if (matrix.isEmpty() || matrix[0].isEmpty()) return emptyList()
        val rows = if (matrix.size in 5..128 && matrix[0].size > matrix.size) transpose(matrix) else matrix
        val candidates = mutableListOf<Detection>()

        rows.forEach { row ->
            if (row.size < 6) return@forEach
            if (row.size == 6) {
                val score = row[4]
                if (score < CONFIDENCE_THRESHOLD) return@forEach
                candidates += Detection(labelFor(row[5].toInt()), score, normalizeBox(row[0], row[1], row[2], row[3]))
            } else {
                var bestClass = -1
                var bestScore = 0f
                for (i in 4 until row.size) {
                    if (row[i] > bestScore) {
                        bestScore = row[i]
                        bestClass = i - 4
                    }
                }
                if (bestScore < CONFIDENCE_THRESHOLD) return@forEach
                val cx = row[0]
                val cy = row[1]
                val w = row[2]
                val h = row[3]
                candidates += Detection(
                    labelFor(bestClass),
                    bestScore,
                    normalizeBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                )
            }
        }
        return nms(candidates, 0.45f).take(100)
    }

    private fun normalizeBox(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val scaleX = if (max(max(x1, x2), 1f) > 1.5f) inputWidth.toFloat() else 1f
        val scaleY = if (max(max(y1, y2), 1f) > 1.5f) inputHeight.toFloat() else 1f
        return RectF(
            (x1 / scaleX).coerceIn(0f, 1f),
            (y1 / scaleY).coerceIn(0f, 1f),
            (x2 / scaleX).coerceIn(0f, 1f),
            (y2 / scaleY).coerceIn(0f, 1f)
        )
    }

    private fun toMatrix(value: Any?): Array<FloatArray>? {
        val outer = value as? Array<*> ?: return null
        val batch = if (outer.size == 1 && outer[0] is Array<*>) outer[0] as Array<*> else outer
        val result = ArrayList<FloatArray>(batch.size)
        batch.forEach { row ->
            when (row) {
                is FloatArray -> result += row
                is Array<*> -> {
                    val converted = FloatArray(row.size)
                    row.forEachIndexed { index, cell -> converted[index] = (cell as? Number)?.toFloat() ?: 0f }
                    result += converted
                }
            }
        }
        return result.toTypedArray()
    }

    private fun transpose(matrix: Array<FloatArray>): Array<FloatArray> {
        val rows = matrix.size
        val cols = matrix[0].size
        return Array(cols) { c -> FloatArray(rows) { r -> matrix[r][c] } }
    }

    private fun nms(input: List<Detection>, threshold: Float): List<Detection> {
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            sorted.removeAll { it.label == best.label && iou(it.box, best.box) > threshold }
        }
        return kept
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

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        if (bitmap.width == bitmap.height) return bitmap
        val size = min(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    private fun labelFor(index: Int): String =
        if (index in COCO_LABELS.indices) COCO_LABELS[index] else "object_$index"

    override fun close() {
        runCatching { session?.close() }
        session = null
    }

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }
}
