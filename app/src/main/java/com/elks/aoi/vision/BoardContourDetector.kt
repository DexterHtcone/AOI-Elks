package com.elks.aoi.vision

import android.util.Log
import androidx.camera.core.ImageProxy
import com.elks.aoi.AoiApplication
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Live PCB/object outer-contour detector for calibration overlay.
 *
 * Coordinates are returned in **upright display space** (sensor frame rotated by
 * [ImageProxy.getImageInfo] rotationDegrees), normalized to [0..1].
 * The UI must map them through FILL_CENTER using [ContourResult.imageAspect].
 */
object BoardContourDetector {

    private const val TAG = "BoardContour"
    private const val WORK_LONG = 360

    data class NormPoint(val x: Float, val y: Float)

    data class ContourResult(
        val points: List<NormPoint>,
        /** width / height after rotation to upright orientation */
        val imageAspect: Float
    )

    fun detect(image: ImageProxy): ContourResult {
        if (!AoiApplication.openCvReady) return ContourResult(emptyList(), 1f)

        var yMat: Mat? = null
        var upright: Mat? = null
        var small: Mat? = null
        var blurred: Mat? = null
        var binary: Mat? = null
        var hierarchy: Mat? = null

        try {
            val width = image.width
            val height = image.height
            if (width < 32 || height < 32) return ContourResult(emptyList(), 1f)

            yMat = yPlaneToMat(image) ?: return ContourResult(emptyList(), 1f)

            val rotation = image.imageInfo.rotationDegrees
            upright = rotateToUpright(yMat, rotation)
            val uW = upright.cols()
            val uH = upright.rows()
            val aspect = uW.toFloat() / uH.toFloat().coerceAtLeast(1f)

            val longSide = max(uW, uH)
            val scale = WORK_LONG.toDouble() / longSide
            val workW = max(48, (uW * scale).toInt())
            val workH = max(48, (uH * scale).toInt())

            small = Mat()
            Imgproc.resize(upright, small, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

            blurred = Mat()
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)

            // Adaptive threshold is more stable for dark objects on light table than pure Canny
            binary = Mat()
            Imgproc.adaptiveThreshold(
                blurred, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 31, 7.0
            )
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
            kernel.release()

            val contours = ArrayList<MatOfPoint>()
            hierarchy = Mat()
            Imgproc.findContours(
                binary, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val imgArea = (workW * workH).toDouble()
            val cxImg = workW / 2.0
            val cyImg = workH / 2.0
            var bestPts: Array<Point>? = null
            var bestScore = 0.0

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                // Object should fill a reasonable portion of the frame, not the whole table edge
                if (area < imgArea * 0.03 || area > imgArea * 0.72) {
                    c.release()
                    continue
                }

                val rect = Imgproc.boundingRect(c)
                val aspectRect = rect.width.toDouble() / rect.height.toDouble().coerceAtLeast(1.0)
                // Reject extremely skinny strips (typical false table-edge detections)
                if (aspectRect < 0.2 || aspectRect > 5.0) {
                    c.release()
                    continue
                }

                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.03 * peri, true)
                val pts = approx.toArray()

                // Solidity: contour area / bounding box area
                val boxArea = (rect.width * rect.height).toDouble().coerceAtLeast(1.0)
                val solidity = area / boxArea
                if (solidity < 0.35) {
                    c2f.release(); approx.release(); c.release()
                    continue
                }

                // Prefer 4-corner rectangles near the center of the frame
                val m = Imgproc.moments(c)
                val mx = if (m.m00 != 0.0) m.m10 / m.m00 else cxImg
                val my = if (m.m00 != 0.0) m.m01 / m.m00 else cyImg
                val distNorm = hypot(mx - cxImg, my - cyImg) / hypot(cxImg, cyImg)
                val centerBonus = 1.0 + (1.0 - distNorm.coerceIn(0.0, 1.0))

                val rectBonus = when {
                    pts.size == 4 -> 1.6
                    pts.size in 5..6 -> 1.2
                    pts.size in 3..8 -> 1.0
                    else -> 0.6
                }

                val score = area * rectBonus * centerBonus * solidity
                c2f.release()
                approx.release()
                c.release()

                if (score > bestScore && pts.size in 3..10) {
                    bestScore = score
                    // Prefer axis-aligned bounding quad for stable overlay when approx is noisy
                    bestPts = if (pts.size == 4) pts else arrayOf(
                        Point(rect.x.toDouble(), rect.y.toDouble()),
                        Point((rect.x + rect.width).toDouble(), rect.y.toDouble()),
                        Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
                        Point(rect.x.toDouble(), (rect.y + rect.height).toDouble())
                    )
                }
            }

            val chosen = bestPts ?: return ContourResult(emptyList(), aspect)

            val points = chosen.map { p ->
                NormPoint(
                    x = (p.x / workW).toFloat().coerceIn(0f, 1f),
                    y = (p.y / workH).toFloat().coerceIn(0f, 1f)
                )
            }
            return ContourResult(points, aspect)
        } catch (e: Exception) {
            Log.w(TAG, "detect failed: ${e.message}")
            return ContourResult(emptyList(), 1f)
        } finally {
            yMat?.release()
            upright?.release()
            small?.release()
            blurred?.release()
            binary?.release()
            hierarchy?.release()
        }
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
