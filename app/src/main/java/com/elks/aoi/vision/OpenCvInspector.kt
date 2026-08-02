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
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import kotlin.math.abs
import kotlin.math.min

/**
 * Verdict for control equipment: doubt must never look like PASS.
 * Review K-2 (fail-unsafe) — P0 fix.
 */
enum class InspectVerdict {
    /** Analysis completed, no defects above threshold. */
    PASS,
    /** Analysis completed, defects found. */
    FAIL,
    /** Alignment or scene unreliable — operator must reshoot. */
    UNRELIABLE
}

object OpenCvInspector {

    private const val TAG = "OpenCvInspector"
    private const val WORK_W = 640
    private const val WORK_H = 480
    private const val MIN_GOOD_MATCHES = 12
    private const val MIN_INLIERS = 20
    private const val MIN_INLIER_RATIO = 0.35
    private const val GRID_X = 12
    private const val GRID_Y = 10
    private const val DEFAULT_THRESHOLD = 0.20f
    /** Fraction of cells flagged → scene mismatch, not component defects. */
    private const val MAX_DEFECT_FRACTION = 0.35f

    data class Result(
        val defects: List<DefectRegion>,
        val verdict: InspectVerdict,
        val aligned: Boolean,
        val matchCount: Int,
        val inlierCount: Int,
        val message: String
    )

    fun inspect(reference: Bitmap, captured: Bitmap, threshold: Float = DEFAULT_THRESHOLD): Result {
        if (!AoiApplication.openCvReady) {
            Log.w(TAG, "OpenCV not ready")
            return Result(
                emptyList(),
                InspectVerdict.UNRELIABLE,
                false,
                0,
                0,
                "⚠ OpenCV не готов — повторите съёмку"
            )
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

            val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(refGray, refGray)
            clahe.apply(capGray, capGray)

            val orb = ORB.create(2000)
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
            Log.i(TAG, "Good matches: $matchCount")

            // K-2: no alignment → UNRELIABLE, never PASS
            if (matchCount < MIN_GOOD_MATCHES) {
                return Result(
                    emptyList(),
                    InspectVerdict.UNRELIABLE,
                    false,
                    matchCount,
                    0,
                    "⚠ Мало совпадений ($matchCount) — повторите съёмку (ракурс/расстояние)"
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
                ransacMask.release()
                srcMat.release()
                dstMat.release()
                return unreliable("Гомография не найдена — повторите съёмку")
            }

            // Count RANSAC inliers (review §5)
            var inliers = 0
            if (!ransacMask.empty() && ransacMask.rows() > 0) {
                for (i in 0 until ransacMask.rows()) {
                    if (ransacMask.get(i, 0)[0] != 0.0) inliers++
                }
            }
            val inlierRatio = if (matchCount > 0) inliers.toDouble() / matchCount else 0.0

            if (inliers < MIN_INLIERS || inlierRatio < MIN_INLIER_RATIO) {
                H.release()
                ransacMask.release()
                srcMat.release()
                dstMat.release()
                return Result(
                    emptyList(),
                    InspectVerdict.UNRELIABLE,
                    false,
                    matchCount,
                    inliers,
                    "⚠ Слабое выравнивание (inliers $inliers / $matchCount) — повторите съёмку"
                )
            }

            if (!isHomographyPlausible(H)) {
                H.release()
                ransacMask.release()
                srcMat.release()
                dstMat.release()
                return unreliable("Искажённая геометрия — повторите съёмку")
            }

            warped = Mat()
            Imgproc.warpPerspective(
                capGray, warped, H,
                Size(WORK_W.toDouble(), WORK_H.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0)
            )

            // K-3: geometric validity mask (warp of ones), NOT brightness threshold
            val ones = Mat(WORK_H, WORK_W, CvType.CV_8UC1, Scalar(255.0))
            val geoMask = Mat()
            Imgproc.warpPerspective(
                ones, geoMask, H,
                Size(WORK_W.toDouble(), WORK_H.toDouble()),
                Imgproc.INTER_NEAREST,
                Core.BORDER_CONSTANT,
                Scalar(0.0)
            )
            // Erode slightly to drop partial border pixels from interpolation
            val erodeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.erode(geoMask, geoMask, erodeK)

            diff = Mat()
            Core.absdiff(refGray, warped, diff)
            Core.bitwise_and(diff, geoMask, diff)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_CLOSE, kernel)

            val raw = zoneDiff(diff, null, threshold, isDiffMap = true)
            // K-4: no BORDER_SKIP, no silent take(12) — show all zones
            val defects = raw

            ones.release()
            geoMask.release()
            erodeK.release()
            descRef.release()
            descCap.release()
            kpRef.release()
            kpCap.release()
            srcMat.release()
            dstMat.release()
            ransacMask.release()
            H.release()
            kernel.release()

            val cellCount = GRID_X * GRID_Y
            val tooMany = raw.size > (cellCount * MAX_DEFECT_FRACTION).toInt()

            // K-2: scene mismatch → UNRELIABLE, never green PASS
            if (tooMany) {
                return Result(
                    emptyList(),
                    InspectVerdict.UNRELIABLE,
                    true,
                    matchCount,
                    inliers,
                    "⚠ Слишком много отличий ($matchCount совп.) — ракурс/эталон не совпали, повторите"
                )
            }

            return if (defects.isEmpty()) {
                Result(
                    emptyList(),
                    InspectVerdict.PASS,
                    true,
                    matchCount,
                    inliers,
                    "✓ Годен ($matchCount совп., $inliers inliers) — крупных отличий нет"
                )
            } else {
                Result(
                    defects,
                    InspectVerdict.FAIL,
                    true,
                    matchCount,
                    inliers,
                    "⚠ Брак: зон ${defects.size} ($matchCount совп., $inliers inliers)"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV inspect failed", e)
            return unreliable("Ошибка анализа: ${e.message ?: "unknown"}")
        } finally {
            refMat?.release()
            capMat?.release()
            refGray?.release()
            capGray?.release()
            warped?.release()
            diff?.release()
        }
    }

    private fun unreliable(msg: String) = Result(
        emptyList(), InspectVerdict.UNRELIABLE, false, 0, 0, msg
    )

    /** Basic geometric sanity on H (review §5). */
    private fun isHomographyPlausible(H: Mat): Boolean {
        try {
            val det = H.get(0, 0)[0] * H.get(1, 1)[0] - H.get(0, 1)[0] * H.get(1, 0)[0]
            if (abs(det) < 0.05 || abs(det) > 20.0) return false
            // Map corners of work image and check scale roughly
            val corners = arrayOf(
                Point(0.0, 0.0),
                Point(WORK_W.toDouble(), 0.0),
                Point(WORK_W.toDouble(), WORK_H.toDouble()),
                Point(0.0, WORK_H.toDouble())
            )
            val src = MatOfPoint2f(*corners)
            val dst = MatOfPoint2f()
            Core.perspectiveTransform(src, dst, H)
            val pts = dst.toArray()
            src.release()
            dst.release()
            if (pts.size != 4) return false
            // Width of top edge after transform
            val w = kotlin.math.hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y)
            val h = kotlin.math.hypot(pts[3].x - pts[0].x, pts[3].y - pts[0].y)
            val scaleW = w / WORK_W
            val scaleH = h / WORK_H
            if (scaleW !in 0.5..1.8 || scaleH !in 0.5..1.8) return false
            return true
        } catch (_: Exception) {
            return false
        }
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
