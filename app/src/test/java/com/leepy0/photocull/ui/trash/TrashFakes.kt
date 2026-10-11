package com.leepy0.photocull.ui.trash

import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.review.MediaCard

/** 2026-10-11 12:00 UTC */
const val TRASH_NOW = 1_791_720_000_000L
const val DAY_MS = 86_400_000L
const val MB = 1024L * 1024L

fun trashedPhoto(id: Long, daysAgo: Int, bytes: Long = 34 * MB, hasRaw: Boolean = true) = MediaCard(
    id = id,
    path = "/storage/emulated/0/DCIM/.PhotoCullTrash/${id}_IMG_${3000 + id}.JPG",
    fileName = "IMG_${3000 + id}.JPG",
    type = MediaType.PHOTO,
    mimeType = "image/jpeg",
    capturedAt = TRASH_NOW - 40 * DAY_MS + id * 1000,
    bytes = bytes,
    hasRaw = hasRaw,
    width = 6000,
    height = 4000,
    sharpness = 22.0,
    focusX = 0.5f,
    focusY = 0.5f,
    durationMs = null,
    reviewState = ReviewState.TRASHED,
    groupId = null,
    trashedAt = TRASH_NOW - daysAgo * DAY_MS - 60_000,
    iso = 400,
    exposure = "0.002",
    aperture = "4.0",
    focalLength = "50",
)

fun trashedVideo(id: Long, daysAgo: Int, bytes: Long = 180 * MB) = trashedPhoto(id, daysAgo, bytes, hasRaw = false).copy(
    path = "/storage/emulated/0/DCIM/.PhotoCullTrash/${id}_MVI_${4000 + id}.MP4",
    fileName = "MVI_${4000 + id}.MP4",
    type = MediaType.VIDEO,
    mimeType = "video/mp4",
    sharpness = null,
    durationMs = 2_400,
)

object TrashFakeData {
    /** 최근에 지운 순 */
    val items: List<MediaCard> = listOf(
        trashedPhoto(1, daysAgo = 0),
        trashedPhoto(2, daysAgo = 0),
        trashedPhoto(3, daysAgo = 1, hasRaw = false, bytes = 9 * MB),
        trashedVideo(4, daysAgo = 2),
        trashedPhoto(5, daysAgo = 5),
        trashedPhoto(6, daysAgo = 12, hasRaw = false, bytes = 8 * MB),
        trashedPhoto(7, daysAgo = 21),
        trashedPhoto(8, daysAgo = 27),
        trashedPhoto(9, daysAgo = 28, hasRaw = false, bytes = 7 * MB),
        trashedPhoto(10, daysAgo = 29),
        trashedPhoto(11, daysAgo = 30),
    )

    fun ready(
        selection: Set<Long> = emptySet(),
        retentionDays: Int = 30,
        items: List<MediaCard> = TrashFakeData.items,
        confirm: DeleteConfirm? = null,
        busy: Boolean = false,
    ) = TrashUiState(
        phase = TrashPhase.READY,
        items = items,
        selection = selection,
        retentionDays = retentionDays,
        now = TRASH_NOW,
        busy = busy,
        confirm = confirm,
    )
}
