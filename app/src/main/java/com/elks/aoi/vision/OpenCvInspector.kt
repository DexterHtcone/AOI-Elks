package com.elks.aoi.vision

import android.graphics.Bitmap
import android.util.Log
import com.elks.aoi.AoiApplication
import com.elks.aoi.camera.DefectRegion
import org.opencv.android.Utils
import org.opencv.core.Core
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
import kotlin.math.min

object OpenCvInspector {

    private const val TAG = "OpenCvInspector"
    private const val WORK_W = 640
    private const val WORK_H = 480
    private const val MIN_GOOD_MATCHES = 15
    private const val GRID_X = 10
    private const val GRID_Y = 8
    /** Border cells ignored (warp / FOV artifacts). */
    private const val BORDER_SKIP = 1
    private const val DEFAULT_THRESHOLD = 0.22f
    /** If more than this fraction of cells are "defects" — scene mismatch, not real defects. */
    private const val MAX_DEFECT_FRACTION = 0.35f

    data class Result(
        val defects: List<DefectRegion>,
        val aligned: Boolean,
        val matchCount: Int,
        val message: String
    )

    fun inspect(reference: Bitmap, captured: Bitmap, threshold: Float = DEFAULT_THRESHOLD): Result {
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
            refMat = bitmapToBgr(reference)
            capMat = bitmapToBgr(captured)

            Imgproc.resize(refMat, refMat, Size(WORK_W.toDouble(), WORK_H.toDouble()))
            Imgproc.resize(capMat, capMat, Size(WORK_W.toDouble(), WORK_H.toDouble()))

            refGray = Mat()
            capGray = Mat()
            Imgproc.cvtColor(refMat, refGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(capMat, capGray, Imgproc.COLOR_BGR2GRAY)

            val orb = ORB.create(1500)
            val kpRef = MatOfKeyPoint()
            val kpCap = MatOfKeyPoint()
            val descRef = Mat()
            val descCap = Mat()
            orb.detectAndCompute(refGray, Mat(), kpRef, descRef)
            orb.detectAndCompute(capGray, Mat(), kpCap, descCap)

            if (descRef.empty() || descCap.empty()) {
                return fallback(reference, captured, threshold)
            }

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

            val matchCount = good.size
            Log.i(TAG, "Good matches: $matchCount")

            if (matchCount < MIN_GOOD_MATCHES) {
                val defects = zoneDiff(refGray, capGray, threshold)
                val filtered = filterAndCap(defects)
                return Result(
                    filtered,
                    false,
                    matchCount,
                    if (filtered.isEmpty())
                        "Мало совпадений ($matchCount) — брак не найден"
                    else
                        "Мало совпадений ($matchCount) — зон: ${filtered.size}"
                )
            }

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
                return fallback(reference, captured, threshold)
            }

            warped = Mat()
            Imgproc.warpPerspective(
                capGray, warped, H,
                Size(WORK_W.toDouble(), WORK_H.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0)
            )

            diff = Mat()
            Core.absdiff(refGray, warped, diff)

            val warpMask = Mat()
            Imgproc.threshold(warped, warpMask, 12.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(diff, warpMask, diff)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_CLOSE, kernel)

            val raw = zoneDiff(diff, null, threshold, isDiffMap = true)
            val defects = filterAndCap(raw)

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

            val msg = when {
                raw.size > (GRID_X * GRID_Y * MAX_DEFECT_FRACTION).toInt() ->
                    "⚠ Слишком много отличий ($matchCount совп.) — проверьте ракурс/эталон"
                defects.isEmpty() ->
                    "✓ Выровнено ($matchCount совп.) — брак не найден"
                else ->
                    "⚠ Выровнено ($matchCount совп.) — зон брака: ${defects.size}"
            }

            return Result(
                defects = if (raw.size > (GRID_X * GRID_Y * MAX_DEFECT_FRACTION).toInt()) emptyList() else defects,
                aligned = true,
                matchCount = matchCount,
                message = msg
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

    /** Drop border cells and cap count for clean UI. */
    private fun filterAndCap(raw: List<DefectRegion>): List<DefectRegion> {
        val cellW = 1f / GRID_X
        val cellH = 1f / GRID_Y
        val filtered = raw.filter { r ->
            val gx = ((r.left + r.right) / 2f / cellW).toInt()
            val gy = ((r.top + r.bottom) / 2f / cellH).toInt()
            gx in BORDER_SKIP until (GRID_X - BORDER_SKIP) &&
                gy in BORDER_SKIP until (GRID_Y - BORDER_SKIP)
        }
        // Cap to avoid flooding UI
        return filtered.take(12)
    }

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
                    Core.mean(roiA).`val`[0]
                } else {
                    val roiB = b.submat(y0, y1, x0, x1)
                    val tmp = Mat()
                    Core.absdiff(roiA, roiB, tmp)
                    val m = Core.mean(tmp).`val`[0]
                    tmp.release()
                    roiB.release()
                    m
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
        val defects = filterAndCap(simpleZoneCompare(reference, captured, threshold))
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
