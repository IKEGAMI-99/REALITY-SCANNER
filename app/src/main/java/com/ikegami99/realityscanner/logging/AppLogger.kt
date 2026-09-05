package com.ikegami99.realityscanner.logging

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class AppLogger(private val context: Context) {
    data class Entry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun line(): String {
            val time = TIME_FORMAT.get().format(Date(timestamp))
            return "[$time][$tag][$level] $message"
        }
    }

    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()
    private val entries = ArrayDeque<Entry>()
    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val sessionFile = File(
        logDir,
        "reality_scanner_${FILE_FORMAT.get().format(Date())}.log"
    )

    @Synchronized
    fun log(tag: String, message: String, level: String = "INFO") {
        val entry = Entry(System.currentTimeMillis(), level, tag.uppercase(), message)
        entries.addLast(entry)
        while (entries.size > 2000) entries.removeFirst()

        runCatching {
            sessionFile.appendText(entry.line() + "\n")
        }

        listeners.forEach { it(entry) }
    }

    fun info(tag: String, message: String) = log(tag, message, "INFO")
    fun warn(tag: String, message: String) = log(tag, message, "WARN")
    fun error(tag: String, message: String) = log(tag, message, "ERROR")

    fun addListener(listener: (Entry) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Entry) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun exportJson(): String {
        val root = JSONObject()
        root.put("generated_at", System.currentTimeMillis())
        root.put("device", Build.MANUFACTURER + " " + Build.MODEL)
        root.put("android", Build.VERSION.RELEASE)
        root.put("sdk", Build.VERSION.SDK_INT)

        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("timestamp", entry.timestamp)
                    .put("level", entry.level)
                    .put("tag", entry.tag)
                    .put("message", entry.message)
            )
        }
        root.put("entries", array)
        return root.toString(2)
    }

    fun currentSessionFile(): File = sessionFile

    companion object {
        private val TIME_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
        private val FILE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
    }
}
