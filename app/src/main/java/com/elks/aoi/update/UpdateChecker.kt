package com.elks.aoi.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK and can download/install it.
 *
 * Repo must be public OR user must be able to open the releases page.
 * Expected release asset: *.apk
 * Tag format: v0.3.1 or 0.3.1
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val OWNER = "DexterHtcone"
    private const val REPO = "AOI-Elks"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    data class UpdateInfo(
        val available: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val apkUrl: String?,
        val releaseNotes: String,
        val htmlUrl: String,
        val error: String? = null
    )

    suspend fun check(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AOI-Elks-App")
                connectTimeout = 12000
                readTimeout = 12000
            }

            val code = conn.responseCode
            if (code == 404) {
                return@withContext UpdateInfo(
                    available = false,
                    latestVersion = currentVersion,
                    currentVersion = currentVersion,
                    apkUrl = null,
                    releaseNotes = "",
                    htmlUrl = RELEASES_PAGE,
                    error = "Релизов ещё нет. Создайте Release на GitHub с APK."
                )
            }
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                return@withContext UpdateInfo(
                    available = false,
                    latestVersion = currentVersion,
                    currentVersion = currentVersion,
                    apkUrl = null,
                    releaseNotes = "",
                    htmlUrl = RELEASES_PAGE,
                    error = "Не удалось проверить: $err. Репозиторий должен быть публичным."
                )
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v")
            val notes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", RELEASES_PAGE)

            var apkUrl: String? = null
            val assets: JSONArray = json.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", null)
                    break
                }
            }

            val newer = isNewer(tag, currentVersion)
            UpdateInfo(
                available = newer && apkUrl != null,
                latestVersion = tag.ifEmpty { currentVersion },
                currentVersion = currentVersion,
                apkUrl = apkUrl,
                releaseNotes = notes,
                htmlUrl = htmlUrl,
                error = when {
                    tag.isEmpty() -> "Пустой tag_name"
                    apkUrl == null -> "В релизе нет APK-файла"
                    !newer -> null
                    else -> null
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "check failed", e)
            UpdateInfo(
                available = false,
                latestVersion = currentVersion,
                currentVersion = currentVersion,
                apkUrl = null,
                releaseNotes = "",
                htmlUrl = RELEASES_PAGE,
                error = e.message ?: "Ошибка сети"
            )
        }
    }

    /** Compare semver-ish strings: 0.3.1 > 0.3.0 */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }

        val r = parts(remote)
        val l = parts(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    fun enqueueDownload(context: Context, apkUrl: String, version: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("AOI Elks $version")
            setDescription("Загрузка обновления")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "AOI-Elks-$version.apk"
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        return dm.enqueue(req)
    }

    fun openReleasesPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
