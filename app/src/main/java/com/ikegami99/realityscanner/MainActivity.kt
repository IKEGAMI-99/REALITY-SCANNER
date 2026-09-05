package com.ikegami99.realityscanner

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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

    private var pendingExport: String? = null

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

    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri != null && data != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(data.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                logger.info("LOG", "export completed")
            }.onFailure {
                logger.error("LOG", "export failed: ${it.message}")
            }
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

        // QNN/HTP is the preferred live path. If the vendor firmware rejects the context binary,
        // fall back to the 640px nano detector on XNNPACK before ever considering YOLO26x/960.
        // This guarantees that a failed NPU probe does not recreate the ~3.8 s/frame behavior.
        val detector = DetectorCascade(
            logger,
            listOf(
                YoloQnnDetector(applicationContext, logger, "yolo26s_v79_qnn.onnx", "YOLO26S-QNN"),
                YoloQnnDetector(applicationContext, logger, "yolo26n_v79_qnn.onnx", "YOLO26N-QNN"),
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
        logger.info("MODEL", "priority=QNN-S > QNN-N > XNN-N > XNN-X")

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
        pendingExport = logger.exportJson()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        exportDocument.launch("reality_scanner_log_$stamp.json")
        logger.info("LOG", "export UI opened")
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
