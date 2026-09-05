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

class YoloOnnxDetector(
    private val context: Context,
    private val logger: AppLogger
) : Detector {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var sessionOptions: OrtSession.SessionOptions? = null
    private var inputName: String = ""
    private var inputWidth: Int = 960
    private var inputHeight: Int = 960
    private var attemptedLoad = false

    override val isReady: Boolean
        get() = session != null

    override var backendName: String = "NO MODEL"
        private set

    override fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int,
        lowLightGain: Float
    ): List<Detection> {
        ensureLoaded()
        val activeSession = session ?: return emptyList()

        val rotated = rotate(bitmap, rotationDegrees)
        val square = centerCropSquare(rotated)
        val resized = Bitmap.createScaledBitmap(square, inputWidth, inputHeight, true)

        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val planeSize = inputWidth * inputHeight
        val input = FloatArray(planeSize * 3)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (((color shr 16) and 0xFF) / 255f * lowLightGain).coerceAtMost(1f)
            val g = (((color shr 8) and 0xFF) / 255f * lowLightGain).coerceAtMost(1f)
            val b = ((color and 0xFF) / 255f * lowLightGain).coerceAtMost(1f)
            input[i] = r
            input[planeSize + i] = g
            input[planeSize * 2 + i] = b
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
                activeSession.run(mapOf(inputName to tensor)).use { result ->
                    parseOutput(result[0].value)
                }
            }
        } catch (t: Throwable) {
            logger.error("YOLO", "inference failed: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    private fun ensureLoaded() {
        if (attemptedLoad) return
        attemptedLoad = true

        val model = resolveModel()
        if (model == null) {
            backendName = "MODEL MISSING"
            logger.warn(
                "MODEL",
                "yolo26x.onnx not found. Place it in app/src/main/assets/models/ or files/models/."
            )
            return
        }

        // The stock ORT Android package contains XNNPACK. For this large FP32/FP16-style
        // convolutional graph it is substantially more useful than falling straight back to
        // the generic CPU EP, and unlike NNAPI it does not reject YOLO26's Split graph on the
        // reference POCO F7 Ultra.
        logger.info("MODEL", "loading ${model.name} // trying XNNPACK first")
        val xnn = createSession(model, Backend.XNNPACK)
        if (xnn != null) {
            installSession(xnn.first, xnn.second, "XNNPACK", model)
            return
        }

        logger.warn("MODEL", "XNNPACK unavailable -> trying NNAPI")
        val nnapi = createSession(model, Backend.NNAPI)
        if (nnapi != null) {
            installSession(nnapi.first, nnapi.second, "NNAPI", model)
            return
        }

        logger.warn("NPU", "NNAPI FAILED -> CPU FALLBACK")
        val cpu = createSession(model, Backend.CPU)
        if (cpu != null) {
            installSession(cpu.first, cpu.second, "CPU", model)
            return
        }

        backendName = "LOAD ERROR"
        logger.error("MODEL", "all ONNX Runtime execution providers failed")
    }

    private enum class Backend { XNNPACK, NNAPI, CPU }

    private fun createSession(
        model: File,
        backend: Backend
    ): Pair<OrtSession, OrtSession.SessionOptions>? {
        val options = OrtSession.SessionOptions()
        return try {
            when (backend) {
                Backend.XNNPACK -> {
                    val cores = Runtime.getRuntime().availableProcessors()
                    val threads = (cores - 2).coerceIn(2, 8)
                    options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                    logger.info("MODEL", "XNNPACK threads=$threads")
                }
                Backend.NNAPI -> options.addNnapi()
                Backend.CPU -> Unit
            }
            environment.createSession(model.absolutePath, options) to options
        } catch (t: Throwable) {
            val tag = if (backend == Backend.NNAPI) "NPU" else "MODEL"
            logger.warn(
                tag,
                "${backend.name} session rejected model: ${t.javaClass.simpleName}: ${t.message}"
            )
            runCatching { options.close() }
            null
        }
    }

    private fun installSession(
        newSession: OrtSession,
        newOptions: OrtSession.SessionOptions,
        backend: String,
        model: File
    ) {
        runCatching { session?.close() }
        runCatching { sessionOptions?.close() }
        session = newSession
        sessionOptions = newOptions
        inputName = newSession.inputNames.first()

        val info = newSession.inputInfo[inputName]?.info as? TensorInfo
        val shape = info?.shape
        if (shape != null && shape.size >= 4) {
            val h = shape[2].toInt()
            val w = shape[3].toInt()
            if (h > 0) inputHeight = h
            if (w > 0) inputWidth = w
        }

        backendName = backend
        logger.info(
            "MODEL",
            "loaded ${model.name} input=${inputWidth}x${inputHeight} backend=$backendName"
        )
    }

    private fun resolveModel(): File? {
        val privateModel = File(context.filesDir, "models/yolo26x.onnx")
        if (privateModel.exists() && privateModel.length() > 0L) return privateModel

        return try {
            context.assets.open("models/yolo26x.onnx").use { input ->
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

        val rows = if (matrix.size in 5..128 && matrix[0].size > matrix.size) {
            transpose(matrix)
        } else {
            matrix
        }

        val candidates = mutableListOf<Detection>()
        rows.forEach { row ->
            if (row.size < 6) return@forEach

            if (row.size == 6) {
                val score = row[4]
                if (score < CONFIDENCE_THRESHOLD) return@forEach
                val classId = row[5].toInt()
                val box = normalizeBox(row[0], row[1], row[2], row[3])
                candidates += Detection(labelFor(classId), score, box)
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
                val box = normalizeBox(
                    cx - w / 2f,
                    cy - h / 2f,
                    cx + w / 2f,
                    cy + h / 2f
                )
                candidates += Detection(labelFor(bestClass), bestScore, box)
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
        val batch = if (outer.size == 1 && outer[0] is Array<*>) {
            outer[0] as Array<*>
        } else {
            outer
        }

        val result = ArrayList<FloatArray>(batch.size)
        batch.forEach { row ->
            when (row) {
                is FloatArray -> result += row
                is Array<*> -> {
                    val converted = FloatArray(row.size)
                    row.forEachIndexed { index, cell ->
                        converted[index] = (cell as? Number)?.toFloat() ?: 0f
                    }
                    result += converted
                }
            }
        }
        return result.toTypedArray()
    }

    private fun transpose(matrix: Array<FloatArray>): Array<FloatArray> {
        val rows = matrix.size
        val cols = matrix[0].size
        return Array(cols) { c ->
            FloatArray(rows) { r -> matrix[r][c] }
        }
    }

    private fun nms(input: List<Detection>, threshold: Float): List<Detection> {
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            sorted.removeAll {
                it.label == best.label && iou(it.box, best.box) > threshold
            }
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
        runCatching { sessionOptions?.close() }
        session = null
        sessionOptions = null
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
