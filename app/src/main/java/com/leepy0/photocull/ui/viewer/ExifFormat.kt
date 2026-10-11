package com.leepy0.photocull.ui.viewer

import com.leepy0.photocull.data.review.MediaCard
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 뷰어 상단의 촬영 정보 한 줄: "ISO 400 · 1/250 · f/5.6 · 50mm" (순수 함수) */
object ExifFormat {
    private const val SEPARATOR = " · "

    fun line(card: MediaCard): String? {
        val parts = listOfNotNull(
            card.iso?.takeIf { it > 0 }?.let { "ISO $it" },
            card.exposure?.let(::exposure),
            card.aperture?.let(::aperture),
            card.focalLength?.let(::focalLength),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** "0.004" → "1/250", "1/250" 그대로, "2" → "2s" */
    fun exposure(raw: String): String? {
        val s = raw.trim().removeSuffix("s").removeSuffix("sec").trim()
        if (s.isEmpty()) return null
        if (s.contains('/')) return s
        val v = s.toDoubleOrNull() ?: return raw.trim()
        if (v <= 0.0) return null
        return if (v < 1.0) {
            "1/${(1.0 / v).roundToLong()}"
        } else {
            "${trim(v)}s"
        }
    }

    /** "5.6" / "f/5.6" / "F5.6" → "f/5.6", "8.0" → "f/8" */
    fun aperture(raw: String): String? {
        val s = raw.trim().lowercase(Locale.ROOT).removePrefix("f/").removePrefix("f").trim()
        if (s.isEmpty()) return null
        val v = s.toDoubleOrNull() ?: return raw.trim()
        if (v <= 0.0) return null
        return "f/${trim(v)}"
    }

    /** "50" / "50.0" / "50mm" / "50 mm" → "50mm" */
    fun focalLength(raw: String): String? {
        val s = raw.trim().lowercase(Locale.ROOT).removeSuffix("mm").trim()
        if (s.isEmpty()) return null
        val v = s.toDoubleOrNull() ?: return raw.trim()
        if (v <= 0.0) return null
        return "${v.roundToInt()}mm"
    }

    private fun trim(v: Double): String {
        val rounded = (v * 10).roundToLong() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
