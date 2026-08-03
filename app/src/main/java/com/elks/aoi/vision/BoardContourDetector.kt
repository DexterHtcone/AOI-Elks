package com.elks.aoi.vision

import android.util.Log
import androidx.camera.core.ImageProxy
import com.elks.aoi.AoiApplication
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight live detector of the PCB outer contour for calibration camera overlay.
 * Returns normalized [0..1] polygon points (image space, rotation already applied by caller if needed).
 */
object BoardContourDetector {

    private const val TAG = "BoardContour"
    private const val WORK_LONG = 320

    data class NormPoint(val x: Float, val y: Float)

    /**
     * Detect largest board-like contour from YUV ImageProxy (uses Y plane only).
     * Safe to call from analyzer thread. Returns empty list if OpenCV not ready or nothing found.
     */
    fun detect(image: ImageProxy): List<NormPoint> {
        if (!AoiApplication.openCvReady) return emptyList()

        var yMat: Mat? = null
        var small: Mat? = null
        var blurred: Mat? = null
        var edges: Mat? = null
        var hierarchy: Mat? = null

        try {
            val width = image.width
            val height = image.height
            if (width < 32 || height < 32) return emptyList()

            yMat = yPlaneToMat(image)
            if (yMat == null || yMat.empty()) return emptyList()

            val longSide = max(width, height)
            val scale = WORK_LONG.toDouble() / longSide
            val workW = max(40, (width * scale).toInt())
            val workH = max(40, (height * scale).toInt())

            small = Mat()
            Imgproc.resize(yMat, small, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

            blurred = Mat()
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)

            edges = Mat()
            Imgproc.Canny(blurred, edges, 40.0, 120.0)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(edges, edges, kernel)
            kernel.release()

            val contours = ArrayList<MatOfPoint>()
            hierarchy = Mat()
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val imgArea = (workW * workH).toDouble()
            var bestPts: Array<Point>? = null
            var bestScore = 0.0

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                // Board should occupy a meaningful part of the frame
                if (area < imgArea * 0.04 || area > imgArea * 0.92) {
                    c.release()
                    continue
                }

                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.025 * peri, true)
                val pts = approx.toArray()
                c2f.release()
                approx.release()
                c.release()

                if (pts.size < 4 || pts.size > 12) continue

                // Prefer nearly-rectangular (4 corners) but accept slightly more complex outlines
                val rectBonus = if (pts.size == 4) 1.35 else if (pts.size <= 6) 1.1 else 1.0
                val score = area * rectBonus
                if (score > bestScore) {
                    bestScore = score
                    bestPts = pts
                }
            }

            val chosen = bestPts ?: return emptyList()

            // Normalize to [0..1] in original image coordinates
            return chosen.map { p ->
                NormPoint(
                    x = (p.x / workW).toFloat().coerceIn(0f, 1f),
                    y = (p.y / workH).toFloat().coerceIn(0f, 1f)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "detect failed: ${e.message}")
            return emptyList()
        } finally {
            yMat?.release()
            small?.release()
            blurred?.release()
            edges?.release()
            hierarchy?.release()
        }
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

        // Copy row-by-row accounting for stride / pixelStride
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
