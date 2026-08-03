package com.elks.aoi.vision

import android.graphics.Bitmap
import android.util.Log
import com.elks.aoi.AoiApplication
import com.elks.aoi.camera.DefectRegion
import com.elks.aoi.settings.AppSettings
import com.elks.aoi.settings.DiffMetric
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
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

enum class InspectVerdict {
    PASS,
    FAIL,
    UNRELIABLE
}

object OpenCvInspector {

    private const val TAG = "OpenCvInspector"

    data class Result(
        val defects: List<DefectRegion>,
        val verdict: InspectVerdict,
        val aligned: Boolean,
        val matchCount: Int,
        val inlierCount: Int,
        val message: String
    )

    fun inspect(reference: Bitmap, captured: Bitmap, settings: AppSettings): Result {
        if (!AoiApplication.openCvReady) {
            return Result(
                emptyList(), InspectVerdict.UNRELIABLE, false, 0, 0,
                "⚠ OpenCV не готов — повторите съёмку"
            )
        }

        val longSide = settings.workResolution.longSide
        val aspect = reference.width.toFloat() / reference.height.coerceAtLeast(1)
        val workW: Int
        val workH: Int
        if (aspect >= 1f) {
            workW = longSide
            workH = (longSide / aspect).toInt().coerceAtLeast(240)
        } else {
            workH = longSide
            workW = (longSide * aspect).toInt().coerceAtLeast(240)
        }

        val gridX = settings.gridX
        val gridY = settings.gridY
        val threshold = settings.threshold
        val minMatches = settings.minMatches
        val minInliers = settings.minInliers
        val maxFrac = settings.maxDefectFraction

        var refMat: Mat? = null
        var capMat: Mat? = null
        var refGray: Mat? = null
        var capGray: Mat? = null
        var warped: Mat? = null
        var diff: Mat? = null

        try {
            refMat = bitmapToBgr(reference)
            capMat = bitmapToBgr(captured)
            Imgproc.resize(refMat, refMat, Size(workW.toDouble(), workH.toDouble()))
            Imgproc.resize(capMat, capMat, Size(workW.toDouble(), workH.toDouble()))

            refGray = Mat()
            capGray = Mat()
            Imgproc.cvtColor(refMat, refGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(capMat, capGray, Imgproc.COLOR_BGR2GRAY)

            if (settings.useClahe) {
                val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                clahe.apply(refGray, refGray)
                clahe.apply(capGray, capGray)
            }

            val orb = ORB.create(min(3000, longSide * 2))
            val kpRef = MatOfKeyPoint()
            val kpCap = MatOfKeyPoint()
            val descRef = Mat()
            val descCap = Mat()
            orb.detectAndCompute(refGray, Mat(), kpRef, descRef)
            orb.detectAndCompute(capGray, Mat(), kpCap, descCap)

            if (descRef.empty() || descCap.empty()) {
                return unreliable("Нет признаков для выравнивания — повторите съёмку")
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
            Log.i(TAG, "matches=$matchCount res=${workW}x$workH metric=${settings.metric}")

            if (matchCount < minMatches) {
                return Result(
                    emptyList(), InspectVerdict.UNRELIABLE, false, matchCount, 0,
                    "⚠ Мало совпадений ($matchCount) — повторите съёмку"
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
            val ransacMask = Mat()
            val H = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 2.0, ransacMask)

            if (H.empty()) {
                releaseAll(srcMat, dstMat, ransacMask, descRef, descCap, kpRef, kpCap)
                return unreliable("Гомография не найдена — повторите съёмку")
            }

            var inliers = 0
            if (!ransacMask.empty()) {
                for (i in 0 until ransacMask.rows()) {
                    if (ransacMask.get(i, 0)[0] != 0.0) inliers++
                }
            }
            val inlierRatio = if (matchCount > 0) inliers.toDouble() / matchCount else 0.0
            if (inliers < minInliers || inlierRatio < 0.30) {
                releaseAll(srcMat, dstMat, ransacMask, H, descRef, descCap, kpRef, kpCap)
                return Result(
                    emptyList(), InspectVerdict.UNRELIABLE, false, matchCount, inliers,
                    "⚠ Слабое выравнивание (inliers $inliers/$matchCount) — повторите"
                )
            }

            if (!isHomographyPlausible(H, workW, workH)) {
                releaseAll(srcMat, dstMat, ransacMask, H, descRef, descCap, kpRef, kpCap)
                return unreliable("Искажённая геометрия — повторите съёмку")
            }

            warped = Mat()
            Imgproc.warpPerspective(
                capGray, warped, H,
                Size(workW.toDouble(), workH.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0)
            )

            val geoMask = Mat()
            if (settings.useGeometricMask) {
                val ones = Mat(workH, workW, CvType.CV_8UC1, Scalar(255.0))
                Imgproc.warpPerspective(
                    ones, geoMask, H,
                    Size(workW.toDouble(), workH.toDouble()),
                    Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0)
                )
                val erodeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.erode(geoMask, geoMask, erodeK)
                ones.release()
                erodeK.release()
            } else {
                geoMask.create(workH, workW, CvType.CV_8UC1)
                geoMask.setTo(Scalar(255.0))
            }

            val raw = when (settings.metric) {
                DiffMetric.MEAN -> {
                    diff = Mat()
                    Core.absdiff(refGray, warped, diff)
                    Core.bitwise_and(diff, geoMask, diff)
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                    Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_OPEN, kernel)
                    kernel.release()
                    zoneMean(diff, gridX, gridY, threshold)
                }
                DiffMetric.ZNCC -> zoneZncc(refGray, warped, geoMask, gridX, gridY, threshold)
                DiffMetric.PIXEL_RATIO -> {
                    diff = Mat()
                    Core.absdiff(refGray, warped, diff)
                    Core.bitwise_and(diff, geoMask, diff)
                    zonePixelRatio(diff, gridX, gridY, threshold)
                }
            }

            val defects = if (settings.showAllZones) raw else raw.take(16)

            releaseAll(srcMat, dstMat, ransacMask, H, descRef, descCap, kpRef, kpCap, geoMask)

            val cellCount = gridX * gridY
            if (raw.size > (cellCount * maxFrac).toInt()) {
                return Result(
                    emptyList(), InspectVerdict.UNRELIABLE, true, matchCount, inliers,
                    "⚠ Слишком много отличий — ракурс/эталон, повторите"
                )
            }

            return if (defects.isEmpty()) {
                Result(
                    emptyList(), InspectVerdict.PASS, true, matchCount, inliers,
                    "✓ Годен (${workW}px, $matchCount совп., $inliers inliers)"
                )
            } else {
                Result(
                    defects, InspectVerdict.FAIL, true, matchCount, inliers,
                    "⚠ Брак: зон ${defects.size} (${settings.metric}, ${workW}px)"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "inspect failed", e)
            return unreliable("Ошибка анализа: ${e.message ?: "?"}")
        } finally {
            refMat?.release()
            capMat?.release()
            refGray?.release()
            capGray?.release()
            warped?.release()
            diff?.release()
        }
    }

    private fun unreliable(msg: String) =
        Result(emptyList(), InspectVerdict.UNRELIABLE, false, 0, 0, msg)

    private fun zoneMean(diff: Mat, gridX: Int, gridY: Int, threshold: Float): List<DefectRegion> {
        val out = mutableListOf<DefectRegion>()
        val cellW = diff.cols() / gridX
        val cellH = diff.rows() / gridY
        if (cellW < 1 || cellH < 1) return out
        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val x0 = gx * cellW
                val y0 = gy * cellH
                val x1 = if (gx == gridX - 1) diff.cols() else x0 + cellW
                val y1 = if (gy == gridY - 1) diff.rows() else y0 + cellH
                val roi = diff.submat(y0, y1, x0, x1)
                val norm = (Core.mean(roi).`val`[0] / 255.0).toFloat()
                roi.release()
                if (norm > threshold) out.add(cell(gx, gy, gridX, gridY))
            }
        }
        return out
    }

