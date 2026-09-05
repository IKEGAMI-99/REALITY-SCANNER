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
import android.view.Gravity
import android.view.ViewGroup
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
import com.ikegami99.realityscanner.detection.DetectorCascade
import com.ikegami99.realityscanner.detection.YoloFastOnnxDetector
import com.ikegami99.realityscanner.detection.YoloOnnxDetector
import com.ikegami99.realityscanner.detection.YoloQnnDetector
import com.ikegami99.realityscanner.logging.AppLogger
import com.ikegami99.realityscanner.tracking.TrackManager
import com.ikegami99.realityscanner.ui.HudOverlayView
import com.ikegami99.realityscanner.ui.SquareFrameLayout
import com.ikegami99.realityscanner.ui.TerminalView
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
    private lateinit var header: TextView

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            logger.info("CAMERA", "permission granted")
            cameraController.start()
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
            text = "REALITY SCANNER v${BuildConfig.VERSION_NAME} // LOCAL\nMODE HYBRID  LOW LIGHT AUTO"
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.rgb(1, 8, 2))
        }
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val cameraSquare = SquareFrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        val hud = HudOverlayView(this)

        cameraSquare.addView(
            preview,
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
                YoloQnnDetector(applicationContext, logger, "qnn_s/model.onnx", "YOLO26S-QNN"),
                YoloQnnDetector(applicationContext, logger, "qnn_n/model.onnx", "YOLO26N-QNN"),
                YoloFastOnnxDetector(applicationContext, logger, "yolo26n.onnx", "YOLO26N-XNN"),
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

        terminal.onExport = { exportLogs() }
        terminal.onUpdate = { checkUpdate(manual = true) }

        logger.info("SYSTEM", "boot sequence start")
        logger.info("SYSTEM", "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        logger.info("SYSTEM", "offline hybrid inference pipeline ready")
        logger.info("MODEL", "priority=QNN-S(ext) > QNN-N(ext) > YOLO26N-small > YOLO26X-compat")

        ensureCamera()
        checkUpdate(manual = false)
    }

    private fun ensureCamera() {
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

            // Do not trust the provider's metadata alone. Re-open the exact URI and count the
            // bytes that can actually be read back. EXPORT is successful only if they match.
            val readBackBytes = contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
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

        val readBackBytes = file.inputStream().use { input ->
            var total = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
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
                        header.text =
                            "REALITY SCANNER v${BuildConfig.VERSION_NAME} // UPDATE ${result.release.version}\n" +
                            "MODE HYBRID  LOW LIGHT AUTO"

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
        if (::cameraController.isInitialized) cameraController.stop()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
