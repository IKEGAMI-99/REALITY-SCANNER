package com.ikegami99.realityscanner

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ikegami99.realityscanner.camera.CameraController
import com.ikegami99.realityscanner.demo.DemoFramePump
import com.ikegami99.realityscanner.detection.DetectorCascade
import com.ikegami99.realityscanner.detection.YoloFastOnnxDetector
import com.ikegami99.realityscanner.detection.YoloOnnxDetector
import com.ikegami99.realityscanner.detection.YoloQnnDetector
import com.ikegami99.realityscanner.logging.AppLogger
import com.ikegami99.realityscanner.tracking.TrackManager
import com.ikegami99.realityscanner.ui.HudOverlayView
import com.ikegami99.realityscanner.ui.SquareFrameLayout
import com.ikegami99.realityscanner.ui.TerminalView
import com.ikegami99.realityscanner.ui.YouTubeDemoView
import com.ikegami99.realityscanner.update.AppUpdater
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val green = Color.rgb(112, 255, 112)
    private val bgColor = Color.rgb(2, 6, 2)

    private lateinit var logger: AppLogger
    private lateinit var updater: AppUpdater
    private lateinit var terminal: TerminalView
    private lateinit var cameraController: CameraController
    private lateinit var demoFramePump: DemoFramePump
    private lateinit var header: TextView
    private lateinit var preview: PreviewView
    private lateinit var hud: HudOverlayView
    private lateinit var demoView: YouTubeDemoView

    private var isDemoMode = false
    private var availableUpdateVersion: String? = null

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            logger.info("CAMERA", "permission granted")
            if (!isDemoMode) cameraController.start()
        } else {
            logger.error("CAMERA", "permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logger = AppLogger(applicationContext)
        updater = AppUpdater(applicationContext, logger)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                maxOf(systemBars.left, cutout.left),
                maxOf(systemBars.top, cutout.top),
                maxOf(systemBars.right, cutout.right),
                maxOf(systemBars.bottom, cutout.bottom)
            )
            insets
        }

        header = TextView(this).apply {
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.rgb(1, 8, 2))
        }
        renderHeader()
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val cameraSquare = SquareFrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        demoView = YouTubeDemoView(this).apply {
            onVideoLoaded = { id ->
                logger.info("DEMO", "YouTube loaded // id=$id // crop=cover-square // AI=live")
            }
            onVideoError = { message -> logger.error("DEMO", message) }
        }
        hud = HudOverlayView(this)

        cameraSquare.addView(
            preview,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        cameraSquare.addView(
            demoView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        cameraSquare.addView(
            hud,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            cameraSquare,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        terminal = TerminalView(this)
        root.addView(
            terminal,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        logger.addListener { entry ->
            runOnUiThread { terminal.append(entry) }
        }

        val detector = DetectorCascade(
            logger,
            listOf(
                // Keep HTP first: if Snapdragon NPU accepts the external context it should be both
                // faster and more accurate than the reduced-resolution fallback.
                YoloQnnDetector(applicationContext, logger, "qnn_s/model.onnx", "YOLO26S-QNN"),
                YoloQnnDetector(applicationContext, logger, "qnn_n/model.onnx", "YOLO26N-QNN"),
                // FAST20 path: 320x320 is ~1/4 the pixel compute of 640 and prefers QNN GPU.
                YoloFastOnnxDetector(
                    applicationContext,
                    logger,
                    "yolo26n_320.onnx",
                    "YOLO26N-FAST20",
                    tryQnnGpu = true,
                    allowCpuFallback = true,
                    confidenceThreshold = 0.38f
                ),
                // Balanced compatibility fallbacks if the FAST20 model itself cannot load.
                YoloFastOnnxDetector(
                    applicationContext,
                    logger,
                    "yolo26n.onnx",
                    "YOLO26N-640",
                    tryQnnGpu = true,
                    allowCpuFallback = true,
                    confidenceThreshold = 0.40f
                ),
                YoloOnnxDetector(applicationContext, logger)
            )
        )
        cameraController = CameraController(
            lifecycleOwner = this,
            previewView = preview,
            detector = detector,
            trackManager = TrackManager(),
            hud = hud,
            logger = logger
        )
        demoFramePump = DemoFramePump(
            window = window,
            sourceView = demoView,
            cameraController = cameraController,
            logger = logger
        )

        terminal.onExport = { exportLogs() }
        terminal.onUpdate = { checkUpdate(manual = true) }
        terminal.onDemo = {
            if (isDemoMode) exitDemoMode() else showDemoDialog()
        }

        logger.info("SYSTEM", "boot sequence start")
        logger.info("SYSTEM", "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        logger.info("SYSTEM", "offline hybrid inference pipeline ready")
        logger.info("MODEL", "priority=QNN-S(ext) > QNN-N(ext) > FAST20-320-GPU/CPU > 640 > X")
        logger.info("PERF", "FAST20 target // detector input=320 // demo gate=40ms // capture=40ms")
        logger.info("DEMO", "YouTube demo ready // square crop // PixelCopy AI inference")

        ensureCamera()
        checkUpdate(manual = false)
    }

    private fun renderHeader() {
        val update = availableUpdateVersion?.let { " // UPDATE $it" }.orEmpty()
        val mode = if (isDemoMode) {
            "MODE DEMO  FAST20  YOUTUBE  AI LIVE"
        } else {
            "MODE FAST20  LOW LIGHT AUTO"
        }
        header.text = "REALITY SCANNER v${BuildConfig.VERSION_NAME} // LOCAL$update\n$mode"
    }

    private fun showDemoDialog() {
        val prefs = getSharedPreferences("demo", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "https://www.youtube.com/watch?v=..."
            setText(prefs.getString("last_youtube_url", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        AlertDialog.Builder(this)
            .setTitle("YOUTUBE DEMO")
            .setMessage(
                "YouTube URLまたは11文字のVideo IDを入力。映像を正方形に中央クロップし、" +
                    "表示中の動画フレームへYOLOをリアルタイム実行します。"
            )
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("PLAY") { _, _ ->
                val source = input.text?.toString()?.trim().orEmpty()
                if (source.isBlank()) {
                    logger.warn("DEMO", "start cancelled // empty YouTube URL")
                    return@setPositiveButton
                }
                if (enterDemoMode(source)) {
                    prefs.edit().putString("last_youtube_url", source).apply()
                }
            }
            .show()
    }

    private fun enterDemoMode(source: String): Boolean {
        if (!demoView.play(source)) return false

        isDemoMode = true
        cameraController.pause()
        preview.visibility = View.GONE
        demoView.visibility = View.VISIBLE
        hud.setTracks(emptyList())
        terminal.setDemoActive(true)
        renderHeader()
        demoFramePump.start()
        logger.info(
            "DEMO",
            "mode active // camera paused // YouTube center-crop cover // FAST20 YOLO enabled"
        )
        return true
    }

    private fun exitDemoMode() {
        if (!isDemoMode) return

        demoFramePump.stop()
        isDemoMode = false
        demoView.stopPlayback()
        demoView.visibility = View.GONE
        preview.visibility = View.VISIBLE
        hud.setTracks(emptyList())
        terminal.setDemoActive(false)
        renderHeader()
        logger.info("DEMO", "mode stopped // restoring live camera")
        ensureCamera()
    }

    private fun ensureCamera() {
        if (isDemoMode) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraController.start()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun exportLogs() {
        val payload = logger.exportJson()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            logger.error("LOG", "export failed: generated payload is empty")
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "reality_scanner_log_$stamp.json"
        logger.info("LOG", "export begin bytes=${bytes.size} target=$fileName")

        Thread {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    exportViaMediaStore(fileName, bytes)
                } else {
                    exportLegacy(fileName, bytes)
                }
            }.onSuccess { result ->
                logger.info("LOG", result)
            }.onFailure { error ->
                logger.error("LOG", "export failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }.start()
    }

    private fun exportViaMediaStore(fileName: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/REALITY_SCANNER"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null")

        try {
            val stream = contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("MediaStore returned null output stream")
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }

            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updatedRows = contentResolver.update(uri, finalizeValues, null, null)
            if (updatedRows <= 0) {
                throw IOException("MediaStore could not finalize pending file")
            }

            val readBackBytes = contentResolver.openInputStream(uri)?.use { inputStream ->
                var total = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    total += count
                }
                total
            } ?: throw IOException("MediaStore read-back stream was null")

            if (readBackBytes != bytes.size.toLong()) {
                throw IOException("read-back mismatch wrote=${bytes.size} read=$readBackBytes")
            }

            val metadataSize = runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else cursor.getLong(0)
                }
            }.getOrNull()

            contentResolver.notifyChange(uri, null)
            return "export completed // Downloads/REALITY_SCANNER/$fileName // " +
                "verifiedBytes=$readBackBytes metadataBytes=${metadataSize ?: -1L}"
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun exportLegacy(fileName: String, bytes: ByteArray): String {
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("external downloads directory unavailable")
        val dir = File(base, "REALITY_SCANNER").apply { mkdirs() }
        val file = File(dir, fileName)

        FileOutputStream(file, false).use { output ->
            output.write(bytes)
            output.flush()
            runCatching { output.fd.sync() }
        }

        val readBackBytes = file.inputStream().use { inputStream ->
            var total = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = inputStream.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        }
        if (readBackBytes != bytes.size.toLong()) {
            file.delete()
            throw IOException("legacy read-back mismatch wrote=${bytes.size} read=$readBackBytes")
        }
        return "export completed // ${file.absolutePath} // verifiedBytes=$readBackBytes"
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) logger.info("UPDATE", "manual update check requested")

        updater.check { result ->
            runOnUiThread {
                when (result) {
                    is AppUpdater.Result.Available -> {
                        availableUpdateVersion = result.release.version
                        renderHeader()

                        if (manual) {
                            AlertDialog.Builder(this)
                                .setTitle("UPDATE ${result.release.version}")
                                .setMessage(
                                    if (result.release.notes.isBlank()) "New APK release available."
                                    else result.release.notes.take(1500)
                                )
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("DOWNLOAD") { _, _ ->
                                    updater.downloadAndInstall(result.release)
                                }
                                .show()
                        }
                    }

                    AppUpdater.Result.Current -> {
                        availableUpdateVersion = null
                        renderHeader()
                        if (manual) {
                            AlertDialog.Builder(this)
                                .setTitle("SYSTEM CURRENT")
                                .setMessage("v${BuildConfig.VERSION_NAME} is the latest release.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }

                    is AppUpdater.Result.Error -> {
                        if (manual) {
                            AlertDialog.Builder(this)
                                .setTitle("UPDATE CHECK FAILED")
                                .setMessage(result.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::demoFramePump.isInitialized) demoFramePump.stop()
        if (::demoView.isInitialized) demoView.release()
        if (::cameraController.isInitialized) cameraController.stop()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
