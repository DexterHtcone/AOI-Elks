package com.elks.aoi.vision

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.elks.aoi.AoiApplication
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Automatic scale estimation from a metric ruler in the camera frame.
 *
 * Detects regularly spaced tick marks (Hough + clustering) and assumes the
 * dominant spacing equals [ASSUMED_TICK_MM] millimetres (standard 1 mm ruler).
 *
 * Phone ToF / dual-camera depth is NOT used: public CameraX APIs do not expose
 * a reliable cross-device rangefinder. Pure CV on a metric ruler at the working
 * distance is the portable approach — calibrate once at the height you inspect boards.
 */
object RulerScaleDetector {

    private const val TAG = "RulerScale"
    private const val WORK_LONG = 560
    /** Most metric rulers have 1 mm minor ticks. */
    const val ASSUMED_TICK_MM = 1.0f

    data class Result(
        val mmPerPixel: Float,
        val tickSpacingPx: Float,
        val tickCount: Int,
        val confidence: Float,
        val message: String = ""
    )

    fun detect(image: ImageProxy): Result? {
        if (!AoiApplication.openCvReady) return null
        var yMat: Mat? = null
        var upright: Mat? = null
        try {
            yMat = yPlaneToMat(image) ?: return null
            upright = rotateToUpright(yMat, image.imageInfo.rotationDegrees)
            return detectFromGray(upright)
        } catch (e: Exception) {
            Log.w(TAG, "detect failed: ${e.message}")
            return null
        } finally {
            yMat?.release()
            upright?.release()
        }
    }

    fun detectFromBitmap(bitmap: Bitmap): Result? {
        if (!AoiApplication.openCvReady) return null
        var bgr: Mat? = null
        var gray: Mat? = null
        try {
            bgr = Mat()
            Utils.bitmapToMat(bitmap, bgr)
            if (bgr.channels() == 4) Imgproc.cvtColor(bgr, bgr, Imgproc.COLOR_RGBA2BGR)
            gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            return detectFromGray(gray)
        } catch (e: Exception) {
            Log.w(TAG, "detectFromBitmap failed: ${e.message}")
            return null
        } finally {
            bgr?.release()
            gray?.release()
        }
    }

    private fun detectFromGray(srcGray: Mat): Result? {
        var small: Mat? = null
        var edges: Mat? = null
        try {
            val uW = srcGray.cols()
            val uH = srcGray.rows()
            val longSide = max(uW, uH)
            val scale = WORK_LONG.toDouble() / longSide
            val workW = max(64, (uW * scale).toInt())
            val workH = max(64, (uH * scale).toInt())

            small = Mat()
            Imgproc.resize(srcGray, small, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)

            // Enhance local contrast for thin tick marks
            val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            clahe.apply(small, small)

            edges = Mat()
            Imgproc.Canny(small, edges, 40.0, 130.0)

            val lines = Mat()
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 30, 8.0, 4.0)
            if (lines.empty() || lines.rows() < 5) {
                lines.release()
                return null
            }

            val horizPos = ArrayList<Double>()
            val vertPos = ArrayList<Double>()

            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                val x1 = v[0]; val y1 = v[1]; val x2 = v[2]; val y2 = v[3]
                val dx = x2 - x1
                val dy = y2 - y1
                val len = hypot(dx, dy)
                // Tick marks are short; reject long edges (ruler body / desk)
                if (len < 6 || len > workH * 0.28) continue
                val angle = Math.toDegrees(kotlin.math.atan2(abs(dy), abs(dx)))
                when {
                    angle < 22.0 -> horizPos.add((y1 + y2) / 2.0)
                    angle > 68.0 -> vertPos.add((x1 + x2) / 2.0)
                }
            }
            lines.release()

            val spacingWork = estimateRegularSpacing(vertPos)
                ?: estimateRegularSpacing(horizPos)
                ?: return null

            val spacingFull = (spacingWork / scale).toFloat()
            if (spacingFull < 2f || spacingFull > min(uW, uH) * 0.18f) return null

            val mmPerPx = ASSUMED_TICK_MM / spacingFull
            val nTicks = max(horizPos.size, vertPos.size)
            val conf = (nTicks / 18f).coerceIn(0.25f, 1f)

            Log.i(TAG, "tickSpacing=${spacingFull}px mm/px=$mmPerPx ticks=$nTicks conf=$conf")
            return Result(
                mmPerPixel = mmPerPx,
                tickSpacingPx = spacingFull,
                tickCount = nTicks,
                confidence = conf,
                message = String.format(
                    "Авто: %.4f мм/px (шаг ≈ %.1f px, %d делений, conf %.0f%%)",
                    mmPerPx, spacingFull, nTicks, conf * 100
                )
            )
        } finally {
            small?.release()
            edges?.release()
        }
    }

    /** Median gap between sorted positions that form a regular grid. */
    private fun estimateRegularSpacing(positions: List<Double>): Double? {
        if (positions.size < 5) return null
        val sorted = positions.sorted()
        val gaps = ArrayList<Double>()
        for (i in 1 until sorted.size) {
            val g = sorted[i] - sorted[i - 1]
            if (g > 1.5) gaps.add(g)
        }
        if (gaps.size < 4) return null
        gaps.sort()
        val median = gaps[gaps.size / 2]
        // Also try the smallest common gap (1 mm) by looking at lower quartile
        val q1 = gaps[gaps.size / 4]
        val candidate = if (q1 > 2.0 && q1 < median * 0.7) q1 else median
        val regular = gaps.count { abs(it - candidate) < candidate * 0.40 || abs(it - 2 * candidate) < candidate * 0.40 }
        if (regular < gaps.size * 0.40) return null
        if (candidate < 2.5 || candidate > 90.0) return null
        return candidate
    }

    private fun rotateToUpright(src: Mat, rotationDegrees: Int): Mat {
        val dst = Mat()
        when ((rotationDegrees % 360 + 360) % 360) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(dst)
        }
        return dst
    }

    private fun yPlaneToMat(image: ImageProxy): Mat? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val mat = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (y in 0 until height) {
            val rowStart = y * rowStride
            if (pixelStride == 1) {
                buffer.position(rowStart)
                val toRead = min(width, buffer.remaining())
                if (toRead <= 0) break
                buffer.get(row, 0, toRead)
                mat.put(y, 0, row)
            } else {
                for (x in 0 until width) {
                    val idx = rowStart + x * pixelStride
                    row[x] = if (idx < buffer.capacity()) buffer.get(idx) else 0
                }
                mat.put(y, 0, row)
            }
        }
        buffer.rewind()
        return mat
    }
}
