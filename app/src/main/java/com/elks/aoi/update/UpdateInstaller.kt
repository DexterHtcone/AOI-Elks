package com.elks.aoi.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Listens for DownloadManager completion of our APK and launches the system installer.
 * Uses FileProvider so content:// URI works on Android 7+.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val PREFS = "aoi_update"
    private const val KEY_DOWNLOAD_ID = "pending_download_id"

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id < 0) return

            val pending = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_DOWNLOAD_ID, -1L)
            if (pending < 0 || pending != id) {
                Log.d(TAG, "Ignoring download id=$id (pending=$pending)")
                return
            }

            Log.i(TAG, "Download complete id=$id — installing")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_DOWNLOAD_ID).apply()

            installDownloadedApk(context, id)
        }
    }

    /** Call once from Application.onCreate (or MainActivity). Safe to call multiple times. */
    fun register(context: Context) {
        if (registered) return
        val appCtx = context.applicationContext
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.registerReceiver(
                    appCtx,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appCtx.registerReceiver(receiver, filter)
            }
            registered = true
            Log.i(TAG, "DownloadComplete receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
        }
    }

    fun rememberDownloadId(context: Context, downloadId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .apply()
        Log.i(TAG, "Remembered downloadId=$downloadId")
    }

    fun installDownloadedApk(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var localUri: Uri? = null
        var localPath: String? = null
        var status = -1
        var reason = -1

        val query = DownloadManager.Query().setFilterById(downloadId)
        var cursor: Cursor? = null
        try {
            cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val pathIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)

                status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                if (uriIdx >= 0) {
                    val s = cursor.getString(uriIdx)
                    if (!s.isNullOrBlank()) localUri = Uri.parse(s)
                }
                if (pathIdx >= 0) {
                    localPath = cursor.getString(pathIdx)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "query download failed", e)
        } finally {
            cursor?.close()
        }

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Log.e(TAG, "Download not successful: status=$status reason=$reason")
            return
        }

        Log.i(TAG, "localUri=$localUri localPath=$localPath")

        // Prefer FileProvider content URI for reliability on Android 7+
        val contentUri: Uri? = try {
            when {
                !localPath.isNullOrBlank() -> {
                    val file = File(localPath)
                    if (file.exists() && file.length() > 0) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else null
                }
                localUri != null && localUri.scheme == "file" -> {
                    val file = File(localUri.path!!)
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else localUri
                }
                else -> localUri
            }
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider failed, falling back to localUri", e)
            localUri
        }

        if (contentUri == null) {
            Log.e(TAG, "No URI for downloaded APK")
            return
        }

        // On Android 8+ user must allow install from this app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Log.w(TAG, "Install unknown apps not granted — opening settings")
                try {
                    val settings = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settings)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open unknown sources settings", e)
                }
                // Still try install — system may prompt
            }
        }

        try {
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Some OEMs need this
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            // Grant to package installer packages if possible
            val resInfo = context.packageManager.queryIntentActivities(
                install, PackageManager.MATCH_DEFAULT_ONLY
            )
            for (ri in resInfo) {
                context.grantUriPermission(
                    ri.activityInfo.packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            context.startActivity(install)
            Log.i(TAG, "Install intent started for $contentUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install intent", e)
            // Fallback: open Downloads folder / file via VIEW
            try {
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    data = contentUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(fallback)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback also failed", e2)
            }
        }
    }
}
