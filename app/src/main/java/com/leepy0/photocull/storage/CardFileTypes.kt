package com.leepy0.photocull.storage

import com.leepy0.photocull.data.db.MediaType
import java.util.Locale

/**
 * 카드에서 가져오는 파일 종류. [priority]가 낮을수록 촬영 단위(같은 폴더 + 같은 기본 이름)의 대표 파일이 된다.
 * JPG/JPEG > HIF/HEIF > CR3. 영상은 각자 단위라 우선순위가 쓰이지 않는다.
 */
enum class CardFileKind(val mediaType: MediaType, val priority: Int, val mimeType: String) {
    JPEG(MediaType.PHOTO, 0, "image/jpeg"),
    HEIF(MediaType.PHOTO, 1, "image/heif"),
    CR3(MediaType.RAW, 2, "image/x-canon-cr3"),
    MP4(MediaType.VIDEO, 3, "video/mp4"),
    MOV(MediaType.VIDEO, 3, "video/quicktime"),
    ;

    val isVideo: Boolean get() = mediaType == MediaType.VIDEO
}

object CardFileTypes {
    const val DCIM = "DCIM"

    /** 대상 확장자면 종류, 아니면 null. 숨김 파일(.으로 시작, 예: macOS의 ._IMG_0001.JPG)은 대상이 아니다. */
    fun kindOf(name: String): CardFileKind? {
        if (isHidden(name)) return null
        return when (extension(name).lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> CardFileKind.JPEG
            "hif", "heif" -> CardFileKind.HEIF
            "cr3" -> CardFileKind.CR3
            "mp4" -> CardFileKind.MP4
            "mov" -> CardFileKind.MOV
            else -> null
        }
    }

    fun isTarget(name: String): Boolean = kindOf(name) != null

    fun isHidden(name: String): Boolean = name.startsWith(".")

    /** "IMG_0001.JPG" -> "IMG_0001" */
    fun baseName(name: String): String = if ('.' in name) name.substringBeforeLast('.') else name

    /** "IMG_0001.JPG" -> "JPG" (원래 대소문자 유지) */
    fun extension(name: String): String = if ('.' in name) name.substringAfterLast('.') else ""

    /** "DCIM/100CANON/IMG_0001.JPG" -> "DCIM/100CANON/IMG_0001" */
    fun withoutExtension(relPath: String): String {
        val slash = relPath.lastIndexOf('/')
        val dot = relPath.lastIndexOf('.')
        return if (dot > slash) relPath.substring(0, dot) else relPath
    }
}
