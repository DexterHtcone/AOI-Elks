package com.elks.aoi.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK.
 * Expects tag like v0.3.3 and an asset ending with .apk
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
                    error = "Релизов ещё нет"
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
                    error = "Ошибка проверки: $err"
                )
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JSONObject(body)
            val tag = normalizeVersion(json.optString("tag_name", ""))
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
            if (apkUrl == null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (name.endsWith(".zip", ignoreCase = true) &&
                        (name.contains("apk", ignoreCase = true) ||
                            name.contains("AOI", ignoreCase = true))
                    ) {
                        Log.w(TAG, "Found zip asset but need .apk for auto-update: $name")
                    }
                }
            }

            val current = normalizeVersion(currentVersion)
            val newer = isNewer(tag, current)

            UpdateInfo(
                available = newer && !apkUrl.isNullOrBlank(),
                latestVersion = tag.ifEmpty { current },
                currentVersion = current,
                apkUrl = apkUrl,
                releaseNotes = notes,
                htmlUrl = htmlUrl,
                error = when {
                    tag.isEmpty() -> "Пустой tag_name"
                    apkUrl == null -> "В релизе нет .apk (нужен файл .apk, не zip)"
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

    fun normalizeVersion(s: String): String =
        s.trim().removePrefix("v").removePrefix("V").trim()

    /** Compare semver-ish: 0.3.3 > 0.3.2 */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(s: String) = normalizeVersion(s)
            .split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }

        val r = parts(remote)
        val l = parts(local)
        if (r.isEmpty()) return false
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
        // Ensure receiver is registered before enqueue
        UpdateInstaller.register(context)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // Always force .apk in the filename — MIUI/Xiaomi sometimes drops the extension
        // when using only setTitle() or when Content-Disposition is ambiguous.
        val safeName = "AOI-Elks-v${normalizeVersion(version)}.apk"
        val req = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle(safeName)
            setDescription("Загрузка обновления AOI Elks")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
            addRequestHeader("Accept", "application/vnd.android.package-archive,*/*")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val id = dm.enqueue(req)
        UpdateInstaller.rememberDownloadId(context, id)
        Log.i(TAG, "Enqueued download id=$id name=$safeName")
        return id
    }

    fun openReleasesPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
