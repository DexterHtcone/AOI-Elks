package com.elks.aoi.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.jvm.JvmName

enum class DiffMetric {
    /** Средняя absdiff по ячейке (быстро, грубо). */
    MEAN,
    /** Нормированная корреляция 1−ZNCC — устойчивее к освещению. */
    ZNCC,
    /** Доля пикселей с большой разностью (локальные дефекты). */
    PIXEL_RATIO
}

enum class WorkResolution(val label: String, val longSide: Int) {
    LOW("640 px (быстро)", 640),
    MED("960 px", 960),
    HIGH("1280 px", 1280),
    ULTRA("1600 px (точно, медленнее)", 1600)
}

/**
 * Persistent app settings. Observable for Compose.
 */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Camera / UX ──────────────────────────────────────────
    var autoTorch by mutableStateOf(prefs.getBoolean(K_AUTO_TORCH, true))
        private set
    var autoCapture by mutableStateOf(prefs.getBoolean(K_AUTO_CAPTURE, true))
        private set
    var soundEnabled by mutableStateOf(prefs.getBoolean(K_SOUND, true))
        private set
    var guidanceOverlay by mutableStateOf(prefs.getBoolean(K_GUIDANCE, true))
        private set

    // ── Detection ────────────────────────────────────────────
    var workResolution by mutableStateOf(
        WorkResolution.entries.getOrElse(prefs.getInt(K_RES, 1)) { WorkResolution.MED }
    )
        private set
    var metric by mutableStateOf(
        DiffMetric.entries.getOrElse(prefs.getInt(K_METRIC, 1)) { DiffMetric.ZNCC }
    )
        private set
    var threshold by mutableStateOf(prefs.getFloat(K_THRESHOLD, 0.18f))
        private set
    var gridX by mutableStateOf(prefs.getInt(K_GRID_X, 12))
        private set
    var gridY by mutableStateOf(prefs.getInt(K_GRID_Y, 10))
        private set
    var useClahe by mutableStateOf(prefs.getBoolean(K_CLAHE, true))
        private set
    var useGeometricMask by mutableStateOf(prefs.getBoolean(K_GEO_MASK, true))
        private set
    var minMatches by mutableStateOf(prefs.getInt(K_MIN_MATCHES, 12))
        private set
    var minInliers by mutableStateOf(prefs.getInt(K_MIN_INLIERS, 20))
        private set
    var maxDefectFraction by mutableStateOf(prefs.getFloat(K_MAX_FRAC, 0.35f))
        private set
    var savePng by mutableStateOf(prefs.getBoolean(K_SAVE_PNG, true))
        private set
    var showAllZones by mutableStateOf(prefs.getBoolean(K_SHOW_ALL, true))
        private set

    /** Global scale: millimetres per pixel. 0 = not calibrated. */
    var mmPerPixel by mutableStateOf(prefs.getFloat(K_MM_PER_PX, 0f))
        private set

    @JvmName("setAutoTorchValue")
    fun setAutoTorch(v: Boolean) { autoTorch = v; prefs.edit().putBoolean(K_AUTO_TORCH, v).apply() }
    @JvmName("setAutoCaptureValue")
    fun setAutoCapture(v: Boolean) { autoCapture = v; prefs.edit().putBoolean(K_AUTO_CAPTURE, v).apply() }
    @JvmName("setSoundEnabledValue")
    fun setSoundEnabled(v: Boolean) { soundEnabled = v; prefs.edit().putBoolean(K_SOUND, v).apply() }
    @JvmName("setGuidanceOverlayValue")
    fun setGuidanceOverlay(v: Boolean) { guidanceOverlay = v; prefs.edit().putBoolean(K_GUIDANCE, v).apply() }

    @JvmName("setWorkResolutionValue")
    fun setWorkResolution(v: WorkResolution) {
        workResolution = v
        prefs.edit().putInt(K_RES, v.ordinal).apply()
    }
    @JvmName("setMetricValue")
    fun setMetric(v: DiffMetric) {
        metric = v
        prefs.edit().putInt(K_METRIC, v.ordinal).apply()
    }
    @JvmName("setThresholdValue")
    fun setThreshold(v: Float) {
        threshold = v.coerceIn(0.02f, 0.60f)
        prefs.edit().putFloat(K_THRESHOLD, threshold).apply()
    }
    @JvmName("setGridXValue")
    fun setGridX(v: Int) {
        gridX = v.coerceIn(4, 24)
        prefs.edit().putInt(K_GRID_X, gridX).apply()
    }
    @JvmName("setGridYValue")
    fun setGridY(v: Int) {
        gridY = v.coerceIn(4, 20)
        prefs.edit().putInt(K_GRID_Y, gridY).apply()
    }
    @JvmName("setUseClaheValue")
    fun setUseClahe(v: Boolean) { useClahe = v; prefs.edit().putBoolean(K_CLAHE, v).apply() }
    @JvmName("setUseGeometricMaskValue")
    fun setUseGeometricMask(v: Boolean) { useGeometricMask = v; prefs.edit().putBoolean(K_GEO_MASK, v).apply() }
    @JvmName("setMinMatchesValue")
    fun setMinMatches(v: Int) {
        minMatches = v.coerceIn(5, 50)
        prefs.edit().putInt(K_MIN_MATCHES, minMatches).apply()
    }
    @JvmName("setMinInliersValue")
    fun setMinInliers(v: Int) {
        minInliers = v.coerceIn(8, 80)
        prefs.edit().putInt(K_MIN_INLIERS, minInliers).apply()
    }
    @JvmName("setMaxDefectFractionValue")
    fun setMaxDefectFraction(v: Float) {
        maxDefectFraction = v.coerceIn(0.15f, 0.80f)
        prefs.edit().putFloat(K_MAX_FRAC, maxDefectFraction).apply()
    }
    @JvmName("setSavePngValue")
    fun setSavePng(v: Boolean) { savePng = v; prefs.edit().putBoolean(K_SAVE_PNG, v).apply() }
    @JvmName("setShowAllZonesValue")
    fun setShowAllZones(v: Boolean) { showAllZones = v; prefs.edit().putBoolean(K_SHOW_ALL, v).apply() }

    @JvmName("setMmPerPixelValue")
    fun setMmPerPixel(v: Float) {
        mmPerPixel = if (v <= 0f) 0f else v.coerceIn(0.0001f, 5f)
        prefs.edit().putFloat(K_MM_PER_PX, mmPerPixel).apply()
    }

    fun resetDefaults() {
        setAutoTorch(true)
        setAutoCapture(true)
        setSoundEnabled(true)
        setGuidanceOverlay(true)
        setWorkResolution(WorkResolution.MED)
        setMetric(DiffMetric.ZNCC)
        setThreshold(0.18f)
        setGridX(12)
        setGridY(10)
        setUseClahe(true)
        setUseGeometricMask(true)
        setMinMatches(12)
        setMinInliers(20)
        setMaxDefectFraction(0.35f)
        setSavePng(true)
        setShowAllZones(true)
        // mmPerPixel intentionally kept (user calibration)
    }

    companion object {
        private const val PREFS = "aoi_elks_settings"
        private const val K_AUTO_TORCH = "auto_torch"
        private const val K_AUTO_CAPTURE = "auto_capture"
        private const val K_SOUND = "sound"
        private const val K_GUIDANCE = "guidance"
        private const val K_RES = "work_res"
        private const val K_METRIC = "metric"
        private const val K_THRESHOLD = "threshold"
        private const val K_GRID_X = "grid_x"
        private const val K_GRID_Y = "grid_y"
        private const val K_CLAHE = "clahe"
        private const val K_GEO_MASK = "geo_mask"
        private const val K_MIN_MATCHES = "min_matches"
        private const val K_MIN_INLIERS = "min_inliers"
        private const val K_MAX_FRAC = "max_frac"
        private const val K_SAVE_PNG = "save_png"
        private const val K_SHOW_ALL = "show_all"
        private const val K_MM_PER_PX = "mm_per_px"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
