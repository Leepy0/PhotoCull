package com.leepy0.photocull.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZoneId

/** 사진 1장 분석 결과. 크기·초점 위치는 화면 표시 방향(EXIF 회전 반영) 기준. */
data class PhotoAnalysis(
    val sharpness: Double,
    val focusX: Float,
    val focusY: Float,
    val dhash: Long,
    /** 원본 해상도(축소 전), 표시 방향 */
    val width: Int,
    val height: Int,
    /** EXIF 촬영 시각(epoch ms). 없으면 null(기존 값 유지) */
    val capturedAt: Long?,
    val iso: Int?,
    val exposure: String?,
    val aperture: String?,
    val focalLength: String?,
)

/** 디코드할 수 없는 파일(손상·미지원 형식) */
class UndecodableImageException(path: String) : IOException("cannot decode image: $path")

/** 분석용 그레이 영상(저장 픽셀 방향) */
class GrayImage(val pixels: ByteArray, val width: Int, val height: Int)

/**
 * 사진 1장 분석(DESIGN.md §4.1).
 * - 디코드: BitmapFactory, inSampleSize = 긴 변이 3000px 이상 남는 가장 큰 2의 거듭제곱(6000px → 2), ARGB_8888.
 *   실패하면 ImageDecoder(HEIF 등)로 같은 배율, 소프트웨어 메모리에 디코드.
 * - 그레이: 행 단위 getPixels로 Y = (77R + 150G + 29B) >> 8, 비트맵은 바로 해제.
 * - EXIF: 촬영 시각(SubSec·시간대), ISO, 셔터, 조리개, 초점거리, 방향.
 *
 * 실패는 예외로 알린다(호출 측이 analysisFailed로 기록). OutOfMemoryError도 그대로 던져 호출 측이 다시 시도할 수 있게 한다.
 */