    /** Score = 1 − ZNCC; higher = more different. Threshold ~0.15–0.35. */
    private fun zoneZncc(
        ref: Mat, warped: Mat, mask: Mat,
        gridX: Int, gridY: Int, threshold: Float
    ): List<DefectRegion> {
        val out = mutableListOf<DefectRegion>()
        val cellW = ref.cols() / gridX
        val cellH = ref.rows() / gridY
        if (cellW < 2 || cellH < 2) return out

        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val x0 = gx * cellW
                val y0 = gy * cellH
                val x1 = if (gx == gridX - 1) ref.cols() else x0 + cellW
                val y1 = if (gy == gridY - 1) ref.rows() else y0 + cellH
                val r = ref.submat(y0, y1, x0, x1)
                val w = warped.submat(y0, y1, x0, x1)
                val m = mask.submat(y0, y1, x0, x1)

                val meanR = Core.mean(r, m).`val`[0]
                val meanW = Core.mean(w, m).`val`[0]

                // Manual ZNCC on downsampled pixels for speed
                var num = 0.0
                var denR = 0.0
                var denW = 0.0
                var n = 0
                val step = maxOf(1, min(cellW, cellH) / 16)
                var yy = 0
                while (yy < r.rows()) {
                    var xx = 0
                    while (xx < r.cols()) {
                        if (m.get(yy, xx)[0] > 0) {
                            val a = r.get(yy, xx)[0] - meanR
                            val b = w.get(yy, xx)[0] - meanW
                            num += a * b
                            denR += a * a
                            denW += b * b
                            n++
                        }
                        xx += step
                    }
                    yy += step
                }
                r.release(); w.release(); m.release()

                val zncc = if (n > 8 && denR > 1 && denW > 1) {
                    num / (sqrt(denR) * sqrt(denW))
                } else 1.0
                val score = (1.0 - zncc).toFloat().coerceIn(0f, 2f)
                // Map threshold similarly: user threshold 0.18 ≈ mild difference
                if (score > threshold * 1.2f) out.add(cell(gx, gy, gridX, gridY))
            }
        }
        return out
    }

    /** Fraction of pixels above absolute diff cutoff. */
    private fun zonePixelRatio(diff: Mat, gridX: Int, gridY: Int, threshold: Float): List<DefectRegion> {
        val out = mutableListOf<DefectRegion>()
        val cellW = diff.cols() / gridX
        val cellH = diff.rows() / gridY
        if (cellW < 1 || cellH < 1) return out
        // Absolute level ~ 25–80 depending on user threshold
        val absCut = (25 + threshold * 120).toInt().coerceIn(15, 100).toDouble()

        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val x0 = gx * cellW
                val y0 = gy * cellH
                val x1 = if (gx == gridX - 1) diff.cols() else x0 + cellW
                val y1 = if (gy == gridY - 1) diff.rows() else y0 + cellH
                val roi = diff.submat(y0, y1, x0, x1)
                val binary = Mat()
                Imgproc.threshold(roi, binary, absCut, 255.0, Imgproc.THRESH_BINARY)
                val ratio = Core.countNonZero(binary).toFloat() / (roi.rows() * roi.cols())
                binary.release()
                roi.release()
                if (ratio > threshold) out.add(cell(gx, gy, gridX, gridY))
            }
        }
        return out
    }

    private fun cell(gx: Int, gy: Int, gridX: Int, gridY: Int) = DefectRegion(
        left = gx.toFloat() / gridX,
        top = gy.toFloat() / gridY,
        right = (gx + 1).toFloat() / gridX,
        bottom = (gy + 1).toFloat() / gridY
    )

    private fun isHomographyPlausible(H: Mat, workW: Int, workH: Int): Boolean {
        try {
            val det = H.get(0, 0)[0] * H.get(1, 1)[0] - H.get(0, 1)[0] * H.get(1, 0)[0]
            if (abs(det) < 0.05 || abs(det) > 20.0) return false
            val corners = arrayOf(
                Point(0.0, 0.0),
                Point(workW.toDouble(), 0.0),
                Point(workW.toDouble(), workH.toDouble()),
                Point(0.0, workH.toDouble())
            )
            val src = MatOfPoint2f(*corners)
            val dst = MatOfPoint2f()
            Core.perspectiveTransform(src, dst, H)
            val pts = dst.toArray()
            src.release(); dst.release()
            if (pts.size != 4) return false
            val w = kotlin.math.hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y)
            val h = kotlin.math.hypot(pts[3].x - pts[0].x, pts[3].y - pts[0].y)
            val scaleW = w / workW
            val scaleH = h / workH
            return scaleW in 0.5..1.8 && scaleH in 0.5..1.8
        } catch (_: Exception) {
            return false
        }
    }

    private fun releaseAll(vararg mats: Mat?) {
        mats.forEach { it?.release() }
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
