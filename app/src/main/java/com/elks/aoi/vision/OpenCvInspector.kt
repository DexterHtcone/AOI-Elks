package com.elks.aoi.vision

import android.graphics.Bitmap
import android.util.Log
import com.elks.aoi.AoiApplication
import com.elks.aoi.camera.DefectRegion
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCV-based AOI inspector.
 *
 * Pipeline:
 * 1. Scale both images to working resolution
 * 2. ORB feature detection + matching
 * 3. Homography (RANSAC) → warp captured onto reference
 * 4. Absolute difference + morphological cleanup
 * 5. Grid analysis → defect regions
 *
 * Falls back to simple zone comparison if OpenCV unavailable or alignment fails.
 */
object OpenCvInspector {

    private const val TAG = "OpenCvInspector"
    private const val WORK_W = 640
    private const val WORK_H = 480
    private const val MIN_GOOD_MATCHES = 12
    private const val GRID_X = 10
    private const val GRID_Y = 8
    private const val DEFAULT_THRESHOLD = 0.14 // mean abs-diff / 255

    data class Result(
        val defects: List<DefectRegion>,
        val aligned: Boolean,
        val matchCount: Int,
        val message: String
    )

    fun inspect(reference: Bitmap, captured: Bitmap, threshold: Float = DEFAULT_THRESHOLD.toFloat()): Result {
        if (!AoiApplication.openCvReady) {
            Log.w(TAG, "OpenCV not ready — fallback")
            return fallback(reference, captured, threshold)
        }

        var refMat: Mat? = null
        var capMat: Mat? = null
        var refGray: Mat? = null
        var capGray: Mat? = null
        var warped: Mat? = null
        var diff: Mat? = null

        try {
            // --- 1. Bitmap → Mat (RGBA) → BGR, scale ---
            refMat = bitmapToBgr(reference)
            capMat = bitmapToBgr(captured)

            Imgproc.resize(refMat, refMat, Size(WORK_W.toDouble(), WORK_H.toDouble()))
            Imgproc.resize(capMat, capMat, Size(WORK_W.toDouble(), WORK_H.toDouble()))

            refGray = Mat()
            capGray = Mat()
            Imgproc.cvtColor(refMat, refGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(capMat, capGray, Imgproc.COLOR_BGR2GRAY)

            // --- 2. ORB features ---
            val orb = ORB.create(1500)
            val kpRef = MatOfKeyPoint()
            val kpCap = MatOfKeyPoint()
            val descRef = Mat()
            val descCap = Mat()
            orb.detectAndCompute(refGray, Mat(), kpRef, descRef)
            orb.detectAndCompute(capGray, Mat(), kpCap, descCap)

            if (descRef.empty() || descCap.empty()) {
                Log.w(TAG, "No descriptors — fallback")
                return fallback(reference, captured, threshold)
            }

            // --- 3. Match + Lowe ratio ---
            val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(descCap, descRef, knn, 2)

            val good = ArrayList<DMatch>()
            for (m in knn) {
                val arr = m.toArray()
                if (arr.size >= 2 && arr[0].distance < 0.75f * arr[1].distance) {
                    good.add(arr[0])
                }
            }

            Log.i(TAG, "Good matches: ${good.size}")

            if (good.size < MIN_GOOD_MATCHES) {
                Log.w(TAG, "Too few matches (${good.size}) — fallback without alignment")
                val defects = zoneDiff(refGray, capGray, threshold)
                return Result(defects, false, good.size, "Мало совпадений (${good.size}) — сравнение без выравнивания")
            }

            // --- 4. Homography ---
            val srcPts = ArrayList<Point>()
            val dstPts = ArrayList<Point>()
            val kpCapArr = kpCap.toArray()
            val kpRefArr = kpRef.toArray()
            for (m in good) {
                srcPts.add(kpCapArr[m.queryIdx].pt)
                dstPts.add(kpRefArr[m.trainIdx].pt)
            }

            val srcMat = MatOfPoint2f(*srcPts.toTypedArray())
            val dstMat = MatOfPoint2f(*dstPts.toTypedArray())
            val mask = Mat()
            val H = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 5.0, mask)

            if (H.empty()) {
                Log.w(TAG, "Homography failed — fallback")
                return fallback(reference, captured, threshold)
            }

            // --- 5. Warp captured onto reference ---
            warped = Mat()
            Imgproc.warpPerspective(
                capGray, warped, H,
                Size(WORK_W.toDouble(), WORK_H.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0)
            )

            // --- 6. Absolute difference + morphology ---
            diff = Mat()
            Core.absdiff(refGray, warped, diff)

            // Suppress very dark borders from warp (where warp filled with 0)
            val warpMask = Mat()
            Imgproc.threshold(warped, warpMask, 8.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(diff, warpMask, diff)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_CLOSE, kernel)

            val defects = zoneDiff(diff, null, threshold, isDiffMap = true)

            descRef.release()
            descCap.release()
            kpRef.release()
            kpCap.release()
            srcMat.release()
            dstMat.release()
            mask.release()
            H.release()
            warpMask.release()
            kernel.release()

            return Result(
                defects = defects,
                aligned = true,
                matchCount = good.size,
                message = if (defects.isEmpty())
                    "✓ Выровнено ($good.size совп.) — брак не найден"
                else
                    "⚠ Выровнено ($good.size совп.) — зон брака: ${defects.size}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV inspect failed", e)
            return fallback(reference, captured, threshold)
        } finally {
            refMat?.release()
            capMat?.release()
            refGray?.release()
            capGray?.release()
            warped?.release()
            diff?.release()
        }
    }

