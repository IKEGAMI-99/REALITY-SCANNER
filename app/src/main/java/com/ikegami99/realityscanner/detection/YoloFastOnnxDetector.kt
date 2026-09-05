package com.ikegami99.realityscanner.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.ikegami99.realityscanner.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloFastOnnxDetector(
    private val context: Context,
    private val logger: AppLogger,
    private val assetName: String = "yolo26n.onnx",
    private val displayName: String = "YOLO26N"
) : Detector {
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var options: OrtSession.SessionOptions? = null
    private var inputName = ""
    private var inputWidth = 640
    private var inputHeight = 640
    private var attemptedLoad = false
    private var activeBackend = "PROBE"

    override val isReady: Boolean
        get() = session != null

    override val backendName: String
        get() = if (isReady) "$displayName-$activeBackend" else "$displayName/PROBE"

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
        for (i in pixels.indices) {
            val color = pixels[i]
            input[i] = ((((color shr 16) and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
            input[planeSize + i] = ((((color shr 8) and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
            input[planeSize * 2 + i] = (((color and 0xFF) / 255f) * lowLightGain).coerceAtMost(1f)
        }

        if (rotated !== bitmap) rotated.recycle()
        if (square !== rotated && square !== bitmap) square.recycle()
        if (resized !== square) resized.recycle()

        return try {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
            ).use { tensor ->
                active.run(mapOf(inputName to tensor)).use { result ->
                    parseOutput(result[0].value)
                }
            }
        } catch (t: Throwable) {
            logger.error("FAST", "$backendName inference failed: ${t.javaClass.simpleName}: ${t.message}")
            close()
            emptyList()
        }
    }

    private fun ensureLoaded() {
        if (attemptedLoad) return
        attemptedLoad = true
        val model = resolveModel() ?: run {
            logger.warn("FAST", "$assetName missing")
            return
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (cores - 2).coerceIn(2, 8)

        val xnnOptions = OrtSession.SessionOptions()
        try {
            xnnOptions.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            val created = environment.createSession(model.absolutePath, xnnOptions)
            installSession(created, xnnOptions, "XNN", threads)
            return
        } catch (t: Throwable) {
            runCatching { xnnOptions.close() }
            logger.warn("FAST", "XNNPACK unavailable for $displayName: ${t.javaClass.simpleName}: ${t.message}")
        }

        // The QNN Android package may not include XNNPACK. Still keep the small 640px nano model
        // as the compatibility path instead of escalating to YOLO26x/960 on generic CPU.
        val cpuOptions = OrtSession.SessionOptions()
        try {
            cpuOptions.setIntraOpNumThreads(threads)
            cpuOptions.setInterOpNumThreads(1)
            val created = environment.createSession(model.absolutePath, cpuOptions)
            installSession(created, cpuOptions, "CPU", threads)
        } catch (t: Throwable) {
            runCatching { cpuOptions.close() }
            logger.warn("FAST", "$displayName CPU load failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun installSession(
        created: OrtSession,
        newOptions: OrtSession.SessionOptions,
        backend: String,
        threads: Int
    ) {
        inputName = created.inputNames.first()
        val info = created.inputInfo[inputName]?.info as? TensorInfo
        val shape = info?.shape
        if (shape != null && shape.size >= 4) {
            if (shape[2] > 0) inputHeight = shape[2].toInt()
            if (shape[3] > 0) inputWidth = shape[3].toInt()
        }
        session = created
        options = newOptions
        activeBackend = backend
        logger.info("FAST", "$backendName loaded input=${inputWidth}x$inputHeight threads=$threads")
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
        val candidates = ArrayList<Detection>()

        rows.forEach { row ->
            if (row.size < 6) return@forEach
            if (row.size == 6) {
                val score = row[4]
                if (score < CONFIDENCE_THRESHOLD) return@forEach
                candidates += Detection(
                    labelFor(row[5].toInt()), score,
                    normalizeBox(row[0], row[1], row[2], row[3])
                )
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
                    labelFor(bestClass), bestScore,
                    normalizeBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                )
            }
        }
        return nms(candidates, 0.45f).take(100)
    }

    private fun normalizeBox(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val sx = if (max(max(x1, x2), 1f) > 1.5f) inputWidth.toFloat() else 1f
        val sy = if (max(max(y1, y2), 1f) > 1.5f) inputHeight.toFloat() else 1f
        return RectF(
            (x1 / sx).coerceIn(0f, 1f),
            (y1 / sy).coerceIn(0f, 1f),
            (x2 / sx).coerceIn(0f, 1f),
            (y2 / sy).coerceIn(0f, 1f)
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
        return Bitmap.createBitmap(bitmap, (bitmap.width - size) / 2, (bitmap.height - size) / 2, size, size)
    }

    private fun labelFor(index: Int): String =
        if (index in COCO_LABELS.indices) COCO_LABELS[index] else "object_$index"

    override fun close() {
        runCatching { session?.close() }
        runCatching { options?.close() }
        session = null
        options = null
        activeBackend = "PROBE"
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