class ImageAnalyzer(
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun analyze(path: String): PhotoAnalysis {
        val file = File(path)
        val exif = withContext(Dispatchers.IO) {
            if (!file.isFile) throw FileNotFoundException(path)
            readExif(file)
        }
        return withContext(Dispatchers.Default) { analyzeDecoded(file, exif) }
    }

    private fun analyzeDecoded(file: File, exif: ExifSummary): PhotoAnalysis {
        val decoded = decode(file) ?: throw UndecodableImageException(file.path)
        val gray = decoded.gray
        val sharp = Sharpness.compute(gray.pixels, gray.width, gray.height)
        val dhash = DHash.compute(gray.pixels, gray.width, gray.height)
        // ImageDecoder는 EXIF 방향을 이미 적용해 디코드하므로 다시 돌리지 않는다.
        val orientation = if (decoded.alreadyOriented) ExifOrientation.NORMAL else exif.orientation
        val (w, h) = ExifOrientation.displaySize(decoded.originalWidth, decoded.originalHeight, orientation)
        val (fx, fy) = ExifOrientation.displayPoint(sharp.focusX, sharp.focusY, orientation)
        return PhotoAnalysis(
            sharpness = sharp.score,
            focusX = fx,
            focusY = fy,
            dhash = dhash,
            width = w,
            height = h,
            capturedAt = exif.capturedAt,
            iso = exif.iso,
            exposure = exif.exposure,
            aperture = exif.aperture,
            focalLength = exif.focalLength,
        )
    }

    private class Decoded(
        val gray: GrayImage,
        val originalWidth: Int,
        val originalHeight: Int,
        val alreadyOriented: Boolean,
    )

    private fun decode(file: File): Decoded? =
        decodeWithBitmapFactory(file) ?: decodeWithImageDecoder(file)

    private fun decodeWithBitmapFactory(file: File): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(ow, oh)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        return Decoded(toGray(bitmap), ow, oh, alreadyOriented = false)
    }

    private fun decodeWithImageDecoder(file: File): Decoded? {
        var ow = 0
        var oh = 0
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                ow = info.size.width
                oh = info.size.height
                val sample = sampleSizeFor(ow, oh)
                if (sample > 1) decoder.setTargetSize(maxOf(1, ow / sample), maxOf(1, oh / sample))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (ow <= 0 || oh <= 0) {
            ow = bitmap.width
            oh = bitmap.height
        }
        return Decoded(toGray(bitmap), ow, oh, alreadyOriented = true)
    }

    /** 행 단위로 휘도만 뽑고 비트맵은 바로 해제한다. */
    private fun toGray(bitmap: Bitmap): GrayImage {
        try {
            val w = bitmap.width
            val h = bitmap.height
            val gray = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                Luma.fromArgb(row, w, gray, y * w)
            }
            return GrayImage(gray, w, h)
        } finally {
            bitmap.recycle()
        }
    }

    private class ExifSummary(
        val orientation: Int = ExifOrientation.NORMAL,
        val capturedAt: Long? = null,
        val iso: Int? = null,
        val exposure: String? = null,
        val aperture: String? = null,
        val focalLength: String? = null,
    )

    /** EXIF가 없거나 깨져도 분석은 계속한다(빈 요약). */
    private fun readExif(file: File): ExifSummary {
        val exif = try {
            ExifInterface(file)
        } catch (e: Exception) {
            return ExifSummary()
        }
        return try {
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                .takeIf { it in ExifOrientation.NORMAL..ExifOrientation.ROTATE_270 } ?: ExifOrientation.NORMAL
            val capturedAt = ExifValues.parseDateTime(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                zone(),
            ) ?: ExifValues.parseDateTime(
                exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
                zone(),
            )
            ExifSummary(
                orientation = orientation,
                capturedAt = capturedAt,
                iso = ExifValues.iso(
                    exif.intOrNull(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                    exif.intOrNull(ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX),
                    exif.intOrNull(ExifInterface.TAG_ISO_SPEED),
                ),
                exposure = ExifValues.exposure(exif.doubleOrNull(ExifInterface.TAG_EXPOSURE_TIME)),
                aperture = ExifValues.aperture(exif.doubleOrNull(ExifInterface.TAG_F_NUMBER)),
                focalLength = ExifValues.focalLength(exif.doubleOrNull(ExifInterface.TAG_FOCAL_LENGTH)),
            )
        } catch (e: Exception) {
            ExifSummary()
        }
    }

    private fun ExifInterface.intOrNull(tag: String): Int? =
        getAttributeInt(tag, -1).takeIf { it > 0 }

    private fun ExifInterface.doubleOrNull(tag: String): Double? =
        getAttributeDouble(tag, -1.0).takeIf { it > 0 }

    companion object {
        /** 분석 해상도: 긴 변이 이 값 이상 남는 가장 큰 2의 거듭제곱으로 축소 */
        const val MIN_LONG_SIDE = 3000

        /** 메모리 상한: 축소 후 화소 수가 이보다 크면 한 단계 더 줄인다(ARGB 64MB). 24MP 카메라 사진에는 해당 없음. */
        const val MAX_PIXELS = 16_000_000L

        fun sampleSizeFor(width: Int, height: Int): Int {
            val longSide = maxOf(width, height)
            var sample = 1
            while (longSide / (sample * 2) >= MIN_LONG_SIDE) sample *= 2
            while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS) sample *= 2
            return sample
        }
    }
}

/** ARGB → 8비트 휘도 Y = (77R + 150G + 29B) >> 8 */
object Luma {
    fun fromArgb(argb: IntArray, count: Int, out: ByteArray, outOffset: Int) {
        for (i in 0 until count) {
            val p = argb[i]
            val y = (77 * ((p ushr 16) and 0xFF) + 150 * ((p ushr 8) and 0xFF) + 29 * (p and 0xFF)) ushr 8
            out[outOffset + i] = y.toByte()
        }
    }
}