    /** Zone mean on grayscale or precomputed diff map. */
    private fun zoneDiff(
        a: Mat,
        b: Mat?,
        threshold: Float,
        isDiffMap: Boolean = false
    ): List<DefectRegion> {
        val defects = mutableListOf<DefectRegion>()
        val cellW = a.cols() / GRID_X
        val cellH = a.rows() / GRID_Y
        if (cellW < 1 || cellH < 1) return defects

        for (gy in 0 until GRID_Y) {
            for (gx in 0 until GRID_X) {
                val x0 = gx * cellW
                val y0 = gy * cellH
                val x1 = if (gx == GRID_X - 1) a.cols() else x0 + cellW
                val y1 = if (gy == GRID_Y - 1) a.rows() else y0 + cellH
                val roiA = a.submat(y0, y1, x0, x1)

                val meanVal: Double = if (isDiffMap || b == null) {
                    val m = Core.mean(roiA)
                    m.`val`[0]
                } else {
                    val roiB = b.submat(y0, y1, x0, x1)
                    val tmp = Mat()
                    Core.absdiff(roiA, roiB, tmp)
                    val m = Core.mean(tmp)
                    tmp.release()
                    roiB.release()
                    m.`val`[0]
                }
                roiA.release()

                val norm = (meanVal / 255.0).toFloat()
                if (norm > threshold) {
                    defects.add(
                        DefectRegion(
                            left = gx.toFloat() / GRID_X,
                            top = gy.toFloat() / GRID_Y,
                            right = (gx + 1).toFloat() / GRID_X,
                            bottom = (gy + 1).toFloat() / GRID_Y
                        )
                    )
                }
            }
        }
        return defects
    }

    private fun fallback(reference: Bitmap, captured: Bitmap, threshold: Float): Result {
        // Pure Kotlin grid (same as before OpenCV)
        val defects = simpleZoneCompare(reference, captured, threshold)
        return Result(
            defects = defects,
            aligned = false,
            matchCount = 0,
            message = if (defects.isEmpty())
                "✓ Брак не обнаружен (без выравнивания)"
            else
                "⚠ Найдено зон: ${defects.size} (без выравнивания)"
        )
    }

    private fun simpleZoneCompare(reference: Bitmap, captured: Bitmap, threshold: Float): List<DefectRegion> {
        val defects = mutableListOf<DefectRegion>()
        val refScaled = Bitmap.createScaledBitmap(reference, WORK_W, WORK_H, true)
        val capScaled = Bitmap.createScaledBitmap(captured, WORK_W, WORK_H, true)
        val cellW = WORK_W / GRID_X
        val cellH = WORK_H / GRID_Y

        for (gy in 0 until GRID_Y) {
            for (gx in 0 until GRID_X) {
                var diffSum = 0.0
                var count = 0
                val x0 = gx * cellW
                val y0 = gy * cellH
                for (y in y0 until min(y0 + cellH, WORK_H)) {
                    for (x in x0 until min(x0 + cellW, WORK_W)) {
                        val p1 = refScaled.getPixel(x, y)
                        val p2 = capScaled.getPixel(x, y)
                        val l1 = 0.299 * ((p1 shr 16) and 0xFF) +
                            0.587 * ((p1 shr 8) and 0xFF) +
                            0.114 * (p1 and 0xFF)
                        val l2 = 0.299 * ((p2 shr 16) and 0xFF) +
                            0.587 * ((p2 shr 8) and 0xFF) +
                            0.114 * (p2 and 0xFF)
                        diffSum += abs(l1 - l2)
                        count++
                    }
                }
                val avg = if (count > 0) (diffSum / count) / 255.0 else 0.0
                if (avg > threshold) {
                    defects.add(
                        DefectRegion(
                            left = gx.toFloat() / GRID_X,
                            top = gy.toFloat() / GRID_Y,
                            right = (gx + 1).toFloat() / GRID_X,
                            bottom = (gy + 1).toFloat() / GRID_Y
                        )
                    )
                }
            }
        }
        refScaled.recycle()
        capScaled.recycle()
        return defects
    }

    private fun bitmapToBgr(bmp: Bitmap): Mat {
        val rgba = Mat()
        val safe = if (bmp.config != Bitmap.Config.ARGB_8888) {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } else bmp
        Utils.bitmapToMat(safe, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }
}
