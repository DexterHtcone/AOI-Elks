package com.elks.aoi

import android.app.Application
import android.util.Log
import com.elks.aoi.update.UpdateInstaller
import org.opencv.android.OpenCVLoader

class AoiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val ok = OpenCVLoader.initLocal()
        Log.i(TAG, if (ok) "OpenCV loaded successfully" else "OpenCV failed to load")
        openCvReady = ok
        // Register early so download-complete → install works even if AboutScreen is closed
        UpdateInstaller.register(this)
    }

    companion object {
        private const val TAG = "AOI-Elks"
        @Volatile
        var openCvReady: Boolean = false
            private set
    }
}
