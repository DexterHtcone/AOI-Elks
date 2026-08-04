package com.elks.aoi.vision

import android.util.Log
import androidx.camera.core.ImageProxy
import com.elks.aoi.AoiApplication
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Automatic scale estimation from a metric ruler in the camera frame.
 *
 * Detects regularly spaced tick marks (Hough + clustering) and assumes the
 * dominant spacing equals [ASSUMED_TICK_MM] millimetres (standard 1 mm ruler).
 *
 * Phone ToF / dual-camera depth is NOT used: public CameraX APIs do not expose
 * a reliable cross-device rangefinder. Pure CV on a metric ruler is the portable approach.
 */
object RulerScaleDetector {

    private const val TAG = "RulerScale"
    private const val WORK_LONG = 480
    /** Most metric rulers have 1 mm minor ticks. */
    const val ASSUMED_TICK_MM = 1.0f

    data class Result(
        val mmPerPixel: Float,
        val tickSpacingPx: Float,
        val tickCount: Int,
        val confidence: Float
    )

    fun detect(image: ImageProxy): Result? {
        if (!AoiApplication.openCvReady) return null

        var yMat: Mat? = null
        var upright: Mat? = null
        var small: Mat? = null
        var edges: Mat? = null

        try {
            yMat = yPlaneToMat(image) ?: return null
            upright = rotateToUpright(yMat, image.imageInfo.rotationDegrees)
            val uW = upright.cols()
            val uH = upright.rows()

            val longSide = max(uW, uH)
            val scale = WORK_LONG.toDouble() / longSide
            val workW = max(64, (uW * scale).toInt())
            val workH = max(64, (uH * scale).toInt())

            small = Mat()
            Imgproc.resize(upright, small, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)

            edges = Mat()
            Imgproc.Canny(small, edges, 50.0, 150.0)

            val lines = Mat()
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 40, 12.0, 6.0)
            if (lines.empty() || lines.rows() < 6) {
                lines.release()
                return null
            }

            // Collect short tick-like segments: nearly vertical OR nearly horizontal
            val horizPos = ArrayList<Double>()
            val vertPos = ArrayList<Double>()

            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                val x1 = v[0]; val y1 = v[1]; val x2 = v[2]; val y2 = v[3]
                val dx = x2 - x1
                val dy = y2 - y1
                val len = kotlin.math.hypot(dx, dy)
                if (len < 8 || len > workH * 0.35) continue
                val angle = Math.toDegrees(kotlin.math.atan2(abs(dy), abs(dx)))
                when {
                    angle < 20.0 -> horizPos.add((y1 + y2) / 2.0) // horizontal ticks → y positions along vertical ruler
                    angle > 70.0 -> vertPos.add((x1 + x2) / 2.0)  // vertical ticks → x positions along horizontal ruler
                }
            }
            lines.release()

            val spacingWork = estimateRegularSpacing(horizPos)
                ?: estimateRegularSpacing(vertPos)
                ?: return null

            // Convert spacing from work-image pixels back to full upright image pixels
            val spacingFull = (spacingWork / scale).toFloat()
            if (spacingFull < 2f || spacingFull > min(uW, uH) * 0.2f) return null

            val mmPerPx = ASSUMED_TICK_MM / spacingFull
            val conf = (min(horizPos.size, vertPos.size).coerceAtLeast(
                max(horizPos.size, vertPos.size)
            ) / 20f).coerceIn(0.2f, 1f)

            Log.i(TAG, "tickSpacing=${spacingFull}px mm/px=$mmPerPx conf=$conf")
            return Result(
                mmPerPixel = mmPerPx,
                tickSpacingPx = spacingFull,
                tickCount = max(horizPos.size, vertPos.size),
                confidence = conf
            )
        } catch (e: Exception) {
            Log.w(TAG, "detect failed: ${e.message}")
            return null
        } finally {
            yMat?.release()
            upright?.release()
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
            if (g > 2.0) gaps.add(g)
        }
        if (gaps.size < 4) return null
        gaps.sort()
        val median = gaps[gaps.size / 2]
        // Count how many gaps are close to the median (regularity)
        val regular = gaps.count { abs(it - median) < median * 0.35 }
        if (regular < gaps.size * 0.45) return null
        if (median < 3.0 || median > 80.0) return null
        return median
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
