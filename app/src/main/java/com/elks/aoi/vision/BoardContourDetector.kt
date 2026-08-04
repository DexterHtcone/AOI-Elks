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
 *
 * Improvements:
 *  - Reject contours that touch the image border (table / paper edges).
 *  - Prefer compact mid-sized rectangles near the optical centre.
 *  - Run both adaptive-threshold and Canny pipelines; pick the better candidate.
 */
object BoardContourDetector {

    private const val TAG = "BoardContour"
    private const val WORK_LONG = 400

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

            val fromAdaptive = findBestContour(blurred, workW, workH, useAdaptive = true)
            val fromCanny = findBestContour(blurred, workW, workH, useAdaptive = false)

            val best = when {
                fromAdaptive != null && fromCanny != null ->
                    if (fromAdaptive.score >= fromCanny.score) fromAdaptive else fromCanny
                fromAdaptive != null -> fromAdaptive
                fromCanny != null -> fromCanny
                else -> null
            } ?: return ContourResult(emptyList(), aspect)

            val points = best.pts.map { p ->
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
        }
    }

    private data class Candidate(val pts: Array<Point>, val score: Double)

    private fun findBestContour(
        gray: Mat,
        workW: Int,
        workH: Int,
        useAdaptive: Boolean
    ): Candidate? {
        var binary: Mat? = null
        var hierarchy: Mat? = null
        try {
            binary = Mat()
            if (useAdaptive) {
                Imgproc.adaptiveThreshold(
                    gray, binary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV, 35, 8.0
                )
            } else {
                val edges = Mat()
                Imgproc.Canny(gray, edges, 40.0, 120.0)
                val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.dilate(edges, binary, k)
                k.release()
                edges.release()
            }

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
            val margin = max(4, min(workW, workH) / 40)

            var best: Candidate? = null
            var bestScore = 0.0

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                // Compact object: 4 % … 40 % of frame (not whole desk)
                if (area < imgArea * 0.04 || area > imgArea * 0.40) {
                    c.release()
                    continue
                }

                val rect = Imgproc.boundingRect(c)
                // Touching image border → table / paper edge
                if (rect.x <= margin || rect.y <= margin ||
                    rect.x + rect.width >= workW - margin ||
                    rect.y + rect.height >= workH - margin
                ) {
                    c.release()
                    continue
                }

                val aspectRect = rect.width.toDouble() / rect.height.toDouble().coerceAtLeast(1.0)
                if (aspectRect < 0.25 || aspectRect > 4.5) {
                    c.release()
                    continue
                }

                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.025 * peri, true)
                val pts = approx.toArray()

                val boxArea = (rect.width * rect.height).toDouble().coerceAtLeast(1.0)
                val solidity = area / boxArea
                if (solidity < 0.40) {
                    c2f.release(); approx.release(); c.release()
                    continue
                }

                val minRect = Imgproc.minAreaRect(c2f)
                val mrSize = minRect.size
                val mrAspect = max(mrSize.width, mrSize.height) /
                    min(mrSize.width, mrSize.height).coerceAtLeast(1.0)
                if (mrAspect > 5.0) {
                    c2f.release(); approx.release(); c.release()
                    continue
                }

                val m = Imgproc.moments(c)
                val mx = if (m.m00 != 0.0) m.m10 / m.m00 else cxImg
                val my = if (m.m00 != 0.0) m.m01 / m.m00 else cyImg
                val distNorm = hypot(mx - cxImg, my - cyImg) / hypot(cxImg, cyImg)
                val centerBonus = 1.0 + 1.2 * (1.0 - distNorm.coerceIn(0.0, 1.0))

                val rectBonus = when {
                    pts.size == 4 -> 1.8
                    pts.size in 5..6 -> 1.3
                    pts.size in 3..8 -> 1.0
                    else -> 0.5
                }

                val landscapeBonus = when {
                    aspectRect in 1.2..3.2 -> 1.25
                    aspectRect in 0.6..1.2 -> 1.1
                    else -> 0.9
                }

                val fill = area / imgArea
                val sizeBonus = when {
                    fill in 0.08..0.28 -> 1.3
                    fill in 0.05..0.35 -> 1.1
                    else -> 0.85
                }

                val score = area * rectBonus * centerBonus * solidity * landscapeBonus * sizeBonus
                c2f.release()
                approx.release()
                c.release()

                if (score > bestScore && pts.size in 3..12) {
                    bestScore = score
                    val outPts = if (pts.size == 4) {
                        pts
                    } else {
                        arrayOf(
                            Point(rect.x.toDouble(), rect.y.toDouble()),
                            Point((rect.x + rect.width).toDouble(), rect.y.toDouble()),
                            Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
                            Point(rect.x.toDouble(), (rect.y + rect.height).toDouble())
                        )
                    }
                    best = Candidate(outPts, score)
                }
            }
            return best
        } finally {
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
