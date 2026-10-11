package com.leepy0.photocull.data.review

import com.leepy0.photocull.data.db.GroupKind
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState

/** 검토·휴지통 화면에 보이는 항목 1개(대표 파일 기준) */
data class MediaCard(
    val id: Long,
    /** 표시·재생할 실제 파일 경로(휴지통에 있으면 휴지통 경로) */
    val path: String,
    val fileName: String,
    val type: MediaType,
    val mimeType: String,
    val capturedAt: Long,
    /** 같은 촬영 단위(JPG+CR3) 전체 용량 */
    val bytes: Long,
    val hasRaw: Boolean,
    val width: Int?,
    val height: Int?,
    val sharpness: Double?,
    val focusX: Float?,
    val focusY: Float?,
    val durationMs: Long?,
    val reviewState: ReviewState,
    val groupId: Long?,
    val trashedAt: Long?,
    val iso: Int?,
    val exposure: String?,
    val aperture: String?,
    val focalLength: String?,
)

data class GroupCard(
    val id: Long,
    val kind: GroupKind,
    /** 촬영 순서대로 */
    val members: List<MediaCard>,
    val bestId: Long,
    val bestPinned: Boolean,
    /** 그룹 최고 점수의 60% 미만인 멤버(고정한 베스트도 해당되면 포함) */
    val relativeBlurIds: Set<Long>,
    /** 그룹 최고 점수조차 흐림 임계값 미만(전체가 흐림) */
    val allBlurry: Boolean,
) {
    val best: MediaCard get() = members.firstOrNull { it.id == bestId } ?: members.first()
    val totalBytes: Long get() = members.sumOf { it.bytes }
    val startAt: Long get() = members.minOf { it.capturedAt }
    val endAt: Long get() = members.maxOf { it.capturedAt }

    /** 기본 삭제 선택: 베스트와 이미 '남기기'한 사진을 뺀 나머지 */
    fun defaultSelection(): Set<Long> =
        members.filter { it.id != bestId && it.reviewState == ReviewState.PENDING }.map { it.id }.toSet()
}

data class ReviewCounts(
    val blur: Int = 0,
    val groups: Int = 0,
    /** 그룹들에 속한 사진 수 합 */
    val groupPhotos: Int = 0,
    val videos: Int = 0,
    val shortVideos: Int = 0,
) {
    val total: Int get() = blur + groups + videos
}

/** 상세 보기에서 좌우로 넘길 목록의 출처 */
sealed interface ViewerSource {
    data object Blur : ViewerSource
    data class Group(val groupId: Long) : ViewerSource
    data object Videos : ViewerSource
    data object Trash : ViewerSource

    fun encode(): String = when (this) {
        Blur -> "blur"
        is Group -> "group:$groupId"
        Videos -> "videos"
        Trash -> "trash"
    }

    companion object {
        fun decode(s: String): ViewerSource = when {
            s == "blur" -> Blur
            s.startsWith("group:") -> Group(s.removePrefix("group:").toLong())
            s == "videos" -> Videos
            else -> Trash
        }
    }
}
