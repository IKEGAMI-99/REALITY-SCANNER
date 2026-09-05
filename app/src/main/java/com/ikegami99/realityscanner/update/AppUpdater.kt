package com.ikegami99.realityscanner.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ikegami99.realityscanner.BuildConfig
import com.ikegami99.realityscanner.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AppUpdater(
    private val context: Context,
    private val logger: AppLogger
) {
    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String
    )

    sealed class Result {
        data class Available(val release: Release) : Result()
        data object Current : Result()
        data class Error(val message: String) : Result()
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun check(callback: (Result) -> Unit) {
        executor.execute {
            val result = try {
                logger.info("UPDATE", "checking GitHub Releases")
                val connection = URL(
                    "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
                ).openConnection() as HttpURLConnection

                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "REALITY-SCANNER/${BuildConfig.VERSION_NAME}")

                val code = connection.responseCode
                if (code != 200) {
                    Result.Error("release endpoint HTTP $code")
                } else {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name", "")
                    val notes = json.optString("body", "")
                    val assets = json.optJSONArray("assets")

                    var apkUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    if (tag.isBlank() || apkUrl.isBlank()) {
                        Result.Error("latest release has no APK asset")
                    } else if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                        Result.Available(Release(tag, notes, apkUrl))
                    } else {
                        Result.Current
                    }
                }
            } catch (t: Throwable) {
                Result.Error("${t.javaClass.simpleName}: ${t.message}")
            }

            when (result) {
                is Result.Available -> logger.info("UPDATE", "available ${result.release.version}")
                Result.Current -> logger.info("UPDATE", "current ${BuildConfig.VERSION_NAME} is latest")
                is Result.Error -> logger.warn("UPDATE", result.message)
            }
            callback(result)
        }
    }

    fun downloadAndInstall(release: Release) {
        executor.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    logger.warn("UPDATE", "install permission required")
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return@execute
                }

                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apk = File(updateDir, "reality-scanner-${release.version}.apk")
                logger.info("UPDATE", "downloading ${release.version}")

                val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "REALITY-SCANNER/${BuildConfig.VERSION_NAME}")

                connection.inputStream.use { input ->
                    apk.outputStream().use { output -> input.copyTo(output) }
                }

                if (apk.length() <= 0L) error("downloaded APK is empty")
                logger.info("UPDATE", "download complete // ${apk.length() / 1024 / 1024}MB")

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(install)
            } catch (t: Throwable) {
                logger.error("UPDATE", "install failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = remote.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val count = maxOf(a.size, b.size)
        for (i in 0 until count) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }
}
